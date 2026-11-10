package app.resound.health

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.health.connect.AggregateRecordsRequest
import android.health.connect.AggregateRecordsResponse
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.AggregationType
import android.health.connect.datatypes.DistanceRecord
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.ExerciseSessionType
import android.health.connect.datatypes.StepsRecord
import android.os.Build
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class MovementAvailability { Available, Unsupported, Unavailable }
enum class MovementStatus { Available, NoData, NotAuthorized, Unavailable, Failed }

data class MovementAccess(
    val availability: MovementAvailability,
    val grantedPermissions: Set<String>,
    val missingPermissions: Set<String>,
)

data class MovementMetric<T>(
    val status: MovementStatus,
    val value: T? = null,
    val sourcePackages: List<String> = emptyList(),
    val message: String? = null,
)

data class StepsWindow(val startAt: Long, val endAt: Long, val count: Long?)
data class ExerciseWindow(val startAt: Long, val endAt: Long, val typeCode: Int,
    val typeLabel: String, val sourcePackage: String)

data class DailyMovementSnapshot(
    val date: LocalDate,
    val zoneId: String,
    val access: MovementAccess,
    val steps: MovementMetric<Long>,
    val distanceMeters: MovementMetric<Double>,
    val exerciseMinutes: MovementMetric<Double>,
    val hourlySteps: MovementMetric<List<StepsWindow>>,
    val sessions: MovementMetric<List<ExerciseWindow>>,
)

/** 仅由前台日记界面主动读取；不请求权限、不写健康数据、不联网或安排后台读取。 */
class HealthMovementReader(private val context: Context) {
    fun access(): MovementAccess {
        if (Build.VERSION.SDK_INT < 34) return MovementAccess(MovementAvailability.Unsupported, emptySet(), PERMISSIONS)
        val available = runCatching { context.getSystemService(HealthConnectManager::class.java) != null }.getOrDefault(false)
        val granted = PERMISSIONS.filterTo(mutableSetOf()) {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        return MovementAccess(if (available) MovementAvailability.Available else MovementAvailability.Unavailable,
            granted, PERMISSIONS - granted)
    }

    /** 用户主动打开公开的健康设置入口；首次授权由系统运行时权限流程处理。 */
    fun permissionIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 34) return null
        // 与 AndroidX HealthConnectClient 的 Android 14+ 公开设置入口一致。
        val intent = Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    suspend fun readDay(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DailyMovementSnapshot {
        val access = access()
        if (access.availability != MovementAvailability.Available || Build.VERSION.SDK_INT < 34) {
            fun <T> missing() = MovementMetric<T>(MovementStatus.Unavailable,
                message = if (access.availability == MovementAvailability.Unsupported) "此功能需要 Android 14 或更新系统" else "系统健康数据服务不可用")
            return DailyMovementSnapshot(date, zone.id, access, missing(), missing(), missing(), missing(), missing())
        }
        val process = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        if (process.importance !in setOf(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)) {
            // 后台只可能读到本应用写入的数据，不能把空结果当成用户当天没有运动。
            fun <T> hidden() = MovementMetric<T>(MovementStatus.Failed, message = "请打开日记页面后读取运动数据")
            return DailyMovementSnapshot(date, zone.id, access, hidden(), hidden(), hidden(), hidden(), hidden())
        }
        return FrameworkReader(context).readDay(date, zone, access)
    }

    companion object {
        const val READ_STEPS = "android.permission.health.READ_STEPS"
        const val READ_DISTANCE = "android.permission.health.READ_DISTANCE"
        const val READ_EXERCISE = "android.permission.health.READ_EXERCISE"
        val PERMISSIONS = setOf(READ_STEPS, READ_DISTANCE, READ_EXERCISE)
    }
}

internal fun movementDayBounds(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
    date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

/** 空结果代表没有可用记录；非空的 0 才代表数据源确实提供了零。 */
internal fun <T> movementValue(value: T?, sources: List<String> = emptyList()): MovementMetric<T> =
    MovementMetric(if (value == null) MovementStatus.NoData else MovementStatus.Available,
        value, sources, if (value == null) "健康数据源尚未提供这一天的记录" else null)

@RequiresApi(34)
private class FrameworkReader(private val context: Context) {
    private val manager = requireNotNull(context.getSystemService(HealthConnectManager::class.java))
    private val executor get() = context.mainExecutor

    suspend fun readDay(date: LocalDate, zone: ZoneId, access: MovementAccess): DailyMovementSnapshot {
        val (start, end) = movementDayBounds(date, zone)
        val range = TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build()
        val steps = permitted(HealthMovementReader.READ_STEPS) { total(StepsRecord.STEPS_COUNT_TOTAL, range) }
        val distance = permitted(HealthMovementReader.READ_DISTANCE) {
            val value = total(DistanceRecord.DISTANCE_TOTAL, range)
            MovementMetric(value.status, value.value?.inMeters, value.sourcePackages, value.message)
        }
        val duration = permitted(HealthMovementReader.READ_EXERCISE) {
            val value = total(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL, range)
            MovementMetric(value.status, value.value?.div(60_000.0), value.sourcePackages, value.message)
        }
        val hourly = permitted(HealthMovementReader.READ_STEPS) {
            val request = AggregateRecordsRequest.Builder<Long>(range)
                .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL).build()
            val buckets = call<List<android.health.connect.AggregateRecordsGroupedByDurationResponse<Long>>> { callback ->
                manager.aggregateGroupByDuration(request, Duration.ofHours(1), executor, callback)
            }
            val windows = buckets.map { StepsWindow(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(),
                it.get(StepsRecord.STEPS_COUNT_TOTAL)) }
            val sources = if (Build.VERSION.SDK_INT >= 35)
                buckets.flatMap { it.getDataOrigins(StepsRecord.STEPS_COUNT_TOTAL).map { origin -> origin.packageName } }.distinct()
                else steps.sourcePackages
            movementValue(windows.takeIf { list -> list.any { it.count != null } }, sources)
        }
        val sessions = permitted(HealthMovementReader.READ_EXERCISE) {
            val records = mutableListOf<ExerciseSessionRecord>()
            var token = -1L
            var pages = 0
            do {
                val builder = ReadRecordsRequestUsingFilters.Builder(ExerciseSessionRecord::class.java)
                    .setTimeRangeFilter(range).setPageSize(1000).setAscending(true)
                if (token != -1L) builder.setPageToken(token)
                val result = call<ReadRecordsResponse<ExerciseSessionRecord>> { callback ->
                    manager.readRecords(builder.build(), executor, callback)
                }
                records += result.records
                token = result.nextPageToken
                check(++pages <= 10) { "Too many exercise pages for a single day" }
            } while (token != -1L)
            val windows = records.mapNotNull { record ->
                val from = maxOf(start, record.startTime)
                val until = minOf(end, record.endTime)
                if (until <= from) null else ExerciseWindow(from.toEpochMilli(), until.toEpochMilli(),
                    record.exerciseType, exerciseLabel(record.exerciseType), record.metadata.dataOrigin.packageName)
            }
            movementValue(windows.takeIf { it.isNotEmpty() }, windows.map { it.sourcePackage }.distinct())
        }
        return DailyMovementSnapshot(date, zone.id, access, steps, distance, duration, hourly, sessions)
    }

    private suspend fun <T> total(type: AggregationType<T>, range: TimeInstantRangeFilter): MovementMetric<T> {
        val request = AggregateRecordsRequest.Builder<T>(range).addAggregationType(type).build()
        val result = call<AggregateRecordsResponse<T>> { callback -> manager.aggregate(request, executor, callback) }
        return movementValue(result.get(type), result.getDataOrigins(type).map { it.packageName })
    }

    private suspend fun <T> permitted(permission: String, read: suspend () -> MovementMetric<T>): MovementMetric<T> {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            return MovementMetric(MovementStatus.NotAuthorized, message = "尚未授权读取这一项运动数据")
        return try { read() }
        catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            if (failure is SecurityException || failure is HealthConnectException && failure.errorCode == HealthConnectException.ERROR_SECURITY)
                MovementMetric(MovementStatus.NotAuthorized, message = "健康权限已撤回或当前不允许读取，请回到前台重新授权")
            else MovementMetric(MovementStatus.Failed, message = "健康数据暂时读取失败，请稍后重试")
        }
    }

