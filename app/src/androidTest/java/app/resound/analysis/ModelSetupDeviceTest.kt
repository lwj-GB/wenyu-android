package app.resound.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.AppGraph
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Configuration is supplied only through the target application's private cache. Never log it. */
@RunWith(AndroidJUnit4::class)
class ModelSetupDeviceTest {
    @Test fun configureEncryptedProviderAndCheckSyntheticPrompt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.wenyu.mobile") { "MODEL_SETUP_WRONG_TARGET" }
        val configFile = File(context.cacheDir, "wenyu-model-setup.json")
        val resultFile = File(context.cacheDir, "wenyu-model-setup-result.json")
        var category = "configuration_failed"
        var stage = "configuration"
        try {
            check(configFile.isFile && configFile.length() in 1..8192) { "MODEL_SETUP_CONFIG_MISSING" }
            val raw = try { configFile.readText(Charsets.UTF_8) } finally {
                check(configFile.delete()) { "MODEL_SETUP_CONFIG_REMOVE_FAILED" }
            }
            val config = JSONObject(raw)
            val baseUrl = config.getString("baseUrl").trim()
            val model = config.getString("model").trim()
            val key = config.getString("key")
            require(baseUrl.isNotBlank() && model.isNotBlank() && key.isNotBlank())
            check(AppGraph.isLlmProvidersReady)
            val providers = AppGraph.llmProviders
            val existing = providers.saved.value.firstOrNull { it.name == "闻鱼测试模型" }
            stage = "storage"
            val id = providers.save(existing?.id, null, "闻鱼测试模型", baseUrl, model, key, "", "")
            providers.activate(id)
            stage = "connection"
            val result = withTimeout(210_000) { providers.test(id, baseUrl, model, null) }
            category = if (result.isSuccess) "success" else "connection_failed"
        } catch (_: Exception) {
            category = when (stage) {
                "storage" -> "encrypted_storage_failed"
                "connection" -> "connection_failed"
                else -> "configuration_failed"
            }
        } finally {
            // Cover malformed input and all early exits; never preserve the plaintext temporary file.
            if (configFile.exists() && !configFile.delete()) category = "temporary_file_cleanup_failed"
            resultFile.writeText(JSONObject().put("status", if (category == "success") "passed" else "failed")
                .put("category", category).toString())
        }
        check(category == "success") { "MODEL_SETUP_$category" }
    }
}
