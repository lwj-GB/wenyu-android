package app.resound.archive

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.resound.digest.ItemOrigin
import app.resound.digest.ItemStatus
import app.resound.digest.MemoryItem
import app.resound.digest.MemoryRepository
import app.resound.transcribe.TranscriptLine
import app.resound.transcribe.TranscriptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 电脑归档的选择独立于待办状态；接受、完成或标重点都不会自动勾选。 */
class DesktopArchiveSelection(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("desktop-archive", Context.MODE_PRIVATE)

    val deviceId: String
        get() = synchronized(DesktopArchiveSelection::class.java) {
            prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString("deviceId", it).commit()) { "无法保存设备标识" }
            }
        }

    fun selectedIds(): Set<Long> = prefs.getStringSet("todoIds", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()
    fun isSelected(id: Long) = id in selectedIds()

    fun setSelected(id: Long, selected: Boolean) = synchronized(DesktopArchiveSelection::class.java) {
        val ids = selectedIds().toMutableSet()
        if (selected) ids += id else ids -= id
        check(prefs.edit().putStringSet("todoIds", ids.map(Long::toString).toSet()).commit()) { "无法保存归档选择" }
    }
}

/** 一次归档操作固定同一份内容；失败重试不刷新生成时间或更换资料范围。 */
internal class DesktopArchiveSnapshot(content: JSONObject) {
    val json: String = content.toString(2)
    val date: String = content.getString("date")
    val lineCount: Int = content.getJSONArray("lines").length()
    val todoCount: Int = content.getJSONArray("todos").length()
    val hasJournal: Boolean = content.has("journal")
    val sha256: String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(json.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** 仅用户显式请求时收集快照；发送与分享都复用这份资料，不传播手机删除操作。 */
internal suspend fun prepareDesktopArchive(context: Context, memory: MemoryRepository, transcripts: TranscriptRepository, date: LocalDate, includeJournal: Boolean = false): DesktopArchiveSnapshot =
    withContext(Dispatchers.IO) {
        val selection = DesktopArchiveSelection(context)
        val selectedIds = selection.selectedIds()
        val chosen = memory.items(ARCHIVE_TASK_CATEGORIES, null).first().filter {
            it.id in selectedIds && it.status != ItemStatus.Ignored
        }
        val lines = transcripts.day(date).first().flatMap { it.lines }.toMutableList()
        val present = lines.mapTo(mutableSetOf()) { it.id }
        // 跨天待办仍带上确切来源句，不能写出无法回链的来源 ID。
        for (item in chosen) {
            val id = item.sourceLineId ?: continue
            if (id !in present) memory.sourceLines(id).firstOrNull { it.id == id }?.let {
                lines += it; present += id
            }
        }
        val content = desktopArchiveJson(selection.deviceId, date, ZoneId.systemDefault().id,
            System.currentTimeMillis(), lines, chosen, selectedIds,
            app.resound.transcribe.ConversationContextStore(context).forSegments(lines.map { it.segmentId }))
        if (includeJournal) content.put("journal", journalArchiveJson(context, memory, date, selection.deviceId))
        check(includeJournal || content.getJSONArray("lines").length() > 0 || content.getJSONArray("todos").length() > 0) { "当天没有原话，也没有勾选的待办" }
        DesktopArchiveSnapshot(content)
    }

/** 系统分享的文件与直接发送正文逐字节一致，不再重新读取当天资料。 */
internal suspend fun shareDesktopArchive(context: Context, snapshot: DesktopArchiveSnapshot): Uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "desktop-archive").apply { check(isDirectory || mkdirs()) }
        // 独立文件避免分享面板尚未读取时被下一次导出覆盖。
        val file = File(directory, "lingxi-mobile-${snapshot.date}-${UUID.randomUUID()}.json")
        file.writeText(snapshot.json, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

suspend fun exportDesktopArchive(context: Context, memory: MemoryRepository, transcripts: TranscriptRepository, date: LocalDate, includeJournal: Boolean = false): Uri =
    shareDesktopArchive(context, prepareDesktopArchive(context, memory, transcripts, date, includeJournal))

internal val ARCHIVE_TASK_CATEGORIES = setOf("待办", "约定", "备忘")

/** 不附带混合摘要、卡片或整篇日记，以免绕过逐条待办选择。原话保持原文。 */
internal fun desktopArchiveJson(deviceId: String, date: LocalDate, timeZone: String, generatedAt: Long,
    lines: List<TranscriptLine>, items: List<MemoryItem>, selectedIds: Set<Long>,
    contexts: List<app.resound.transcribe.ConversationContext> = emptyList()): JSONObject {
    UUID.fromString(deviceId)
    val source = lines.filter { it.text.isNotBlank() }.distinctBy { it.id }.sortedWith(compareBy({ it.startAt }, { it.id }))
    val sourceIds = source.mapTo(mutableSetOf()) { it.id }
    fun lineId(id: Long) = "$deviceId:line:$id"
    return JSONObject().put("type", "lingxi_mobile_archive").put("version", 1)
        .put("deviceId", deviceId).put("generatedAt", generatedAt).put("date", date.toString()).put("timeZone", timeZone)
        .put("contexts", app.resound.transcribe.conversationContextsJson(contexts.filter { note -> source.any { it.segmentId == note.segmentId } }))
        .put("lines", JSONArray(source.map { line -> JSONObject()
            .put("id", lineId(line.id)).put("localId", line.id).put("segmentId", line.segmentId)
            .put("startAt", line.startAt).put("endAt", line.endAt)
            .put("speaker", line.speakerName?.takeIf(String::isNotBlank) ?: line.speakerTag?.takeIf(String::isNotBlank) ?: "未知")
            .put("text", line.text) }))
        .put("todos", JSONArray(items.filter { it.id in selectedIds && it.category in ARCHIVE_TASK_CATEGORIES && it.status != ItemStatus.Ignored }
            .distinctBy { it.id }.sortedBy { it.id }.map { item -> JSONObject()
                .put("id", "$deviceId:item:${item.id}").put("localId", item.id).put("category", item.category)
                .put("content", item.content).put("status", item.status.name).put("at", item.at)
                .put("dueAt", item.dueAt ?: JSONObject.NULL)
                .put("sourceLineId", item.sourceLineId?.takeIf { it in sourceIds }?.let(::lineId) ?: JSONObject.NULL)
                .put("generatedBy", if (item.origin == ItemOrigin.Digest) "ai" else "unknown") }))
}
