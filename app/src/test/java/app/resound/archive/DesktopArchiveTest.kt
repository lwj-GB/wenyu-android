package app.resound.archive

import android.app.Application
import android.content.Context
import app.resound.digest.ItemOrigin
import app.resound.digest.ItemStatus
import app.resound.digest.MemoryItem
import app.resound.transcribe.TranscriptLine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DesktopArchiveTest {
    private val device = "fdc61f3b-39e9-4a88-a86b-9961ed82595d"
    private val day = LocalDate.of(2026, 11, 10)
    private fun item(id: Long, text: String, source: Long? = null) = MemoryItem(id, null, "待办", null, text,
        null, null, null, ItemStatus.Pending, source, at = 1000, origin = ItemOrigin.Digest)
    private fun line(id: Long) = TranscriptLine(id, 1000, 2000, "虚构测试原话", "speaker_1", segmentId = 8)

    @Test fun `archive is opt in and persists independently of item status`() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("desktop-archive", Context.MODE_PRIVATE).edit().clear().commit()
        val first = DesktopArchiveSelection(context)
        assertFalse(first.isSelected(42))
        first.setSelected(42, true)
        val restored = DesktopArchiveSelection(context)
        assertEquals(first.deviceId, restored.deviceId)
        assertTrue(restored.isSelected(42))
        restored.setSelected(42, false)
        assertFalse(first.isSelected(42))
    }

    @Test fun `only selected structured todos are exported with stable provenance`() {
        val json = desktopArchiveJson(device, day, "Asia/Shanghai", 3000, listOf(line(5)),
            listOf(item(1, "勾选的测试任务", 5), item(2, "未选任务不应进入结构化归档", 5)), setOf(1))
        assertEquals(1, json.getJSONArray("todos").length())
        val task = json.getJSONArray("todos").getJSONObject(0)
        assertEquals("$device:item:1", task.getString("id"))
        assertEquals("$device:line:5", task.getString("sourceLineId"))
        assertFalse(json.toString().contains("未选任务不应进入结构化归档"))
        assertFalse(json.has("review"))
        assertFalse(json.has("markdown"))
        assertEquals("ai", task.getString("generatedBy"))
    }

    @Test fun `deleted source is null and ignored or non task selections do not escape`() {
        val tasks = listOf(item(1, "来源已删除", 99), item(2, "已忽略").copy(status = ItemStatus.Ignored),
            item(3, "不是待办").copy(category = "灵感"))
        val json = desktopArchiveJson(device, day, "Asia/Shanghai", 3000, emptyList(), tasks, setOf(1, 2, 3))
        assertEquals(1, json.getJSONArray("todos").length())
        assertTrue(json.getJSONArray("todos").getJSONObject(0).isNull("sourceLineId"))
    }

    @Test fun `archive includes scoped user context without promoting it to speaker identity`() {
        val json = desktopArchiveJson(device, day, "Asia/Shanghai", 5000, listOf(line(5)), emptyList(), emptySet(), listOf(
            app.resound.transcribe.ConversationContext(8, "我与虚构的小林", "用户补充背景", 4000),
            app.resound.transcribe.ConversationContext(99, "其他录音", "不应导出", 4000),
        ))
        assertEquals(1, json.getJSONArray("contexts").length())
        val note = json.getJSONArray("contexts").getJSONObject(0)
        assertEquals(8L, note.getLong("segmentId"))
        assertEquals("user", note.getString("source"))
        assertFalse(json.toString().contains("不应导出"))
        assertEquals("speaker_1", json.getJSONArray("lines").getJSONObject(0).getString("speaker"))
    }
}
