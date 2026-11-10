package app.resound.transcribe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisAudioUploadTest {
    @Test fun importedAudioUsesMatchingUploadContentType() {
        for ((extension, mime) in listOf("mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "m4a" to "audio/mp4")) {
            val body = customAsrBody(AsrAudio(File("example.$extension"), extension, 1000), "test-model", emptyList())
            val audio = body.parts.single { it.headers?.get("Content-Disposition")?.contains("filename=") == true }
            assertEquals(mime, audio.body.contentType().toString())
        }
    }
}
