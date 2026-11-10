package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 验证真实Runner把同一PCM交给识别及声纹；不以替身推理耗时冒充真机速度。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class LocalPcmReuseTest {
    @Test fun failedSharedDecodeKeepsTheOriginalAndReportsAudioFailureWithoutCallingTheModel() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val source = File(context.cacheDir, "fixture-bad-pcm.ogg").apply { writeText("fictional broken fixture") }
        val shared = SharedPcmFiles(context.cacheDir) { _, _ -> throw java.io.IOException("fictional decode error") }
        try {
            db.capture().segment(SegmentEntity(1, 1000, 4000, "mic", source.path, source.length()))
            val runner = TranscribeRunner(db.transcribe(), object : AsrProvider {
                override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult =
                    error("解码失败不能调用模型")
            }, { "local-model" }, { _, _ -> }, now = { 10_000 }, deferAudioCleanup = true,
                defaultSource = { TranscribeSource.Local }, sharedPcm = shared)
            runner.run()
            assertEquals("Abandoned", db.transcribe().segment(1).status)
            assertEquals("Audio", db.transcribe().recentAttempts().single().errorType)
            assertTrue(source.isFile); assertNull(shared.find(source))
        } finally { shared.clear(); source.delete(); db.close() }
    }

    @Test fun localRecognitionAndVoiceprintShareOneDecodeWhenEnvironmentAnalysisIsOff() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val source = File(context.cacheDir, "fixture-pcm-reuse.ogg").apply { writeText("fictional fixture") }
        val samples = shortArrayOf(1, 2, -3)
        var decodes = 0
        val shared = SharedPcmFiles(context.cacheDir) { _, target ->
            decodes++; target.writeBytes(byteArrayOf(1, 0, 2, 0, -3, -1))
        }
        try {
            db.capture().segment(SegmentEntity(1, 1000, 4000, "mic", source.path, source.length()))
            val provider = object : AsrProvider {
                override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                    // 与LocalAsrService同样：存在共享PCM就读它，否则才需要解码原音。
                    val actual = audio.pcmFile?.let(::readPcm16) ?: samples.also { decodes++ }
                    assertArrayEquals(samples, actual)
                    return AsrResult(listOf(AsrSentence(0, 3000, "虚构测试原话", "0")), 3000, null)
                }
            }
            val runner = TranscribeRunner(db.transcribe(), provider, { "local-model" }, { _, _ -> },
                now = { 10_000 }, deferAudioCleanup = true, defaultSource = { TranscribeSource.Local },
                beforeTranscribe = { false }, sharedPcm = shared,
                afterSegment = { assertArrayEquals(samples, shared.read(source) { decodes++; samples }) })
            runner.run()
            assertEquals(1, decodes)
            assertEquals("Done", db.transcribe().segment(1).status)
            assertEquals(1000L, db.transcribe().linesForRedo(1).single().startAt)
            assertNull(shared.find(source))
            assertTrue(source.isFile)
        } finally { shared.clear(); source.delete(); db.close() }
    }
}
