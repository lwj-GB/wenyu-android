package app.resound.analysis

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class AnalysisPerson(val id: String, val name: String, val answers: Map<String,String>, val revision: Long = 1,
    val materials: List<AnalysisMaterial> = emptyList(), val events: List<AnalysisEvent> = emptyList(), val reports: List<AnalysisReport> = emptyList())
data class AnalysisMaterial(val id: String, val title: String, val text: String, val at: Long, val audioPath: String? = null, val sourceKey: String? = null)
data class AnalysisEvent(val id: String, val title: String, val text: String, val at: Long, val audioPath: String? = null, val occurredAt: Long? = null, val category: String? = null, val personIds: List<String> = emptyList(), val importance: String = "日常")
data class AnalysisClaim(val id: String, val text: String, val sources: List<String>, val verdict: String = "")
data class AnalysisReport(val id: String, val kind: String, val markdown: String, val claims: List<AnalysisClaim>, val revision: Long, val at: Long, val skills: List<String> = listOf("心理学视角")) { val style: String get() = skills.joinToString("＋").ifBlank { "基础分析" } }
data class AnalysisAnswer(val text: String, val sources: List<String>)
data class AnalysisState(val people: List<AnalysisPerson> = emptyList(), val autoUpdate: Boolean = false, val skills: List<String> = listOf("心理学视角"), val events: List<AnalysisEvent> = emptyList(), val reportPrompt: String = "", val askPrompt: String = "", val defaultReportKind: String = "性格与沟通") { val style: String get() = skills.joinToString("＋").ifBlank { "基础分析" } }
data class AnalysisBackupPreview(val people: Int, val events: Int, val materials: Int, val reports: Int, val audioReferences: Int)
data class AnalysisQuestion(val id: String, val title: String)
val analysisQuestions = listOf("姓名或昵称", "和你的关系", "职业或目前在做的事", "你们怎样认识", "平时喜欢怎样相处", "喜欢的书、电影或音乐", "喜欢的食物与口味", "兴趣爱好", "不喜欢或想避开的事", "最近关心的事", "重要经历或转折", "擅长的领域与成就", "分歧时通常怎样沟通", "你想改善的相处问题", "还有什么需要了解").mapIndexed { i, t -> AnalysisQuestion("q${i+1}",t) }

