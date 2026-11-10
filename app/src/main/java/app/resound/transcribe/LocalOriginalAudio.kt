package app.resound.transcribe

import android.content.SharedPreferences
import app.resound.data.SegmentEntity
import app.resound.data.TranscribeDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 清理与声纹取快照共用锁；保留原话和事件，过期任务明确收尾。 */
class LocalOriginalAudio(
    private val dao: TranscribeDao,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val guard: suspend (suspend (Set<Long>) -> Unit) -> Unit = { it(emptySet()) },
    private val playback: PlaybackCaptureHold,
    private val now: () -> Long = System::currentTimeMillis,
) : OriginalAudio {
    override val retentionDays = MutableStateFlow(prefs.getInt("originalRetentionDays", DEFAULT_RETENTION_DAYS).takeIf { it in RETENTION_DAY_RANGE } ?: DEFAULT_RETENTION_DAYS)
    override val keepStarred = MutableStateFlow(prefs.getBoolean("originalKeepStarred", true))
    private val savedLock = Any()
    private fun readSaved() = prefs.getStringSet("savedOriginalSegments", emptySet()).orEmpty()
        .mapNotNull(String::toLongOrNull).filter { it > 0 }.toSet()
    private fun readSavedDays() = prefs.getStringSet("savedOriginalDays", emptySet()).orEmpty()
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
    override val savedDays = MutableStateFlow(readSavedDays())
    override fun saveDay(date: LocalDate) = synchronized(savedLock) {
        val updated = readSavedDays() + date
        check(prefs.edit().putStringSet("savedOriginalDays", updated.map(LocalDate::toString).toSet()).commit())
        savedDays.value = updated
    }
    private fun manuallySaved(s: SegmentEntity): Boolean = s.id in readSaved() ||
        Instant.ofEpochMilli(s.startAt).atZone(ZoneId.systemDefault()).toLocalDate() in readSavedDays()
    override val savedSegments = MutableStateFlow(readSaved())
    override fun setSaved(segmentId: Long, saved: Boolean) = synchronized(savedLock) {
        require(segmentId > 0)
        val updated = readSaved().toMutableSet().apply { if (saved) add(segmentId) else remove(segmentId) }.toSet()
        check(prefs.edit().putStringSet("savedOriginalSegments", updated.map(Long::toString).toSet()).commit())
        savedSegments.value = updated
    }
    override fun setRetentionDays(days: Int) {
        require(days in RETENTION_DAY_RANGE)
        val shorter = days < retentionDays.value
        check(prefs.edit().putInt("originalRetentionDays", days).commit())
        retentionDays.value = days
        if (shorter) scope.launch { cleanup() }
    }
    override fun setKeepStarred(keep: Boolean) {
        check(prefs.edit().putBoolean("originalKeepStarred", keep).commit())
        keepStarred.value = keep
        if (!keep) scope.launch { cleanup() }
    }
    private suspend fun existing() = dao.allAudio().filter { File(it.path).isFile }
    private fun pending(s: SegmentEntity) = when (s.status) {
        "Failed" -> s.nextRetryAt != null && s.attempts < 5
        "Done" -> s.completedAt == null || s.voiceprintProcessedAt == null
        "Empty", "SkippedPlayback", "SkippedEnv", "Abandoned", "Quiet" -> s.completedAt == null
        else -> true
    }
    private fun days(rows: List<SegmentEntity>): Int {
        val zone = ZoneId.systemDefault()
        return rows.flatMap { s ->
            val start = Instant.ofEpochMilli(s.startAt).atZone(zone).toLocalDate().toEpochDay()
            val end = Instant.ofEpochMilli(maxOf(s.startAt, s.endAt - 1)).atZone(zone).toLocalDate().toEpochDay()
            (start..end).toList()
        }.distinct().size
    }
    override fun usage(): Flow<AudioUsage> = flow {
        while (currentCoroutineContext().isActive) {
            val rows = existing(); val stars = dao.starredAudio().toSet() + readSaved()
            emit(AudioUsage(rows.sumOf { File(it.path).length() }, rows.filter { it.id in stars || manuallySaved(it) }.sumOf { File(it.path).length() },
                days(rows), rows.count(::pending)))
            delay(5_000)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private suspend fun trim(retention: Int?, includeStarred: Boolean, delete: Boolean, range: LongRange? = null): AudioTrim {
        var result = AudioTrim(0, 0, 0, 0)
        val action: suspend (Set<Long>) -> Unit = { reading ->
            val timestamp = now(); dao.refreshFailures(timestamp)
            val rows = existing(); val stars = dao.starredAudio().toSet()
            fun eligible(s: SegmentEntity, saved: Set<Long>) =
                (retention != null || !pending(s)) && s.status != "Running" && s.id !in reading &&
                    (includeStarred || s.id !in saved) &&
                    ((retention == null && includeStarred) || !manuallySaved(s)) &&
                    (range == null || s.startAt in range) &&
                    (retention == null || timestamp - retentionStartedAt(s, File(s.path)) >= retention * 86_400_000L)
            val candidates = rows.filter { eligible(it, stars) }
            var bytes = 0L
            val removed = candidates.filter { snapshot ->
                if (!delete) {
                    bytes += File(snapshot.path).length()
                    true
                } else dao.removeOriginalIfIdle(snapshot.id) { current ->
                    val currentStars = dao.starredAudio().toSet()
                    val file = File(current.path); val size = file.length()
                    // 保存按钮和实际删除共用锁，提交保存后自动清理不能再删掉这一段。
                    val removed = synchronized(savedLock) { eligible(current, currentStars) && file.delete() }
                    if (removed) {
                        bytes += size
                        if (retention != null && pending(current)) {
                            val message = "原音已超过 $retention 天并清理，未完成的转写或声纹处理已停止"
                            dao.update(current.copy(status = if (current.status == "Done") "Done" else "Abandoned",
                                completedAt = current.completedAt ?: timestamp, voiceprintProcessedAt = timestamp,
                                attempts = 5, nextRetryAt = null,
                                error = listOfNotNull(current.error, message).joinToString("；")))
                        }
                    }
                    removed
                }
            }
            val selected = removed.mapTo(mutableSetOf()) { it.id }
            result = AudioTrim(bytes, days(removed), removed.size,
                rows.count { it.id !in selected && (pending(it) || it.id in reading) })
        }
        guard(action)
        return result
    }
    internal suspend fun cleanup(): AudioTrim = trim(retentionDays.value, !keepStarred.value, true)
    override suspend fun previewTrim(days: Int): AudioTrim {
        require(days in RETENTION_DAY_RANGE)
        return trim(days, !keepStarred.value, false)
    }
    override suspend fun previewClear(includeStarred: Boolean) = trim(null, includeStarred, false)
    override suspend fun clear(includeStarred: Boolean) = trim(null, includeStarred, true)
    override suspend fun previewClearRange(fromMillis: Long, untilMillis: Long, includeStarred: Boolean) = trim(null, includeStarred, false, fromMillis..untilMillis)
    override suspend fun clearRange(fromMillis: Long, untilMillis: Long, includeStarred: Boolean) = trim(null, includeStarred, true, fromMillis..untilMillis)
    override suspend fun locate(segmentId: Long): SegmentAudio? = dao.findSegment(segmentId)?.let {
        val file = File(it.path)
        if (file.isFile) SegmentAudio(it.id, file, it.startAt, it.endAt) else null
    }
    override suspend fun available(segmentIds: Set<Long>): Set<Long> = dao.allAudio().filter { it.id in segmentIds && File(it.path).isFile }.mapTo(mutableSetOf()) { it.id }
    override fun beginPlayback() = playback.begin()
    override fun endPlayback() = playback.end()
}

/** 外部录音可能录于多年以前，从导入生成片段时计时；手机采集从录音结束时计时。 */
internal fun retentionStartedAt(segment: SegmentEntity, file: File): Long =
    if (segment.sourceId == "import") file.lastModified() else segment.endAt

/** 只在进程内持有，进程重建不会遗留暂停。重复 begin 不延长最长让麦时间。 */
class PlaybackCaptureHold(private val scope: CoroutineScope, private val hold: (Boolean) -> Unit,
    private val timeoutMillis: Long = 30 * 60_000L) {
    private var active = false
    private var generation = 0L
    private var timeout: Job? = null
    @Synchronized fun begin() {
        if (active) return
        active = true
        val token = ++generation
        hold(true)
        timeout = scope.launch {
            try { delay(timeoutMillis) } finally { release(token) }
        }
    }
    @Synchronized private fun release(token: Long) { if (token == generation) end() }
    @Synchronized fun end() {
        if (!active) return
        active = false
        generation++
        timeout?.cancel(); timeout = null
        hold(false)
    }
}
