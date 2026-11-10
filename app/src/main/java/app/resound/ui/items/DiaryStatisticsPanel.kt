package app.resound.ui.items

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.resound.diary.DeviceStatistics
import app.resound.diary.DeviceStatisticsDay
import app.resound.diary.StatisticsAccess
import app.resound.ui.LocalShellPageActive
import app.resound.ui.collectWhenPageActive
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.ZoneId

/** 只在当前前台页读取选定日期的元数据，不自动进入模型上下文或电脑归档。 */
@Composable
internal fun DiaryStatisticsPanel(date: LocalDate, memory: app.resound.digest.MemoryRepository? = null, dayStats: app.resound.capture.DayStats? = null) {
    val context = LocalContext.current
    val active = LocalShellPageActive.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val mayRead = active && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val textStats by remember(date, memory) { memory?.dayTextStats(date) ?: flowOf(null) }.collectWhenPageActive(initial = null)
    var refresh by remember { mutableIntStateOf(0) }
    val deviceReader = remember(context) { DeviceStatistics(context) }
    var deviceFailed by remember(date) { mutableStateOf(false) }
    val device by key(date) { produceState<DeviceStatisticsDay?>(null, mayRead, refresh) {
        deviceFailed = false
        if (!mayRead) return@produceState
        try { value = withContext(Dispatchers.IO) { deviceReader.read(date) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { deviceFailed = true }
    } }
    var expanded by remember(date) { mutableStateOf(false) }
    fun open(intent: Intent?) {
        if (intent == null) Toast.makeText(context, "系统暂不支持此授权入口", Toast.LENGTH_SHORT).show()
        else runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "无法打开系统授权页，请在系统设置中检查", Toast.LENGTH_LONG).show() }
    }
    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("当天记录", style = RsType.headline, color = Rs.colors.text, modifier = Modifier.weight(1f))
            Pill("刷新", filled = false) { refresh++ }
        }
        RsCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            StatisticsHeading("文字与事件", null)
            val words = textStats
            if (words == null) StatisticsMessage("文字整理统计尚未就绪")
            else {
                MetadataRow("已转写", "${words.transcribedCharacters} 字")
                MetadataRow("已整理 / 待整理", "${words.digestedCharacters} / ${words.pendingCharacters} 字")
                MetadataRow("当天事件", "${words.events} 件")
                if (words.transcribedCharacters > 0) RankingTrack(
                    (words.digestedCharacters.toDouble() / words.transcribedCharacters).toFloat().coerceIn(0f, 1f), "已整理文字占比")
                StatisticsMessage("按非空白字符计数，含标点；待整理文字不重复计入已转写合计")
            }
            StatisticsDivider()
            val stats = device
            StatisticsHeading("手机使用", stats?.usage?.takeIf { it.state == StatisticsAccess.Ready }?.let { duration(it.apps.sumOf { app -> app.foregroundMillis }) })
            if (stats == null) StatisticsMessage(if (deviceFailed) "暂时无法读取，请刷新重试" else "正在读取使用记录…")
            else {
                StatisticsMessage(stats.usage.message)
                if (stats.usage.state == StatisticsAccess.PermissionRequired) Pill("授权使用情况", filled = false) { open(DeviceStatistics.usageSettingsIntent(context)) }
                if (stats.usage.state == StatisticsAccess.Ready) {
                    val apps = stats.usage.apps.take(if (expanded) Int.MAX_VALUE else 3)
                    val fractions = rankingFractions(apps.map { it.foregroundMillis })
                    apps.forEachIndexed { index, app ->
                        MetadataRow(app.label, duration(app.foregroundMillis))
                        RankingTrack(fractions[index], "${app.label}使用时长排行")
                    }
                }
            }
            StatisticsDivider()
            StatisticsHeading("通知", stats?.notifications?.total?.let { "$it 条" })
            if (stats == null) StatisticsMessage(if (deviceFailed) "暂时无法读取，请刷新重试" else "正在读取通知统计…")
            else {
                StatisticsMessage(stats.notifications.message)
                if (stats.notifications.state == StatisticsAccess.PermissionRequired) Pill("授权通知计数", filled = false) { open(DeviceStatistics.notificationSettingsIntent(context)) }
                if (stats.notifications.state == StatisticsAccess.Ready) {
                    StatisticsMessage("实际观测 ${duration(stats.notifications.coveredMillis)}")
                    val apps = stats.notifications.apps.take(if (expanded) Int.MAX_VALUE else 3)
                    val fractions = rankingFractions(apps.map { it.count.toLong() })
                    apps.forEachIndexed { index, app ->
                        MetadataRow(app.label, "${app.count} 条通知")
                        RankingTrack(fractions[index], "${app.label}通知数量排行")
                    }
                    if (stats.notifications.hourly.isNotEmpty()) NotificationHistogram(date, stats.notifications.hourly)
                }
            }
            StatisticsDivider()
            FootprintSoundSummary(date, dayStats)
            Spacer(Modifier.height(10.dp))
            Pill(if (expanded) "收起时序与明细" else "查看时序与明细", filled = false) { expanded = !expanded }
            if (expanded) {
                StatisticsDivider()
                StatisticsHeading("使用时间序列", null)
                val slices = stats?.usage?.timeline.orEmpty()
                if (slices.isEmpty()) StatisticsMessage("没有可展示的使用时段；未记录不等于未使用")
                else {
                    val zone = ZoneId.systemDefault()
                    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    stats?.usage?.apps.orEmpty().take(5).forEach { app ->
                        MetadataRow(app.label, duration(app.foregroundMillis))
                        UsageSpans(start, end, slices.filter { it.packageName == app.packageName }.map { it.startAt to it.endAt }, app.label)
                    }
                    MetadataRow("00:00", "24:00")
                    slices.sortedBy { it.startAt }.takeLast(20).forEach { slice -> MetadataRow("${formatClock(slice.startAt)}—${formatClock(slice.endAt)}", slice.label) }
                    if (slices.size > 20) StatisticsMessage("以上列出最近20个使用时段；条形图显示前5个应用的全天时段")
                }
                val notifications = stats?.notifications
                if (!notifications?.hourly.isNullOrEmpty()) {
                    StatisticsHeading("通知小时记录", null)
                    notifications?.hourly.orEmpty().forEach { hour -> MetadataRow(formatClock(hour.hourStartMillis), "${hour.count} 条") }
                    StatisticsMessage("只列出已记录的小时，空白时段不推定为零")
                }
            }
        }
        StatisticsMessage("使用与通知统计在主动归档时随日记保存到电脑。足迹明细留在手机；模型参考范围由整理设置控制。")
    }
}

