package app.resound.diary

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId

enum class StatisticsAccess { Ready, PermissionRequired, NoHistory, Unavailable }
data class AppUsage(val packageName: String, val label: String, val foregroundMillis: Long)
data class AppUsageSlice(val packageName: String, val label: String, val startAt: Long, val endAt: Long)
data class AppNotificationCount(val packageName: String, val label: String, val count: Int)
data class NotificationHour(val hourStartMillis: Long, val count: Int)
data class UsageDayStats(val state: StatisticsAccess, val message: String,
    val apps: List<AppUsage> = emptyList(), val timeline: List<AppUsageSlice> = emptyList())
data class NotificationDayStats(val state: StatisticsAccess, val message: String,
    val observedFromMillis: Long? = null, val observedUntilMillis: Long? = null,
    val coveredMillis: Long = 0, val total: Int? = null,
    val apps: List<AppNotificationCount> = emptyList(), val hourly: List<NotificationHour> = emptyList())
data class DeviceStatisticsDay(val usage: UsageDayStats, val notifications: NotificationDayStats)

/** 只读系统前台事件及本应用的通知计数，不查询应用内容、不自动交给模型或导出。 */
class DeviceStatistics(private val context: Context) {
    private val labels = mutableMapOf<String, String>()
    private fun label(pkg: String) = labels.getOrPut(pkg) {
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }

    fun read(date: LocalDate): DeviceStatisticsDay = DeviceStatisticsDay(readUsage(date), readNotifications(date))

