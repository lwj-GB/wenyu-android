package app.resound.digest

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class LlmConfigurationInputTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private class Keys : ProviderKeys {
        val values = mutableMapOf<String, String>()
        var unreadable = false
        override fun has(id: String) = values.containsKey(id)
        override fun read(id: String): String { check(!unreadable) { "fixture keystore locked" }; return values[id].orEmpty() }
        override fun write(id: String, key: String) { values[id] = key }
        override fun remove(id: String) { values.remove(id) }
    }
    private lateinit var keys: Keys
    @Before fun reset() {
        listOf("digest", "digest-secrets", "llm-providers", "llm-balance").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        keys = Keys()
    }
    private fun providers(client: LlmClient = LlmClient()) = AndroidLlmProviders(context, client, keys)

    @Test fun clipboardEdgesAreNormalizedButKeyBodyIsPreserved() = runBlocking {
        val p = providers(); val preset = p.selected()
        p.save(preset.id, preset.templateId, preset.name, preset.baseUrl, preset.model,
            "\uFEFF\u200B fixture-key-123 \u200B", preset.balanceUrl, preset.balancePath)
        assertEquals("fixture-key-123", p.credentials(preset.id).second)
        val before = keys.values.toMap()
        assertTrue(runCatching { p.setKey("fixture key must fail") }.isFailure)
        assertEquals(before, keys.values)
    }

    @Test fun invalidDraftKeyExplainsThePasteProblemWithoutSendingOrSaving() = runBlocking {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { error("invalid draft cannot send: ${++requests}") }.build()
        val p = providers(LlmClient(http)); val preset = p.selected()
        val error = p.test(preset.id, preset.baseUrl, preset.model, "fixture key").exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("空白"))
        assertEquals(0, requests); assertTrue(keys.values.isEmpty())
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    @Test fun unreadableSavedSecretIsDistinctFromMissingKeyAndDoesNotEraseIt() = runBlocking {
        keys.values["deepseek"] = "fixture-preserved-key"
        val p = providers(); val preset = p.selected(); keys.unreadable = true
        val error = p.test(preset.id, preset.baseUrl, preset.model, null).exceptionOrNull()
        assertTrue(error!!.message.orEmpty().contains("安全区"))
        assertEquals("fixture-preserved-key", keys.values["deepseek"])
    }

    @Test fun connectivityProbeUsesCurrentModelAndSmallOutputLimit() = runBlocking {
        var request: JSONObject? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer); request = JSONObject(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"{\"ok\":true}"}}]}""".toResponseBody()).build()
        }.build()
        val p = providers(LlmClient(http)); val preset = p.selected()
        assertTrue(p.test(preset.id, preset.baseUrl, preset.model, "fixture-key").isSuccess)
        assertEquals("deepseek-flash", request!!.getString("model"))
        assertEquals(128, request!!.getInt("max_tokens"))
        assertEquals("disabled", request!!.getJSONObject("thinking").getString("type"))
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    @Test fun httpErrorsDistinguishKeyQuotaAndModelWithoutEchoingServerBody() = runBlocking {
        for ((status, expected) in listOf(400 to "参数", 401 to "密钥", 402 to "余额", 404 to "接口或模型", 500 to "服务暂时")) {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                    .body("fixture-server-body-never-display".toResponseBody()).build()
            }.build()
            val error = runCatching { LlmClient(http).chatJson(LlmPreset("fixture", "fixture", "https://example.com/v1", "fixture-model"),
                "fixture-key", "test", "test") }.exceptionOrNull()
            assertTrue("status=$status", error is LlmException)
            assertTrue("status=$status", error!!.message.orEmpty().contains(expected))
            assertFalse(error.message.orEmpty().contains("fixture-server-body"))
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }
}