@Composable
private fun RankingTrack(fraction: Float, description: String) {
    val track = Rs.colors.separator
    val ink = Rs.colors.accent
    Canvas(Modifier.fillMaxWidth().height(5.dp).semantics { contentDescription = description }) {
        drawRect(track)
        if (fraction > 0) drawRect(ink, size = Size(size.width * fraction, size.height))
    }
}

@Composable
private fun NotificationHistogram(date: LocalDate, hours: List<app.resound.diary.NotificationHour>) {
    val bars = remember(date, hours) { notificationChartBars(hours, date, ZoneId.systemDefault()) }
    val baseline = Rs.colors.separator
    val ink = Rs.colors.accent
    Spacer(Modifier.height(10.dp))
    Text("通知小时分布", style = RsType.caption, color = Rs.colors.textSecondary)
    Canvas(Modifier.fillMaxWidth().height(54.dp).semantics { contentDescription = "已记录小时的通知条形图；空白不代表零" }) {
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        bars.forEach { bar ->
            val width = (size.width * (bar.until - bar.from) - 2.dp.toPx()).coerceAtLeast(1f)
            val height = size.height * bar.height
            if (height > 0) drawRect(ink, Offset(size.width * bar.from, size.height - height), Size(width, height))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("00:00", style = RsType.caption, color = Rs.colors.textTertiary)
        Text("未记录时段留空", style = RsType.caption, color = Rs.colors.textTertiary)
        Text("24:00", style = RsType.caption, color = Rs.colors.textTertiary)
    }
}

@Composable
private fun StatisticsHeading(title: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = RsType.headline, color = Rs.colors.text, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = RsType.headline, color = Rs.colors.accent) }
    }
}

@Composable
private fun StatisticsMessage(text: String) = Text(text, style = RsType.caption, color = Rs.colors.textSecondary, modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun StatisticsDivider() = Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = RsType.subhead, color = Rs.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = RsType.subhead, color = Rs.colors.text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun UsageSpans(start: Long, end: Long, spans: List<Pair<Long, Long>>, label: String) {
    val track = Rs.colors.separator
    val ink = Rs.colors.accent
    Canvas(Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = "$label 在所选日期的使用时段" }) {
        drawRect(track)
        val duration = (end - start).coerceAtLeast(1).toDouble()
        spans.forEach { (from, until) ->
            val left = ((from - start) / duration).coerceIn(0.0, 1.0).toFloat() * size.width
            val right = ((until - start) / duration).coerceIn(0.0, 1.0).toFloat() * size.width
            if (right > left) drawRect(ink, Offset(left, 0f), Size(right - left, size.height))
        }
    }
}

private fun duration(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        millis == 0L -> "0 分钟"
        minutes == 0L -> "不足1分钟"
        minutes < 60 -> "$minutes 分钟"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}
