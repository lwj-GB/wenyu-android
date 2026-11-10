package app.resound.archive

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅发送代码内固定虚构资料；电脑地址/临时码从应用私有cache读取，不进入测试输出。 */
@RunWith(AndroidJUnit4::class)
class DesktopArchiveSendDeviceTest {
    @Test fun sendsSyntheticDayAndReceivesIdempotentAcknowledgement() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, app.resound.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val configFile = File(context.cacheDir, "desktop-receiver-probe.json")
        check(configFile.isFile && configFile.length() in 1..4096) { "Provide the private-cache receiver probe configuration first" }
        val config = JSONObject(configFile.readText())
        val address = config.getString("address")
        val code = config.getString("code")
        check(configFile.delete()) { "Could not clear the temporary receiver probe configuration" }
        val device = "fdc61f3b-39e9-4a88-a86b-9961ed82595d"
        val content = JSONObject().put("type", "lingxi_mobile_archive").put("version", 1)
            .put("deviceId", device).put("date", "2026-11-10").put("timeZone", "Asia/Shanghai")
            .put("generatedAt", 1794272400000L).put("contexts", JSONArray())
            .put("lines", JSONArray().put(JSONObject().put("id", "$device:line:1").put("localId", 1).put("segmentId", 1)
                .put("startAt", 1794272400000L).put("endAt", 1794272405000L).put("speaker", "虚构测试人物")
                .put("text", "这是一条虚构的手机归档验收原话，不包含真实录音。")))
            .put("todos", JSONArray().put(JSONObject().put("id", "$device:item:1").put("localId", 1).put("category", "待办")
                .put("content", "核对虚构归档是否显示在电脑手机页").put("status", "Pending").put("at", 1794272400000L)
                .put("dueAt", JSONObject.NULL).put("sourceLineId", "$device:line:1").put("generatedBy", "manual")))
        val snapshot = DesktopArchiveSnapshot(content)
        val sender = DesktopArchiveSender()
        try {
            val first = sender.send(address, code, snapshot)
            val second = sender.send(address, code, snapshot)
            assertEquals(snapshot.sha256, first.id)
            assertEquals(first.id, second.id)
            assertTrue("Sending the exact same bytes again must not create another archive", second.alreadyExists)
            File(context.cacheDir, "desktop-receiver-probe-result.json").writeText(JSONObject()
                .put("status", "passed").put("id", first.id).put("date", snapshot.date)
                .put("lineCount", snapshot.lineCount).put("todoCount", snapshot.todoCount)
                .put("secondAlreadyExists", second.alreadyExists).toString(2))
        } finally { sender.close() }
        Unit
    }
}
