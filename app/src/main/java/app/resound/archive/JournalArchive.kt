package app.resound.archive

import android.content.Context
import app.resound.AppGraph
import app.resound.diary.DeviceStatistics
import app.resound.digest.ItemOrigin
import app.resound.digest.MemoryRepository
import app.resound.health.HealthMovementReader
import app.resound.health.MovementMetric
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** 只在用户显式导出选中日期时取快照；不上传统计，也不把统计自动交给文本模型。 */
internal suspend fun journalArchiveJson(context: Context, memory: MemoryRepository, date: LocalDate, deviceId: String): JSONObject {
    val events = memory.events(date).first()
    val notes = memory.dayItems(date).first().notes.filter { it.category !in ARCHIVE_TASK_CATEGORIES }
    val review = if (AppGraph.isReviewReady) AppGraph.review.daily(date).first() else null
    val markdown = buildString {
        appendLine("# 当天日记 · $date")
        appendLine("事件和笔记保留其整理来源；用户补充与模型整理不等于已验证的人物事实。")
        appendLine()
        appendLine("## 当天事件")
        events.sortedBy { it.startAt }.forEach { event ->
            appendLine("### ${event.title}")
            appendLine("来源：$deviceId:event:${event.id}；${if (event.editedAt != null) "用户编辑" else "AI整理"}")
            appendLine(event.summary)
            if (event.people.isNotEmpty()) appendLine("提及人物：${event.people.joinToString("、")}")
            appendLine()
        }
        appendLine("## 非待办笔记")
        notes.forEach { note ->
            appendLine("- ${note.category}：${note.content}")
            appendLine("  来源：$deviceId:item:${note.id}；${if (note.origin == ItemOrigin.Digest) "AI整理" else "用户记录"}")
        }
        appendLine()
        appendLine("## 摘要")
        appendLine(review?.brief?.takeIf { it.isNotBlank() } ?: "尚未生成摘要。")
        if (review != null) appendLine("AI回顾生成时间：${review.generatedAt}（Unix毫秒）")
        appendLine("结构化待办由逐条归档选择决定；日记叙述和原话仍可能提及同一事项。")
    }
    val device = DeviceStatistics(context).read(date)
    val movement = HealthMovementReader(context).readDay(date)
    fun <T> metric(value: MovementMetric<T>, encode: (T) -> Any): JSONObject = JSONObject()
        .put("state", value.status.name).put("value", value.value?.let(encode) ?: JSONObject.NULL)
        .put("sourcePackages", JSONArray(value.sourcePackages)).put("message", value.message ?: JSONObject.NULL)
    val usage = JSONObject().put("state", device.usage.state.name).put("message", device.usage.message)
        .put("apps", JSONArray(device.usage.apps.map { JSONObject().put("packageName", it.packageName)
            .put("label", it.label).put("foregroundMillis", it.foregroundMillis) }))
        .put("timeline", JSONArray(device.usage.timeline.map { JSONObject().put("packageName", it.packageName)
            .put("label", it.label).put("startAt", it.startAt).put("endAt", it.endAt) }))
    val notifications = JSONObject().put("state", device.notifications.state.name).put("message", device.notifications.message)
        .put("observedFromMillis", device.notifications.observedFromMillis ?: JSONObject.NULL)
        .put("observedUntilMillis", device.notifications.observedUntilMillis ?: JSONObject.NULL)
        .put("coveredMillis", device.notifications.coveredMillis).put("total", device.notifications.total ?: JSONObject.NULL)
        .put("apps", JSONArray(device.notifications.apps.map { JSONObject().put("packageName", it.packageName)
            .put("label", it.label).put("count", it.count) }))
        .put("hourly", JSONArray(device.notifications.hourly.map { JSONObject().put("hourStartMillis", it.hourStartMillis).put("count", it.count) }))
    val health = JSONObject().put("availability", movement.access.availability.name)
        .put("steps", metric(movement.steps) { it })
        .put("distanceMeters", metric(movement.distanceMeters) { it })
        .put("exerciseMinutes", metric(movement.exerciseMinutes) { it })
        .put("hourlySteps", metric(movement.hourlySteps) { rows -> JSONArray(rows.map { JSONObject()
            .put("startAt", it.startAt).put("endAt", it.endAt).put("count", it.count ?: JSONObject.NULL) }) })
        .put("sessions", metric(movement.sessions) { rows -> JSONArray(rows.map { JSONObject()
            .put("startAt", it.startAt).put("endAt", it.endAt).put("typeCode", it.typeCode)
            .put("typeLabel", it.typeLabel).put("sourcePackage", it.sourcePackage) }) })
    return JSONObject().put("markdown", markdown).put("statistics", JSONObject()
        .put("usage", usage).put("notifications", notifications).put("health", health))
}
