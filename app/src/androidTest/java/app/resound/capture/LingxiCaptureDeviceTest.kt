package app.resound.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.AppGraph
import app.resound.transcribe.AndroidTranscribeSettings
import app.resound.transcribe.TranscribeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 短时真实麦克风控制验收；不授权、不读取录音/原话/Key，不更改转写配置或主动调用转写。 */
@RunWith(AndroidJUnit4::class)
class LingxiCaptureDeviceTest {
    @Test fun startPauseResumeStopUsesRealAudioFrames() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "lingxi-capture-device-test.json")
        val started = SystemClock.elapsedRealtime()
        val phases = JSONArray()
        val microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = CaptureNotifications.permitted(context)
        val report = JSONObject().put("type", "lingxi_capture_device_test").put("version", 1)
            .put("startedAt", System.currentTimeMillis())
            .put("microphonePermission", microphone).put("notificationsPermitted", notifications)
            .put("phases", phases).put("status", "pending")
        fun emit(value: JSONObject) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", value.toString() + "\n")
        })
        fun save() {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(report.toString(2))
            check(temporary.renameTo(file)) { "Could not save capture test report" }
        }
        assertTrue("Target app capture dependencies must be initialized", AppGraph.isReady)
        val capture = AppGraph.capture
        fun snapshot(phase: String): JSONObject {
            val status = capture.status.value
            return JSONObject().put("phase", phase).put("at", System.currentTimeMillis())
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("state", status.state.name).put("serviceAlive", CaptureService.alive)
                .put("lastAudioFrameAt", status.lastAudioFrameAt ?: JSONObject.NULL)
                .put("pausedUntil", status.pausedUntil ?: JSONObject.NULL)
                .put("quietName", status.quietName ?: JSONObject.NULL)
        }
        fun record(phase: String) {
            val value = snapshot(phase)
            phases.put(value); save(); emit(JSONObject().put("capturePhase", value))
        }
        suspend fun awaitStatus(phase: String, timeout: Long, condition: (CaptureStatus) -> Boolean): CaptureStatus {
            val result = withTimeoutOrNull(timeout) { capture.status.first(condition) }
            if (result == null) {
                record("${phase}_timeout")
                fail("$phase timed out after ${timeout}ms; actual=${snapshot(phase)}")
            }
            return requireNotNull(result)
        }
        fun hasFrameAfter(status: CaptureStatus, boundary: Long) =
            status.state in setOf(CaptureState.Listening, CaptureState.Recording) &&
                status.lastAudioFrameAt?.let { it > boundary } == true
        var touchedCapture = false
        var originalFailure: Throwable? = null
        val initial = capture.status.value
        report.put("initial", snapshot("initial"))
        try {
            if (!microphone || !notifications) {
                val reason = "Skipped: microphone and notification permissions must be granted by the user first"
                report.put("status", "skipped").put("reason", reason); save(); emit(report)
                assumeTrue(reason, false)
            }
            val source = if (AppGraph.isTranscribeReady)
                (AppGraph.transcribeSettings as? AndroidTranscribeSettings)?.source?.value else null
            report.put("configuredTranscribeSource", source?.name ?: "not_ready")
            if (source != TranscribeSource.Local) {
                val reason = "Skipped: keep existing settings unchanged; microphone test requires an already selected local transcriber"
                report.put("status", "skipped").put("reason", reason); save(); emit(report)
                assumeTrue(reason, false)
            }
            if (initial.state != CaptureState.Stopped || CaptureService.alive) {
                val reason = "Skipped: an existing capture session must not be interrupted; initial=${initial.state}"
                report.put("status", "skipped").put("reason", reason); save(); emit(report)
                assumeTrue(reason, false)
            }
            touchedCapture = true
            val startRequestedAt = System.currentTimeMillis()
            withContext(Dispatchers.Main.immediate) { capture.start() }
            val first = awaitStatus("start_real_audio", 25_000L) { hasFrameAfter(it, startRequestedAt) }
            record("started_real_audio")
            awaitStatus("start_audio_advances", 5_000L) { hasFrameAfter(it, requireNotNull(first.lastAudioFrameAt)) }
            record("start_audio_advanced")

            val pauseRequestedAt = System.currentTimeMillis()
            withContext(Dispatchers.Main.immediate) { capture.pause(20) }
            val paused = awaitStatus("pause_20_minutes", 10_000L) { it.state == CaptureState.Paused }
            val pausedUntil = requireNotNull(paused.pausedUntil) { "Paused status must include its real resume deadline" }
            assertTrue("20-minute pause deadline must not end early", pausedUntil >= pauseRequestedAt + 20 * 60_000L)
            assertTrue("Pause must be 20 minutes rather than an unrelated quiet window",
                pausedUntil <= pauseRequestedAt + 20 * 60_000L + 10_000L)
            assertNull("Paused status must not claim a fresh audio frame", paused.lastAudioFrameAt)
            record("paused_20_minutes")
            delay(750L)
            assertEquals("Capture must remain paused until manual resume", CaptureState.Paused, capture.status.value.state)
            assertNull("No audio frames may be generated by a pause timer", capture.status.value.lastAudioFrameAt)

            val resumeRequestedAt = System.currentTimeMillis()
            withContext(Dispatchers.Main.immediate) { capture.resume() }
            val resumed = awaitStatus("resume_real_audio", 25_000L) { hasFrameAfter(it, resumeRequestedAt) }
            record("resumed_real_audio")
            awaitStatus("resume_audio_advances", 5_000L) { hasFrameAfter(it, requireNotNull(resumed.lastAudioFrameAt)) }
            record("resume_audio_advanced")

            withContext(Dispatchers.Main.immediate) { capture.stop() }
            awaitStatus("stop", 10_000L) { it.state == CaptureState.Stopped }
            record("stopped")
            report.put("status", "passed")
        } catch (failure: Throwable) {
            originalFailure = failure
            if (failure !is AssumptionViolatedException) report.put("status", "failed")
                .put("failure", JSONObject().put("type", failure.javaClass.simpleName)
                    .put("message", failure.message ?: "no detail"))
            throw failure
        } finally {
            var cleanupProblem: AssertionError? = null
            if (touchedCapture) {
                withContext(NonCancellable) {
                    // 仅恢复最初的 Stopped 状态；不读取或删除本次自然生成的音频与用户数据。
                    withContext(Dispatchers.Main.immediate) { capture.stop() }
                    val ended = withTimeoutOrNull(10_000L) {
                        while (CaptureService.alive || capture.status.value.state != CaptureState.Stopped) delay(100L)
                        true
                    } == true
                    val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
                    val restored = ended && !prefs.getBoolean("enabled", false) &&
                        prefs.getLong("pausedUntil", 0) == 0L && prefs.getLong("manualPausedUntil", 0) == 0L
                    report.put("restoredInitialStoppedState", restored)
                        .put("finalEnabled", prefs.getBoolean("enabled", false))
                        .put("finalPausedUntil", prefs.getLong("pausedUntil", 0))
                        .put("finalManualPausedUntil", prefs.getLong("manualPausedUntil", 0))
                    if (!restored) cleanupProblem = AssertionError("Capture test did not restore stopped service and clear pause timers")
                }
            } else report.put("captureUntouched", true)
            report.put("final", snapshot("finally")).put("wallElapsedMs", SystemClock.elapsedRealtime() - started)
            cleanupProblem?.let { report.put("cleanupFailure", it.message).put("status", "failed") }
            save(); emit(JSONObject().put("captureTestFinal", report))
            cleanupProblem?.let { cleanup ->
                val previous = originalFailure
                if (previous != null) previous.addSuppressed(cleanup) else throw cleanup
            }
        }
        Unit
    }
}