/** 与个人采集库完全隔离；提交成功后才发布新状态。 */
class AnalysisStore(context: Context) {
    internal companion object { val fileLock = Any() }
    val intelligence by lazy { AnalysisSkillRepository(context.applicationContext,this) }
    private val backupPrefs=context.getSharedPreferences("analysis-backup-origin",Context.MODE_PRIVATE)
    private fun backupOrigin(): String = backupPrefs.getString("id",null) ?: UUID.randomUUID().toString().also { check(backupPrefs.edit().putString("id",it).commit()) }
    private val file = AtomicFile(File(context.filesDir, "analysis-mode.json"))
    private var styleGeneration = 0L
    val loadError: String?
    private val mutable: MutableStateFlow<AnalysisState>
    init {
        var error: String? = null
        val loaded = synchronized(fileLock) {
            try {
                if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) decode(JSONObject(file.openRead().bufferedReader().use { it.readText() })) else AnalysisState()
            } catch (_: Exception) { error = "分析资料读取失败，请保留原文件后恢复备份"; AnalysisState() }
        }
        loadError = error
        mutable = MutableStateFlow(loaded)
    }
    val state = mutable.asStateFlow()
    @Synchronized private fun save(next: AnalysisState) = synchronized(fileLock) {
        check(loadError == null) { loadError.orEmpty() }
        val stream = file.startWrite()
        try { stream.write(encode(next).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        mutable.value = next
    }
    @Synchronized fun createPerson(answers: Map<String,String>): String {
        require(answers["q1"].orEmpty().isNotBlank()) { "请填写姓名或昵称" }
        val p = AnalysisPerson(UUID.randomUUID().toString(), answers.getValue("q1").trim(), answers.toMap())
        save(state.value.copy(people = state.value.people + p)); return p.id
    }
    fun updatePerson(personId: String, answers: Map<String,String>) = edit(personId) { p ->
        val merged=p.answers+answers
        require(merged["q1"].orEmpty().isNotBlank()) { "请填写姓名或昵称" }
        if(merged==p.answers) p else p.copy(name=merged.getValue("q1").trim(),answers=merged,revision=p.revision+1)
    }
    @Synchronized fun applyProfileSuggestions(personId: String, revision: Long, suggestions: Map<String,String>) {
        val p = person(personId)
        require(p.revision == revision) { "资料已更新，请重新提取" }
        require(suggestions.keys.all { id -> analysisQuestions.any { it.id == id } })
        val blankOnly = suggestions.filter { (id,value) -> p.answers[id].isNullOrBlank() && value.isNotBlank() }
        updatePerson(personId, blankOnly)
    }
    @Synchronized fun setDefaultReportKind(value: String) {
        require(value in analysisReportKinds) { "报告类型无效" }
        save(state.value.copy(defaultReportKind=value))
    }
    @Synchronized private fun edit(id: String, transform: (AnalysisPerson)->AnalysisPerson) {
        require(state.value.people.any { it.id == id }) { "人物不存在" }
        save(state.value.copy(people = state.value.people.map { if(it.id == id) transform(it) else it }))
    }
    fun person(id: String) = state.value.people.first { it.id == id }
    fun addMaterial(personId: String, title: String, text: String, audioPath: String? = null): String {
        require(text.isNotBlank()); val item = AnalysisMaterial(UUID.randomUUID().toString(), title.ifBlank { "补充材料" }, text, System.currentTimeMillis(), audioPath)
        edit(personId) { it.copy(revision = it.revision+1, materials = it.materials+item) }; return item.id
    }
    @Synchronized fun addQuestionAnswer(personId: String, questionId: Long, question: String, text: String, occurredAt: Long? = null): String = synchronized(fileLock) {
        require(text.isNotBlank()) { "请先填写回答" }
        require(occurredAt == null || occurredAt >= 0) { "发生时间无效" }
        check(loadError == null) { loadError.orEmpty() }
        if(file.baseFile.exists()) mutable.value=decode(JSONObject(file.openRead().bufferedReader().use { it.readText() }))
        val key = "analysis-question:$questionId"
        val time = occurredAt?.let { java.time.Instant.ofEpochMilli(it).atZone(app.resound.skills.SKILL_ZONE).toString() } ?: "未知"
        val item = AnalysisMaterial(UUID.randomUUID().toString(), "每日资料补充", "用户补充，尚非独立核实的事实。\n问题：$question\n发生时间：$time\n回答：${text.trim()}", System.currentTimeMillis(), sourceKey = key)
        person(personId).materials.firstOrNull { it.sourceKey == key }?.let {
            require(it.text==item.text) { "此前回答已保存，请先查看人物材料，避免重复写入不同回答" }
            return@synchronized it.id
        }
        edit(personId) { it.copy(revision = it.revision + 1, materials = it.materials + item) }
        item.id
    }
    @Synchronized fun importPersonalTranscript(personId: String, segmentId: Long, startAt: Long, endAt: Long, text: String, sourceKind: String = "personal-transcript"): Boolean {
        require(text.isNotBlank() && segmentId > 0 && endAt >= startAt)
        require(sourceKind in setOf("personal-transcript", "personal-memory"))
        val key="$sourceKind:$segmentId"
        if (person(personId).materials.any { it.sourceKey==key || it.sourceKey=="restored:${backupOrigin()}:$key" }) return false
        val item=AnalysisMaterial(UUID.randomUUID().toString(),"${if(sourceKind=="personal-memory") "个人记忆" else "个人录音"} · $segmentId", "个人模式材料，用户明确归入本人物；整理内容及说话人身份需核实。\n时间：$startAt–$endAt\n$text", startAt, sourceKey=key)
        edit(personId) { it.copy(revision=it.revision+1, materials=it.materials+item) }
        return true
    }
    @Synchronized fun addStandaloneEvent(title: String, text: String, audioPath: String? = null, occurredAt: Long? = null, category: String? = null, personIds: List<String> = emptyList(), importance: String = "日常"): String {
        require(importance in analysisEventImportance); require(text.isNotBlank()); validateEventMetadata(occurredAt, personIds); val e=AnalysisEvent(UUID.randomUUID().toString(),title.ifBlank { "事件" },text,System.currentTimeMillis(),audioPath,occurredAt,category?.trim()?.ifBlank { null },personIds.distinct(),importance)
        save(state.value.copy(events=state.value.events+e,people=state.value.people.map { if(it.id in e.personIds) it.copy(revision=it.revision+1) else it })); return e.id
    }
    @Synchronized fun addEvent(personId: String, title: String, text: String, occurredAt: Long? = null, category: String? = null, personIds: List<String> = emptyList()): String {
        require(text.isNotBlank()); validateEventMetadata(occurredAt, personIds); val e = AnalysisEvent(UUID.randomUUID().toString(), title.ifBlank { "相处记录" }, text, System.currentTimeMillis(), occurredAt=occurredAt, category=category?.trim()?.ifBlank { null }, personIds=(personIds+personId).distinct())
        require(state.value.people.any { it.id==personId }) { "人物不存在" }
        save(state.value.copy(people=state.value.people.map { p -> when { p.id==personId -> p.copy(revision=p.revision+1,events=p.events+e); p.id in e.personIds -> p.copy(revision=p.revision+1); else -> p } })); return e.id
    }
    @Synchronized fun updateEventMetadata(eventId: String, category: String?, importance: String) {
        require(importance in analysisEventImportance) { "重要程度无效" }
        val row=analysisEventRows(state.value).firstOrNull { it.event.id==eventId } ?: error("事件不存在")
        require(category == null || category in analysisEventCategories || category==row.event.category) { "事件分类无效" }
        val next=row.event.copy(category=category,importance=importance)
        if(next==row.event) return
        save(state.value.copy(events=state.value.events.map { if(it.id==eventId) next else it },people=state.value.people.map { p ->
            if(p.id in row.personIds) p.copy(revision=p.revision+1,events=p.events.map { if(it.id==eventId) next else it }) else p
        }))
    }
    private fun validateEventMetadata(occurredAt: Long?, personIds: List<String>) {
        require(occurredAt==null || occurredAt>=0) { "事件时间无效" }
        require(personIds.all { id -> state.value.people.any { it.id==id } }) { "关联人物不存在" }
    }
    fun review(personId: String, reportId: String, claimId: String, verdict: String) {
        require(verdict in listOf("", "准确", "不准确", "不确定", "已忽略"))
        edit(personId) { p -> p.copy(revision=p.revision+1, reports=p.reports.map { r -> if(r.id!=reportId) r else r.copy(claims=r.claims.map { if(it.id==claimId) it.copy(verdict=verdict) else it }) }) }
    }
    @Synchronized fun setSkills(values: Collection<String>) {
        require(values.all { it in analysisStyles }) { "分析技能不存在" }
        val selected=analysisStyles.keys.filter { it in values }
        if (selected != state.value.skills) { save(state.value.copy(skills=selected)); styleGeneration++ }
    }
    @Synchronized fun setBasePrompt(kind: String, text: String) {
        require(kind in listOf("report","ask")) { "提示词类型无效" }
        require(text.length<=4000) { "补充提示词最多4000字" }
        val next=if(kind=="report") state.value.copy(reportPrompt=text) else state.value.copy(askPrompt=text)
        if(next!=state.value) { save(next); styleGeneration++ }
    }
    fun setStyle(value: String) = setSkills(listOf(value))
    @Synchronized fun styleToken(): Pair<List<String>,Long> = state.value.skills.toList() to styleGeneration
    @Synchronized internal fun ensureCurrent(personId: String, revision: Long, token: Pair<List<String>,Long>) {
        check(styleToken() == token) { "分析方向已切换，请重新运行" }
        check(person(personId).revision == revision) { "材料已更新，请重新运行" }
    }
    @Synchronized fun setAutoUpdate(value: Boolean) = save(state.value.copy(autoUpdate=value))
    @Synchronized internal fun append(personId: String, report: AnalysisReport, token: Pair<List<String>,Long>? = null) = edit(personId) {
        if (token != null) ensureCurrent(personId,report.revision,token)
        check(it.revision == report.revision) { "材料已更新，请重新分析" }; it.copy(reports=it.reports+report)
    }
    @Synchronized fun exportBackup(): String {
        check(loadError==null) { loadError.orEmpty() }
        return JSONObject().put("type","lingxi_analysis_text_backup").put("version",1)
            .put("scope","仅分析文字资料及设置快照；不含聊天和音频文件，原音路径已移除；个人录音来源仅保留带原设备标记的历史引用")
            .put("origin",backupOrigin()).put("data",encode(textOnly(state.value,backupOrigin()))).toString(2)
    }
    @Synchronized fun previewBackup(text: String): AnalysisBackupPreview {
        val incoming=parseBackup(text)
        mergedBackup(incoming)
        return AnalysisBackupPreview(incoming.people.size, incoming.events.size+incoming.people.sumOf { it.events.size },
            incoming.people.sumOf { it.materials.size }, incoming.people.sumOf { it.reports.size },
            incoming.people.sumOf { p -> p.materials.count { it.audioPath!=null }+p.events.count { it.audioPath!=null } }+incoming.events.count { it.audioPath!=null })
    }
    @Synchronized fun restoreBackup(text: String, confirmed: Boolean): AnalysisBackupPreview {
        require(confirmed) { "请先预览并确认恢复范围" }
        val preview=previewBackup(text)
        val incoming=parseBackup(text)
        val merged=mergedBackup(incoming)
        if(merged!=state.value) { save(merged); styleGeneration++ }
        return preview
    }
    private fun parseBackup(text: String): AnalysisState {
        require(text.toByteArray(Charsets.UTF_8).size<=20*1024*1024) { "备份超过20MiB" }
        try {
            val envelope=JSONObject(text)
            require(envelope.getString("type")=="lingxi_analysis_text_backup" && envelope.getInt("version")==1) { "备份类型或版本不支持" }
            val origin=envelope.getString("origin").also { UUID.fromString(it) }
            val decoded=textOnly(decode(envelope.getJSONObject("data")),origin)
            validateBackup(decoded)
            return decoded
        } catch(e: IllegalArgumentException) { throw e }
        catch(_:Exception) { throw IllegalArgumentException("备份格式损坏，未修改现有资料") }
    }
    private fun textOnly(s: AnalysisState, origin: String): AnalysisState = s.copy(
        events=s.events.map { it.copy(audioPath=null) },
        people=s.people.map { p -> p.copy(materials=p.materials.map { m -> m.copy(audioPath=null,
            sourceKey=m.sourceKey?.let { if(it.startsWith("personal-transcript:") || it.startsWith("personal-memory:")) "restored:$origin:$it" else it }) },events=p.events.map { it.copy(audioPath=null) }) })
    private fun validateBackup(s: AnalysisState) {
        fun unique(ids: List<String>) { require(ids.all { it.isNotBlank() } && ids.distinct().size==ids.size) { "备份包含空ID或重复ID" } }
        unique(s.people.map { it.id })
        unique((s.events+s.people.flatMap { it.events }).map { it.id })
        unique(s.people.flatMap { it.materials }.map { it.id })
        unique(s.people.flatMap { it.reports }.map { it.id })
        unique(s.people.flatMap { it.reports }.flatMap { it.claims }.map { it.id })
        require(s.reportPrompt.length<=4000 && s.askPrompt.length<=4000) { "备份提示词过长" }
        val knownPeople=(state.value.people+s.people).map { it.id }.toSet()
        (s.events+s.people.flatMap { it.events }).forEach { e ->
            require(e.title.isNotBlank() && e.text.isNotBlank() && e.at>=0 && (e.occurredAt==null || e.occurredAt>=0) && e.personIds.all { it in knownPeople } && e.importance in analysisEventImportance) { "备份事件字段或人物关联无效" }
        }
        s.people.forEach { p ->
            require(p.name.isNotBlank() && p.answers["q1"].orEmpty().isNotBlank() && p.name==p.answers.getValue("q1").trim() && p.revision>0) { "备份人物字段无效" }
            p.materials.forEach { require(it.text.isNotBlank() && it.at>=0) { "备份材料无效" } }
            p.reports.forEach { r ->
                require(r.kind in analysisReportKinds && r.markdown.isNotBlank() && r.revision>0 && r.at>=0) { "备份报告无效" }
                val knownSources=p.answers.keys+p.materials.map { it.id }+(s.events+s.people.flatMap { it.events }).map { it.id }
                r.claims.forEach { require(it.sources.all { ref -> ref in knownSources }) { "备份核对项来源缺失" }; require(it.text.isNotBlank() && it.sources.isNotEmpty() && it.verdict in listOf("","准确","不准确","不确定","已忽略")) { "备份核对项无效" } }
            }
        }
    }
    private fun mergedBackup(incoming: AnalysisState): AnalysisState {
        check(loadError==null) { loadError.orEmpty() }
        val normalized=textOnly(state.value,backupOrigin())
        incoming.people.forEach { fresh -> normalized.people.firstOrNull { it.id==fresh.id }?.let { require(it==fresh) { "人物ID冲突且内容不同：${fresh.id}；未恢复任何资料" } } }
        incoming.events.forEach { fresh -> normalized.events.firstOrNull { it.id==fresh.id }?.let { require(it==fresh) { "事件ID冲突且内容不同：${fresh.id}；未恢复任何资料" } } }
        val merged=state.value.copy(people=state.value.people+incoming.people.filter { p -> state.value.people.none { it.id==p.id } },events=state.value.events+incoming.events.filter { e -> state.value.events.none { it.id==e.id } })
        validateBackup(merged)
        return merged
    }
    fun reportMarkdown(personId: String, reportId: String): String {
        val p=person(personId); val report=p.reports.first { it.id==reportId }
        val rows=analysisEventRows(state.value).filter { personId in it.personIds }
        val sourceIds=(Regex("\\[([^\\]\\n]+)\\]").findAll(report.markdown).map { it.groupValues[1] }.toList()+report.claims.flatMap { it.sources }).distinct()
        val sources=sourceIds.joinToString("\n\n") { id ->
            val material=p.materials.firstOrNull { it.id==id }
            val event=rows.firstOrNull { it.event.id==id }?.event
            val title=analysisQuestions.firstOrNull { it.id==id }?.title ?: material?.title ?: event?.title ?: "来源材料"
            val text=p.answers[id] ?: material?.text ?: event?.text
            "### $title\n\n"+(text ?: "当前资料未找到该来源，无法核对")
        }
        val note=if(report.revision!=p.revision) "\n\n人物资料在报告生成后已有更新。以下为当前来源文字，不保证与生成时版本相同。" else ""
        return "# ${p.name} · ${report.kind}\n\n技能：${report.style}\n\n${readableAnalysisReport(p,report)}\n\n## 来源原文$note\n\n$sources"
    }
    private fun decodeSkills(o: JSONObject): List<String> {
        val selected=o.optJSONArray("skills")?.strings() ?: listOf(o.optString("style","心理学视角"))
        require(selected.all { it in analysisStyles }) { "分析技能配置损坏" }
        return analysisStyles.keys.filter { it in selected }
    }
    private fun encodeEvent(e: AnalysisEvent) = JSONObject().put("id",e.id).put("title",e.title).put("text",e.text).put("at",e.at)
        .put("audioPath",e.audioPath ?: JSONObject.NULL).put("occurredAt",e.occurredAt ?: JSONObject.NULL)
        .put("category",e.category ?: JSONObject.NULL).put("personIds",JSONArray(e.personIds)).put("importance",e.importance)
    private fun decodeEvent(e: JSONObject) = AnalysisEvent(e.getString("id"),e.getString("title"),e.getString("text"),e.getLong("at"),
        if(e.isNull("audioPath")) null else e.optString("audioPath").ifBlank { null },
        if(e.isNull("occurredAt")) null else e.getLong("occurredAt"),
        if(e.isNull("category")) null else e.optString("category").ifBlank { null },e.optJSONArray("personIds")?.strings() ?: emptyList(),e.optString("importance","日常").also { require(it in analysisEventImportance) { "重要程度配置损坏" } })
    private fun encode(s: AnalysisState) = JSONObject().put("events",JSONArray(s.events.map(::encodeEvent))).put("auto",s.autoUpdate).put("skills",JSONArray(s.skills)).put("reportPrompt",s.reportPrompt).put("askPrompt",s.askPrompt).put("defaultReportKind",s.defaultReportKind).put("people",JSONArray(s.people.map { p ->
        JSONObject().put("id",p.id).put("name",p.name).put("answers",JSONObject(p.answers)).put("revision",p.revision)
            .put("materials",JSONArray(p.materials.map { JSONObject().put("id",it.id).put("title",it.title).put("text",it.text).put("at",it.at).put("audioPath",it.audioPath ?: JSONObject.NULL).put("sourceKey",it.sourceKey ?: JSONObject.NULL) }))
            .put("events",JSONArray(p.events.map(::encodeEvent)))
            .put("reports",JSONArray(p.reports.map { r -> JSONObject().put("id",r.id).put("kind",r.kind).put("skills",JSONArray(r.skills)).put("markdown",r.markdown).put("revision",r.revision).put("at",r.at)
                .put("claims",JSONArray(r.claims.map { JSONObject().put("id",it.id).put("text",it.text).put("sources",JSONArray(it.sources)).put("verdict",it.verdict) })) }))
    }))
    private fun decode(o: JSONObject): AnalysisState = AnalysisState(o.getJSONArray("people").objects().map { p ->
        val a=p.getJSONObject("answers")
        AnalysisPerson(p.getString("id"),p.getString("name"),a.keys().asSequence().associateWith { a.getString(it) },p.getLong("revision"),
            p.getJSONArray("materials").objects().map { AnalysisMaterial(it.getString("id"),it.getString("title"),it.getString("text"),it.getLong("at"),if(it.isNull("audioPath")) null else it.optString("audioPath").ifBlank { null },if(it.isNull("sourceKey")) null else it.optString("sourceKey").ifBlank { null }) },
            p.getJSONArray("events").objects().map(::decodeEvent),
            p.getJSONArray("reports").objects().map { r -> AnalysisReport(r.getString("id"),r.getString("kind"),r.getString("markdown"),r.getJSONArray("claims").objects().map { c -> AnalysisClaim(c.getString("id"),c.getString("text"),c.getJSONArray("sources").strings(),c.optString("verdict")) },r.getLong("revision"),r.getLong("at"),decodeSkills(r)) })
    },o.optBoolean("auto"),decodeSkills(o),o.optJSONArray("events")?.objects()?.map(::decodeEvent) ?: emptyList(),o.optString("reportPrompt"),o.optString("askPrompt"),o.optString("defaultReportKind","性格与沟通").also { require(it in analysisReportKinds) { "报告类型无效" } })
}
internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.strings() = (0 until length()).map { getString(it) }