    fun usagePermitted(): Boolean = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    fun notificationsPermitted(): Boolean = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(context, MetadataNotificationListener::class.java))

    private fun readUsage(date: LocalDate): UsageDayStats {
        if (!usagePermitted()) return UsageDayStats(StatisticsAccess.PermissionRequired, "需要在系统中授权使用情况访问；只读取应用名称、前台时段和时长")
        val (start, end) = dayWindow(date)
        if (end <= start) return UsageDayStats(StatisticsAccess.NoHistory, "所选日期尚无使用记录")
        return try {
            // 最多回看一天只为重建跨午夜前台状态，最终时长和明细严格裁到所选日。
            val stream = context.getSystemService(UsageStatsManager::class.java).queryEvents(start - 86_400_000L, end)
                ?: return UsageDayStats(StatisticsAccess.Unavailable, "系统暂未提供使用事件，请解锁手机后刷新")
            val event = UsageEvents.Event()
            val transitions = mutableListOf<UsageTransition>()
            var count = 0
            var sawSelectedDay = false
            while (stream.hasNextEvent() && count < 50_000) {
                stream.getNextEvent(event); count++
                if (event.timeStamp >= start) sawSelectedDay = true
                val kind = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> UsageTransitionKind.Foreground
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> UsageTransitionKind.Background
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN,
                    UsageEvents.Event.DEVICE_STARTUP -> UsageTransitionKind.Reset
                    else -> null
                }
                if (kind != null) transitions += UsageTransition(event.timeStamp, event.packageName.orEmpty(), kind)
            }
            if (stream.hasNextEvent()) return UsageDayStats(StatisticsAccess.Unavailable, "系统事件过多，本次没有完整统计，请稍后刷新")
            if (count == 0) return UsageDayStats(StatisticsAccess.NoHistory, "系统没有保留这一天的使用明细，不表示使用时长为零")
            val slices = usageSlices(transitions, start, end)
            if (!sawSelectedDay && slices.isEmpty()) return UsageDayStats(StatisticsAccess.NoHistory, "系统没有提供所选日的明细，不表示使用时长为零")
            val apps = slices.groupBy { it.packageName }.map { (pkg, rows) ->
                AppUsage(pkg, label(pkg), rows.sumOf { it.endAt - it.startAt })
            }.sortedWith(compareByDescending<AppUsage> { it.foregroundMillis }.thenBy { it.packageName })
            val displayed = slices.takeLast(2_000).map { AppUsageSlice(it.packageName, label(it.packageName), it.startAt, it.endAt) }
            UsageDayStats(StatisticsAccess.Ready,
                "按本日系统前台切换事件估算，分屏或系统缺失事件可能与系统屏幕时间不同" +
                    if (slices.size > displayed.size) "；明细已限制为最近时段" else "", apps, displayed)
        } catch (_: SecurityException) {
            UsageDayStats(StatisticsAccess.PermissionRequired, "使用情况访问已关闭，请重新授权")
        } catch (_: RuntimeException) {
            UsageDayStats(StatisticsAccess.Unavailable, "暂时无法读取系统使用记录，请稍后刷新")
        }
    }

    private fun readNotifications(date: LocalDate): NotificationDayStats {
        if (!notificationsPermitted()) return NotificationDayStats(StatisticsAccess.PermissionRequired,
            "需要通知使用权。系统授权范围包含通知内容；本应用只统计包名、时间和数量，不读取标题或正文")
        val (start, end) = dayWindow(date)
        val data = try { NotificationMetadataStore(context).read(start, end) }
        catch (_: RuntimeException) { return NotificationDayStats(StatisticsAccess.Unavailable, "本机通知统计暂不可读，请稍后刷新") }
        if (data.coveredMillis == 0L && data.counts.isEmpty()) return NotificationDayStats(StatisticsAccess.NoHistory,
            "只从监听连接后开始计数；这一天没有已观测时段，不表示通知为零")
        return NotificationDayStats(StatisticsAccess.Ready,
            "仅覆盖通知监听实际连接的时段；微信通知条数不等于收发消息数，更新同一通知不重复计数；不含持续运行与分组汇总通知。日总计长期保留，时序明细最多31天" +
                (if (data.recordedZones.isNotEmpty()) "；按记录时区 ${data.recordedZones.sorted().joinToString("、")}" else "") +
                if (data.truncated) "；部分明细已达到保留上限" else "",
            data.from, data.until, data.coveredMillis, data.counts.values.sum(),
            data.counts.map { (pkg, count) -> AppNotificationCount(pkg, label(pkg), count) }
                .sortedWith(compareByDescending<AppNotificationCount> { it.count }.thenBy { it.packageName }),
            data.hours.map { (at, count) -> NotificationHour(at, count) }.sortedBy { it.hourStartMillis })
    }

    private fun dayWindow(date: LocalDate): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return start to minOf(System.currentTimeMillis(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }

    companion object {
        fun usageSettingsIntent(context: Context) = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun notificationSettingsIntent(context: Context) = (if (Build.VERSION.SDK_INT >= 30)
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, MetadataNotificationListener::class.java).flattenToString())
        else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

internal enum class UsageTransitionKind { Foreground, Background, Reset }
internal data class UsageTransition(val at: Long, val packageName: String, val kind: UsageTransitionKind)
internal data class UsageSlice(val packageName: String, val startAt: Long, val endAt: Long)

internal fun usageSlices(events: List<UsageTransition>, start: Long, end: Long): List<UsageSlice> {
    val result = mutableListOf<UsageSlice>()
    var active: String? = null
    var since = start
    fun close(at: Long) {
        val pkg = active
        val until = at.coerceIn(start, end)
        if (pkg != null && until > since) result += UsageSlice(pkg, since, until)
        active = null
    }
    for (event in events.sortedBy { it.at }) {
        if (event.at >= end) break
        when (event.kind) {
            UsageTransitionKind.Foreground -> {
                if (event.packageName.isNotBlank() && active != event.packageName) {
                    close(event.at); active = event.packageName; since = maxOf(start, event.at)
                }
            }
            UsageTransitionKind.Background -> {
                if (active == event.packageName) close(event.at)
            }
            UsageTransitionKind.Reset -> close(event.at)
        }
    }
    close(end)
    return result
}
