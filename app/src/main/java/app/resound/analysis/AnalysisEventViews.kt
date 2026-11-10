package app.resound.analysis

import java.time.Instant
import java.time.ZoneId

val analysisEventCategories = listOf("工作", "学习", "朋友", "感情", "家庭", "生活", "健康", "其他")
/** 重要性由用户标记，文字中的情绪或紧急措辞不自动改变排序。 */
val analysisEventImportance = listOf("紧急", "重要·难过", "重要·开心", "重要", "日常")
fun analysisEventCollections(rows: List<AnalysisEventRow>): List<Pair<String,List<AnalysisEventRow>>> =
    analysisEventImportance.map { label -> label to rows.filter { it.event.importance==label }.sortedByDescending { it.timelineAt } }.filter { it.second.isNotEmpty() }

data class AnalysisEventRow(val event: AnalysisEvent, val personIds: List<String>, val collectionId: String, val collectionName: String) {
    val timelineAt: Long get() = event.occurredAt ?: event.at
    val dateIsRecorded: Boolean get() = event.occurredAt == null
    val category: String get() = event.category ?: "未分类"
}
/** 关联人物只取用户声明或原有归属；合集按显式重要程度归集。 */
fun analysisEventRows(state: AnalysisState): List<AnalysisEventRow> =
    (state.events.map { AnalysisEventRow(it,it.personIds,it.importance,it.importance) } +
        state.people.flatMap { p -> p.events.map { AnalysisEventRow(it,(it.personIds+p.id).distinct(),it.importance,it.importance) } })
        .sortedByDescending { it.timelineAt }

data class AnalysisInteractionStats(val recordedDays: Int, val importedSegments: Int, val eventCount: Int, val latestRecordedAt: Long?)
/** 记录日期不是见面日期；原话段去重按显式sourceKey，文本提及不计数。 */
fun analysisInteractionStats(state: AnalysisState, personId: String, zone: ZoneId = ZoneId.systemDefault()): AnalysisInteractionStats {
    val p=state.people.firstOrNull { it.id==personId } ?: return AnalysisInteractionStats(0,0,0,null)
    val events=analysisEventRows(state).filter { personId in it.personIds }.map { it.event }.distinctBy { it.id }
    val timestamps=p.materials.map { it.at }+events.map { it.at }
    return AnalysisInteractionStats(timestamps.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.distinct().size,
        p.materials.mapNotNull { it.sourceKey?.takeIf { key -> key.startsWith("personal-transcript:") || key.startsWith("personal-memory:") || key.startsWith("restored:") && (key.contains(":personal-transcript:") || key.contains(":personal-memory:")) } }.distinct().size,
        events.size,timestamps.maxOrNull())
}
