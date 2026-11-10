package app.resound.archive

import android.app.Application
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DesktopArchiveSenderTest {
    private fun snapshot() = DesktopArchiveSnapshot(JSONObject()
        .put("type", "lingxi_mobile_archive").put("version", 1)
        .put("deviceId", "fdc61f3b-39e9-4a88-a86b-9961ed82595d")
        .put("date", "2026-11-10").put("timeZone", "Asia/Shanghai").put("generatedAt", 3000)
        .put("lines", JSONArray().put(JSONObject().put("id", "synthetic:line:1").put("text", "虚构测试原话")))
        .put("todos", JSONArray()))
    private fun receipt(snapshot: DesktopArchiveSnapshot, status: String = "created", id: String = snapshot.sha256) =
        JSONObject().put("status", status).put("id", id).put("date", snapshot.date)
            .put("lineCount", snapshot.lineCount).put("todoCount", snapshot.todoCount).toString()
    private fun address(server: MockWebServer) = "http://127.0.0.1:${server.port}"

    @Test fun onlyLiteralPrivateIpv4OrLoopbackReceiversAreAccepted() {
        for (ip in listOf("127.0.0.1", "10.2.3.4", "172.16.5.2", "172.31.255.254", "192.168.1.2", "169.254.1.2"))
            assertEquals("http://$ip:12345", desktopReceiverAddress(" http://$ip:12345/ "))
        for (input in listOf("https://192.168.1.2:1234", "http://8.8.8.8:1234", "http://172.32.0.1:1234", "http://0.0.0.0:1234",
            "http://example.local:1234", "http://127.1:1234", "http://127.0.0.01:1234", "http://127.0.0.1", "http://127.0.0.1:0",
            "http://code@127.0.0.1:1234", "http://127.0.0.1:1234/archive", "http://127.0.0.1:1234?code=x", "http://127.0.0.1:1234#x")) {
            assertTrue("Must reject $input", runCatching { desktopReceiverAddress(input) }.isFailure)
        }
    }

    @Test fun realHttpRetrySendsTheSameSnapshotAndRequiresMatchingReceipt() = runBlocking {
        val server = MockWebServer(); val sender = DesktopArchiveSender(); val fixed = snapshot()
        try {
            server.start()
            server.enqueue(MockResponse().setBody(receipt(fixed)))
            server.enqueue(MockResponse().setBody(receipt(fixed, "exists")))
            assertFalse(sender.send(address(server), "12345678", fixed).alreadyExists)
            assertTrue(sender.send(address(server), "12345678", fixed).alreadyExists)
            repeat(2) {
                val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("/archive", request.path)
                assertEquals("POST", request.method)
                assertEquals("Bearer 12345678", request.getHeader("Authorization"))
                assertEquals(fixed.json, request.body.readUtf8())
            }
        } finally { sender.close(); server.shutdown() }
    }

    @Test fun unauthorizedAndOversizeResponsesNeverEchoRemoteBodyOrClaimSuccess() = runBlocking {
        val server = MockWebServer(); val sender = DesktopArchiveSender(); val fixed = snapshot()
        try {
            server.start()
            for ((code, expected) in listOf(401 to "配对码", 413 to "太大")) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("private-remote-detail"))
                val error = runCatching { sender.send(address(server), "12345678", fixed) }.exceptionOrNull()
                assertNotNull(error); assertTrue(error!!.message.orEmpty().contains(expected))
                assertFalse(error.message.orEmpty().contains("private-remote-detail"))
            }
        } finally { sender.close(); server.shutdown() }
    }

    @Test fun aReceiptForAnotherSnapshotIsNotTreatedAsArchived() = runBlocking {
        val server = MockWebServer(); val sender = DesktopArchiveSender(); val fixed = snapshot()
        try {
            server.start(); server.enqueue(MockResponse().setBody(receipt(fixed, id = "0".repeat(64))))
            val error = runCatching { sender.send(address(server), "12345678", fixed) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("不一致"))
        } finally { sender.close(); server.shutdown() }
    }

    @Test fun redirectsCannotForwardArchiveOrPairingCodeToAnotherServer() = runBlocking {
        val server = MockWebServer(); val other = MockWebServer(); val sender = DesktopArchiveSender()
        try {
            server.start(); other.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "${address(other)}/archive"))
            assertTrue(runCatching { sender.send(address(server), "12345678", snapshot()) }.isFailure)
            assertEquals(1, server.requestCount); assertEquals(0, other.requestCount)
        } finally { sender.close(); server.shutdown(); other.shutdown() }
    }

    @Test fun cancellingAWaitingSendDoesNotProduceASuccessReceipt() = runBlocking {
        val server = MockWebServer(); val sender = DesktopArchiveSender()
        try {
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val sending = async { sender.send(address(server), "12345678", snapshot()) }
            while (server.requestCount == 0) delay(10)
            sending.cancel()
            assertTrue(runCatching { sending.await() }.exceptionOrNull() is CancellationException)
        } finally { sender.close(); server.shutdown() }
    }

    @Test fun addressPersistsWithoutStoringThePairingCode() {
        val context = RuntimeEnvironment.getApplication()
        DesktopReceiverSettings(context).saveAddress("http://192.168.1.2:12345/")
        assertEquals("http://192.168.1.2:12345", DesktopReceiverSettings(context).address)
        val values = context.getSharedPreferences("desktop-receiver", 0).all
        assertEquals(setOf("address"), values.keys)
    }
}
