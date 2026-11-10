package app.resound.backup

import android.content.Context
import android.util.AtomicFile
import app.resound.analysis.AnalysisStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 全局备份中的分析资料；范围标记区分旧备份未包含与新备份内容为空。 */
internal object AnalysisBackupAssets {
    const val MARKER = "files/analysis-backup.json"
    const val DRAFTS = "files/analysis-recording-drafts.json"
    private const val AUDIO = "files/analysis-audio/"
    private val documents = listOf("files/analysis-mode.json", "files/analysis-ask.json", "files/analysis-skills.json")
    val livePaths = documents + AUDIO.trimEnd('/')
    fun allowed(path: String) = path in setOf(MARKER, DRAFTS) || path in documents ||
        path.startsWith(AUDIO) && path.removePrefix(AUDIO).matches(Regex("[a-fA-F0-9-]{36}[.](m4a|mp3|wav|ogg)"))

    fun capture(context: Context, target: File) = synchronized(AnalysisStore.fileLock) {
        val data = File(context.applicationInfo.dataDir)
        documents.forEach { path ->
            val source = AtomicFile(File(data, path))
            if (source.baseFile.exists() || File(source.baseFile.path + ".bak").exists()) {
                val destination = File(target, path).apply { parentFile!!.mkdirs() }
                source.openRead().use { input -> destination.outputStream().use { input.copyTo(it) } }
            }
        }
        // 仅引用已保存材料的原音；正在录制的草稿不作为完成资料备份。
        val audio = mutableSetOf<String>()
        fun references(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key == "audioPath" && child is String) {
                        val source = File(child)
                        copyAudio(source, context, target, audio)
                    } else references(child)
                }
                is JSONArray -> repeat(value.length()) { references(value.opt(it)) }
            }
        }
        File(target, "files/analysis-mode.json").takeIf { it.isFile }?.let { references(JSONObject(it.readText())) }
        val drafts = JSONObject()
        context.getSharedPreferences("analysis-recording-drafts", 0).all.forEach { (owner, value) ->
            if (value is String) {
                val source = File(value)
                copyAudio(source, context, target, audio)
                drafts.put(owner, source.name)
            }
        }
        File(target, DRAFTS).apply { parentFile!!.mkdirs() }.writeText(drafts.toString())
        File(target, MARKER).apply { parentFile!!.mkdirs() }.writeText("{\"format\":1}")
    }
    private fun copyAudio(source: File, context: Context, target: File, audio: MutableSet<String>) {
        require(source.parentFile?.canonicalFile == File(context.filesDir, "analysis-audio").canonicalFile && allowed(AUDIO + source.name)) { "分析原音路径无效，未完成备份" }
        if (!source.isFile) throw BackupFailure("分析原音已不存在，未完成完整备份")
        val path = AUDIO + source.name
        if (audio.add(path)) source.copyTo(File(target, path).apply { parentFile!!.mkdirs() })
    }

    /** 解包验证后、写入前，把旧设备绝对路径定位到本机资料目录。 */
    fun prepareRestore(context: Context, pending: File) {
        if (!File(pending, MARKER).isFile) return
        val marker = JSONObject(File(pending, MARKER).readText())
        require(marker.getInt("format") == 1)
        fun local(name: String): String {
            require(allowed(AUDIO + name) && File(pending, AUDIO + name).isFile) { "备份缺少分析原音" }
            return File(context.filesDir, "analysis-audio/$name").absolutePath
        }
        fun relocate(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key == "audioPath" && child is String && child.isNotBlank()) {
                        val old = File(child)
                        require(old.parentFile?.name == "analysis-audio") { "备份分析原音路径无效" }
                        value.put(key, local(old.name))
                    } else relocate(child)
                }
                is JSONArray -> repeat(value.length()) { relocate(value.opt(it)) }
            }
        }
        File(pending, "files/analysis-mode.json").takeIf { it.isFile }?.let { file ->
            val value = JSONObject(file.readText()); relocate(value); file.writeText(value.toString())
        }
        val edit = context.getSharedPreferences("analysis-recording-drafts", 0).edit().clear()
        File(pending, DRAFTS).takeIf { it.isFile }?.let { file ->
            val drafts = JSONObject(file.readText())
            drafts.keys().forEach { owner -> edit.putString(owner, local(drafts.getString(owner))) }
        }
        check(edit.commit())
    }
}
