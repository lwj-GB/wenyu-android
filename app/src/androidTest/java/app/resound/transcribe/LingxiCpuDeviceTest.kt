package app.resound.transcribe

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.AppGraph
import app.resound.transcribe.local.LocalAsrConnection
import app.resound.transcribe.local.LocalAsrLanguage
import app.resound.transcribe.local.LocalBackend
import app.resound.transcribe.local.LocalModels
import app.resound.transcribe.local.ModelVerification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** 固定合成语音的 CPU 实测；不启动 Activity/麦克风，不调用云端，不写转写或待办数据库。 */
@RunWith(AndroidJUnit4::class)
class LingxiCpuDeviceTest {
    @Test fun cpuChineseDialogueThreeRuns() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // 真机的后台冻结会连测试线程一起暂停；固定前台条件，单独测模型而非系统后台策略。
        val activity = instrumentation.startActivitySync(Intent(context, app.resound.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val audio = File(context.cacheDir, "lingxi-cpu-dialogue.wav")
        val output = File(context.cacheDir, "lingxi-cpu-benchmark.json")
        val began = SystemClock.elapsedRealtime()
        val runs = JSONArray()
        val report = JSONObject()
            .put("type", "lingxi_cpu_benchmark").put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                .put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"))
            .put("audio", JSONObject().put("name", audio.name).put("durationMs", AUDIO_MILLIS)
                .put("synthetic", true).put("expectedSha256", AUDIO_SHA256))
            .put("referenceSource", "tools/asr-probe/samples/dialogue.json:turns[*].text")
            .put("referenceText", REFERENCE).put("normalizedReference", normalize(REFERENCE))
            .put("backend", "Cpu").put("language", LocalAsrLanguage.Chinese.code)
            .put("batteryAndThermalBefore", environment(context))
            .put("runs", runs).put("completed", false)
        fun saveReport() {
            val temporary = File(output.parentFile, "${output.name}.tmp")
            temporary.writeText(report.toString(2))
            check(temporary.renameTo(output)) { "Cannot persist CPU benchmark report" }
        }
        fun emit(value: JSONObject) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", value.toString() + "\n") })
        }
        try {
            assertTrue("Install the fixed synthetic dialogue.wav in target cache first", audio.isFile)
            val actualAudioHash = withContext(Dispatchers.IO) { LocalModels.hash(audio) }
            report.getJSONObject("audio").put("actualSha256", actualAudioHash)
            assertEquals("Only the checked-in synthetic fixture may be benchmarked", AUDIO_SHA256, actualAudioHash)
            val cpu = LocalModels.spec(LocalBackend.Cpu)
            val verified = withContext(Dispatchers.IO) {
                ModelVerification.ensure(LocalModels.directory(context, cpu), cpu)
            }
            report.put("cpuModel", JSONObject().put("id", cpu.id)
                .put("sha256", cpu.modelHash).put("verified", verified))
            assertTrue("Install and verify the CPU SenseVoice model first", verified)
            val settings = AppGraph.transcribeSettings as AndroidTranscribeSettings
            withContext(Dispatchers.Main.immediate) {
                // 按用户要求保留本地 CPU 中文、30 分钟批次设置；不屏蔽正常 onChanged。
                settings.setSource(TranscribeSource.Local)
                settings.setBackend(LocalBackend.Cpu)
                settings.setLanguage(LocalAsrLanguage.Chinese)
                settings.setRealtime(false)
                settings.setIntervalMinutes(30)
            }
            report.put("settings", JSONObject().put("source", settings.source.value.name)
                .put("backend", settings.backend.value.name).put("language", settings.language.value.code)
                .put("realtime", settings.realtime.value).put("intervalMinutes", settings.intervalMinutes.value)
                .put("speakersEnabled", context.getSharedPreferences("local-asr", Context.MODE_PRIVATE)
                    .getBoolean("speakers-enabled", true))
                .put("speakerModelReady", LocalModels.ready(context, LocalModels.speakers)))
            val connection = withContext(Dispatchers.Main.immediate) { LocalAsrConnection.get(context) }
            saveReport()
            suspend fun measure(index: Int, separateSpeakers: Boolean, timeoutMs: Long): JSONObject {
                val started = SystemClock.elapsedRealtime()
                val timings = SegmentTimings(index.toLong(), TranscribeSource.Local, AUDIO_MILLIS)
                // 请求级别切换用于比较纯 ASR 和分人耗时，不改用户的分人偏好。
                val result = withTimeout(timeoutMs) {
                    withContext(timings) {
                        connection.recognize(AsrAudio(audio, "wav", AUDIO_MILLIS, separateSpeakers = separateSpeakers),
                            LocalBackend.Cpu, LocalAsrLanguage.Chinese) {}
                    }
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                val text = result.sentences.joinToString("") { it.text }
                val recognized = normalize(text)
                val expected = normalize(REFERENCE)
                val edits = editDistance(expected, recognized)
                val metrics = withContext(Dispatchers.Main.immediate) { connection.lastMetrics }
                val run = JSONObject().put("run", index).put("elapsedMs", elapsed)
                    .put("stageTimings", timings.line())
                    .put("separateSpeakers", separateSpeakers)
                    .put("audioDurationMs", AUDIO_MILLIS).put("rtf", elapsed.toDouble() / AUDIO_MILLIS)
                    .put("text", text).put("normalizedText", recognized)
                    .put("referenceCharacters", expected.length).put("characterEdits", edits)
                    .put("cer", edits.toDouble() / expected.length)
                    .put("returnedDurationMs", result.durationMillis)
                    .put("sentences", JSONArray().apply {
                        result.sentences.forEach { sentence -> put(JSONObject()
                            .put("startMs", sentence.startMillis).put("endMs", sentence.endMillis)
                            .put("text", sentence.text).put("speakerTag", sentence.speakerTag ?: JSONObject.NULL)) }
                    })
                    .put("environmentAfter", environment(context))
                if (metrics != null) run.put("metrics", JSONObject().put("pid", metrics.pid)
                    .put("modelLoads", metrics.modelLoads).put("chunks", metrics.chunks)
                    .put("timedChunks", metrics.timedChunks).put("language", metrics.language)
                    .put("detectedLanguages", JSONArray(metrics.detectedLanguages)))
                assertEquals(LocalAsrLanguage.Chinese.code, requireNotNull(metrics).language)
                return run
            }
            repeat(3) { index ->
                val run = measure(index + 1, separateSpeakers = false, timeoutMs = 30_000L)
                runs.put(run)
                saveReport()
                emit(JSONObject().put("cpuBenchmarkRun", run))
                assertTrue("CPU run ${index + 1} must return nonempty text", run.getString("normalizedText").isNotEmpty())
            }
            report.put("asrBaselineCompleted", true)
            val speakerModelVerified = withContext(Dispatchers.IO) {
                ModelVerification.ensure(LocalModels.directory(context, LocalModels.speakers), LocalModels.speakers)
            }
            report.put("speakerModelVerified", speakerModelVerified)
            val speakersStarted = SystemClock.elapsedRealtime()
            try {
                check(speakerModelVerified) { "The speaker segmentation model is not installed and verified" }
                val run = measure(4, separateSpeakers = true, timeoutMs = 120_000L)
                assertTrue("CPU speaker run must return nonempty text", run.getString("normalizedText").isNotEmpty())
                run.put("status", "completed")
                report.put("speakerSegmentation", run)
                emit(JSONObject().put("cpuSpeakerSegmentation", run))
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                val failed = JSONObject().put("status", "failed").put("type", failure.javaClass.simpleName)
                    .put("message", failure.message ?: "no detail")
                    .put("elapsedMs", SystemClock.elapsedRealtime() - speakersStarted)
                    .put("environmentAfter", environment(context))
                report.put("speakerSegmentation", failed)
                emit(JSONObject().put("cpuSpeakerSegmentation", failed))
            }
            saveReport()
            report.put("completed", true)
            emit(JSONObject().put("cpuBenchmarkCompleted", true).put("report", output.name))
        } catch (failure: Throwable) {
            report.put("failure", JSONObject().put("type", failure.javaClass.simpleName)
                .put("message", failure.message ?: "no detail"))
            throw failure
        } finally {
            report.put("wallElapsedMs", SystemClock.elapsedRealtime() - began)
                .put("batteryAndThermalAfter", environment(context))
            saveReport()
            instrumentation.runOnMainSync { activity.finish() }
        }
        Unit
    }

    private fun environment(context: Context): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val thermal = context.getSystemService(PowerManager::class.java).currentThermalStatus
        return JSONObject().put("at", System.currentTimeMillis())
            .put("batteryPercent", if (level >= 0 && scale > 0) level * 100.0 / scale else JSONObject.NULL)
            .put("batteryTemperatureC", if (temperature != Int.MIN_VALUE) temperature / 10.0 else JSONObject.NULL)
            .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: JSONObject.NULL)
            .put("batteryStatus", battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: JSONObject.NULL)
            .put("thermalStatus", thermal)
    }

    /** CER 只忽略标点和空白；不设未经校准的准确率通过阈值。 */
    private fun normalize(text: String) = text.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)

    private fun editDistance(reference: String, hypothesis: String): Int {
        var previous = IntArray(hypothesis.length + 1) { it }
        for (i in reference.indices) {
            val current = IntArray(hypothesis.length + 1)
            current[0] = i + 1
            for (j in hypothesis.indices) current[j + 1] = minOf(
                previous[j + 1] + 1, current[j] + 1,
                previous[j] + if (reference[i] == hypothesis[j]) 0 else 1)
            previous = current
        }
        return previous[hypothesis.length]
    }

    private companion object {
        const val AUDIO_MILLIS = 40_000L
        const val AUDIO_SHA256 = "48e6a85b32030a87e51b09f85fdbdf137fad27e4b59c47077e1d63bfc9e3165f"
        const val REFERENCE = "早上好，我们确认一下今天的工作安排。上午十点和产品经理开会，讨论回响的录音功能。" +
            "好的，我会带上测试手机。我们先检查后台录音，再看语音识别能不能区分不同的说话人。" +
            "下午三点请提醒我整理会议纪要，把客户的建议和下一步任务分别记录下来。" +
            "没问题。测试结束以后，我会把结果发给你，明天一起决定采用哪一种转写方案。"
    }
}
