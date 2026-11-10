package app.resound.ui.analysisrecording

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AnalysisAudioImportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun successfulImportKeepsOriginalBytesAndCommitsDraft() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        var draft: File? = null
        val result = importAnalysisAudio(temp.root, "wav", { bytes.inputStream() },
            validate = { assertArrayEquals(bytes, it.readBytes()) }, commit = { draft = it })
        assertEquals(result, draft)
        assertArrayEquals(bytes, result.readBytes())
    }

    @Test fun invalidAudioAndFailedDraftSaveRemoveOnlyNewImport() = runBlocking {
        val existing = temp.newFile("saved.m4a").apply { writeText("existing audio") }
        for (failValidation in listOf(true, false)) {
            try {
                importAnalysisAudio(temp.root, "wav", { byteArrayOf(1, 2).inputStream() },
                    validate = { if (failValidation) error("invalid audio") },
                    commit = { error("draft save failed") })
                fail("import should fail")
            } catch (_: IllegalStateException) { }
            assertEquals(listOf(existing), temp.root.listFiles()!!.toList())
            assertEquals("existing audio", existing.readText())
        }
    }

    @Test fun leavingScreenDuringImportDoesNotRetainUnregisteredAudio() = runBlocking {
        val validating = CountDownLatch(1)
        val release = CountDownLatch(1)
        var committed = false
        val job = launch(Dispatchers.Default) {
            importAnalysisAudio(temp.root, "wav", { byteArrayOf(1, 2).inputStream() },
                validate = { validating.countDown(); check(release.await(5, TimeUnit.SECONDS)) },
                commit = { committed = true })
        }
        try {
            assertTrue(validating.await(5, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
        assertFalse(committed)
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }
}
