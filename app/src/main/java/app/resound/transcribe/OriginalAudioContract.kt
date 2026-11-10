package app.resound.transcribe

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 062 原声：保留时长、占用与清除、播放定位、播放时暂停收音。界面（Claude）只依赖这里；底层（Codex）实现。
 * 改签名需双方确认。
 */

/** 原声保留时长常用值（天），默认 [DEFAULT_RETENTION_DAYS]；也可自定义 [RETENTION_DAY_RANGE] 内任意天数 */
val RETENTION_DAY_OPTIONS = listOf(1, 3, 7, 30)
val RETENTION_DAY_RANGE = 1..365
const val DEFAULT_RETENTION_DAYS = 3

/** 一段录音的原声位置；[startAt] 是录音开头的绝对时间，句子偏移 = line.startAt - startAt */
data class SegmentAudio(val segmentId: Long, val file: File, val startAt: Long, val endAt: Long)

data class AudioUsage(
    /** 现存原声总大小 */
    val bytes: Long,
    /** 其中已手动保存或标了重点的原声 */
    val starredBytes: Long,
    /** 现存原声覆盖的天数 */
    val days: Int,
    /** 还在等转写/声纹的录音段；未保存的原音到期仍会自动清理 */
    val pending: Int,
)

/** 清除或缩短保留时长前的预估 / 实际结果 */
data class AudioTrim(val bytes: Long, val days: Int, val segments: Int, val keptPending: Int)

interface OriginalAudio {
    /** 自动清理从录音结束计算；外部导入从生成本机片段计算，未完成任务也不无限延长。 */
    val retentionDays: StateFlow<Int>
    /** 改短时调用前界面先用 [previewTrim] 给出提示；改后立即按新规则清理 */
    fun setRetentionDays(days: Int)

    /** 按录音开始日期保存，包含该日之后新增的录音；不会恢复已清理的文件。 */
    val savedDays: StateFlow<Set<LocalDate>>
    fun saveDay(date: LocalDate)

    /** 按录音段手动保存，不受自动保留天数和重点开关影响。 */
    val savedSegments: StateFlow<Set<Long>>
    fun setSaved(segmentId: Long, saved: Boolean)

    /** 标了重点的事一直保留原声，默认 true */
    val keepStarred: StateFlow<Boolean>
    fun setKeepStarred(keep: Boolean)

    fun usage(): Flow<AudioUsage>

    /** 如果保留时长改成 [days]，会删掉多少（不删除） */
    suspend fun previewTrim(days: Int): AudioTrim
    /** 清除原声：includeStarred=false 时重点和手动保存的保留；还在等转写/声纹的一律不删。原话文字不动 */
    suspend fun previewClear(includeStarred: Boolean): AudioTrim
    suspend fun clear(includeStarred: Boolean): AudioTrim
    /** 只清除录音开始时刻在 [fromMillis, untilMillis] 内的原声，其余规则同 [clear] */
    suspend fun previewClearRange(fromMillis: Long, untilMillis: Long, includeStarred: Boolean): AudioTrim
    suspend fun clearRange(fromMillis: Long, untilMillis: Long, includeStarred: Boolean): AudioTrim

    /** 找到一段录音的原声；文件已删返回 null */
    suspend fun locate(segmentId: Long): SegmentAudio?
    /** 批量查哪些录音段还有原声（界面显示 ▶） */
    suspend fun available(segmentIds: Set<Long>): Set<Long>

    /** 开始播放前调用：暂停日常收音（与录声纹同样做法，不写成用户暂停）；结束、出错、离开页面时调用 [endPlayback] 恢复。重复调用安全 */
    fun beginPlayback()
    fun endPlayback()
}