    private suspend fun <T> call(request: (OutcomeReceiver<T, HealthConnectException>) -> Unit): T =
        withTimeout(15_000L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    request(object : OutcomeReceiver<T, HealthConnectException> {
                        override fun onResult(result: T) { if (continuation.isActive) continuation.resume(result) }
                        override fun onError(error: HealthConnectException) { if (continuation.isActive) continuation.resumeWithException(error) }
                    })
                } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
            }
        }

    private fun exerciseLabel(type: Int) = when (type) {
        ExerciseSessionType.EXERCISE_SESSION_TYPE_WALKING -> "步行"
        ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING, ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING_TREADMILL -> "跑步"
        ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING, ExerciseSessionType.EXERCISE_SESSION_TYPE_BIKING_STATIONARY -> "骑行"
        ExerciseSessionType.EXERCISE_SESSION_TYPE_HIKING -> "徒步"
        ExerciseSessionType.EXERCISE_SESSION_TYPE_SWIMMING_POOL, ExerciseSessionType.EXERCISE_SESSION_TYPE_SWIMMING_OPEN_WATER -> "游泳"
        ExerciseSessionType.EXERCISE_SESSION_TYPE_BADMINTON -> "羽毛球"
        ExerciseSessionType.EXERCISE_SESSION_TYPE_BASKETBALL -> "篮球"
        ExerciseSessionType.EXERCISE_SESSION_TYPE_STRENGTH_TRAINING, ExerciseSessionType.EXERCISE_SESSION_TYPE_WEIGHTLIFTING -> "力量训练"
        ExerciseSessionType.EXERCISE_SESSION_TYPE_YOGA -> "瑜伽"
        else -> "运动（类型 $type）"
    }
}
