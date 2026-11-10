package app.resound.ui.analysisrecording

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Keep an imported file only after its draft reference has been committed. */
internal suspend fun importAnalysisAudio(
    directory: File,
    extension: String,
    openInput: () -> InputStream?,
    validate: (File) -> Unit,
    commit: (File) -> Unit,
): File {
    val candidate = File(directory, "${UUID.randomUUID()}.$extension")
    var committed = false
    try {
        withContext(Dispatchers.IO) {
            openInput()?.use { input ->
                candidate.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 100L * 1024 * 1024) { "音频超过100MiB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取音频")
            require(candidate.length() > 0) { "音频为空" }
            validate(candidate)
        }
        currentCoroutineContext().ensureActive()
        commit(candidate)
        committed = true
        return candidate
    } finally {
        if (!committed) candidate.delete()
    }
}
