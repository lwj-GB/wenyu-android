package app.resound.analysis

import android.content.Context
import android.util.AtomicFile
import app.resound.ask.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AnalysisQuestionScope(val question: String, val personId: String?, val personName: String?, val clarification: String? = null)
class AnalysisAskRepository(context: Context, private val store: AnalysisStore, private val scope: CoroutineScope,
    private val engine: AnalysisEngine = AnalysisEngine(store)) : AskRepository {
    private val file=AtomicFile(File(context.filesDir,"analysis-ask.json"))
    private data class Chat(val id: Long, val title: String, val personId: String?, val messages: List<AskMessage>)
    private val chats=MutableStateFlow<List<Chat>>(emptyList())
    private val selected=MutableStateFlow<Long?>(null)
    override val currentConversation=selected.asStateFlow()
    override val messages=combine(chats,selected) { rows,id -> rows.firstOrNull { it.id==id }?.messages ?: emptyList() }
    override val suggestions=MutableStateFlow(listOf("张三可能喜欢什么礼物？", "怎样和张三沟通？")).asStateFlow()
    private val mutablePending=MutableStateFlow<AnalysisQuestionScope?>(null)
    val pending=mutablePending.asStateFlow()
    private var job: Job?=null
    private var serial=System.currentTimeMillis()
    val loadError: String?
    init {
        var error: String?=null
        try {
            if(file.baseFile.exists() || File(file.baseFile.path+".bak").exists()) {
                val a=JSONArray(file.openRead().bufferedReader().use { it.readText() })
                chats.value=a.objects().map { c -> Chat(c.getLong("id"),c.getString("title"),c.optString("personId").ifBlank { null },c.getJSONArray("messages").objects().map { m -> AskMessage(m.getLong("id"),AskRole.valueOf(m.getString("role")),m.getString("text"),m.getJSONArray("sources").objects().map { AskSource("analysis",it.getLong("id"),it.getLong("at"),it.getString("title"),it.getString("quote")) },m.getLong("at"),false,error=m.optString("error").ifBlank { null }) }) }
            }
        } catch (_:Exception) { error="分析聊天读取失败，请保留文件后恢复备份" }
        loadError=error
        serial=maxOf(serial,chats.value.flatMap { it.messages.map { m -> m.id } + it.id }.maxOrNull() ?: 0)
    }
    private fun persist() = synchronized(AnalysisStore.fileLock) {
        check(loadError==null) { loadError.orEmpty() }
        val a=JSONArray(chats.value.map { c -> JSONObject().put("id",c.id).put("title",c.title).put("personId",c.personId.orEmpty()).put("messages",JSONArray(c.messages.map { m -> JSONObject().put("id",m.id).put("role",m.role.name).put("text",m.text).put("at",m.at).put("error",m.error.orEmpty()).put("sources",JSONArray(m.sources.map { JSONObject().put("id",it.id).put("at",it.at).put("title",it.title).put("quote",it.quote) })) })) })
        val out=file.startWrite(); try { out.write(a.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) } catch(e:Exception) { file.failWrite(out); throw e }
    }
    private fun update(id: Long, f:(Chat)->Chat) { val old=chats.value; chats.value=old.map { if(it.id==id) f(it) else it }; try { persist() } catch(e:Exception) { chats.value=old; throw e } }
    override fun history(query: String, limit: Int)=chats.map { rows -> rows.filter { it.title.contains(query,true) }.sortedByDescending { it.messages.lastOrNull()?.at ?: it.id }.take(limit).map { AskConversation(it.id,it.title,it.messages.lastOrNull()?.text.orEmpty(),it.messages.lastOrNull()?.at ?: it.id) } }
    override fun openConversation(id: Long) { stop(); selected.value=id.takeIf { n -> chats.value.any { it.id==n } } }
    override fun renameConversation(id:Long,title:String)=update(id) { it.copy(title=title) }
    override fun deleteConversation(id:Long) { stop(); val old=chats.value; chats.value=old.filterNot { it.id==id }; try { persist() } catch(e:Exception) { chats.value=old; throw e }; if(selected.value==id) selected.value=null }
    override fun newConversation() { stop(); selected.value=null }
    fun prepare(question:String):AnalysisQuestionScope {
        var matches=store.state.value.people.filter { p -> p.name.isNotBlank() && question.contains(p.name,true) }
        if(matches.size>1) {
            val refined=matches.filter { it.answers["q2"].orEmpty().let { relation -> relation.isNotBlank() && question.contains(relation) } }
            if(refined.size==1) matches=refined
        }
        if(matches.size==1) return AnalysisQuestionScope(question,matches.single().id,matches.single().name)
        if(matches.size>1) return AnalysisQuestionScope(question,null,null,"涉及多位人物或同名人物，请补充昵称或关系，明确本次要问谁。")
        val previous=chats.value.firstOrNull { it.id==selected.value }?.personId?.let { id -> store.state.value.people.firstOrNull { it.id==id } }
        if(previous!=null && listOf("他","她","这个人","对方").any { question.contains(it) }) return AnalysisQuestionScope(question,previous.id,previous.name)
        return AnalysisQuestionScope(question,null,null,"请在问题里写出通讯录中的姓名或昵称，例如“张三可能喜欢什么礼物”。")
    }
    override fun ask(question:String) {
        if(question.isBlank() || job?.isActive==true) return
        val plan=prepare(question)
        if(plan.personId==null) { val id=ensureChat(question); update(id) { it.copy(messages=it.messages+message(AskRole.User,question)+message(AskRole.Assistant,plan.clarification.orEmpty())) }; return }
        mutablePending.value=plan
    }
    fun cancelPending() { mutablePending.value=null }
    fun target()=engine.displayTarget()
    fun confirmPending() {
        val plan=mutablePending.value ?: return
        mutablePending.value=null
        val personId=plan.personId ?: return
        val id=ensureChat(plan.question)
        val previous=chats.value.first { it.id==id }
        val conversation=if(previous.personId==personId) previous.messages.filter { it.error==null && !it.streaming }.takeLast(8).joinToString("\n") { "${it.role}: ${it.text}" }.takeLast(8000) else ""
        update(id) { it.copy(personId=personId,messages=it.messages+message(AskRole.User,plan.question)) }
        val answerId=++serial
        update(id) { it.copy(messages=it.messages+AskMessage(answerId,AskRole.Assistant,"",emptyList(),System.currentTimeMillis(),true,progress="正在核对${plan.personName}的资料…")) }
        job=scope.launch {
            try {
                val answer=engine.ask(personId,plan.question,conversation)
                val p=store.person(personId)
                val refs=answer.sources.mapIndexed { index,ref ->
                    val material=p.materials.firstOrNull { it.id==ref }; val event=analysisEventRows(store.state.value).firstOrNull { p.id in it.personIds && it.event.id==ref }?.event; val report=p.reports.firstOrNull { "report:"+it.id==ref }
                    AskSource("analysis",index.toLong(),material?.at ?: event?.at ?: report?.at ?: 0,material?.title ?: event?.title ?: report?.kind ?: analysisQuestions.firstOrNull { it.id==ref }?.title ?: ref,material?.text ?: event?.text ?: report?.markdown ?: p.answers[ref].orEmpty())
                }
                update(id) { it.copy(messages=it.messages.map { m -> if(m.id==answerId) m.copy(text=answer.text,sources=refs,streaming=false,progress=null) else m }) }
            } catch(e:CancellationException) {
                update(id) { it.copy(messages=it.messages.map { m -> if(m.id==answerId) m.copy(streaming=false,progress=null,error="已停止") else m }) }; throw e
            } catch(e:Exception) {
                val reason=when(e) { is app.resound.digest.LlmException -> e.detail; is IllegalArgumentException,is IllegalStateException -> e.message ?: "分析失败"; else -> "分析失败，请重试" }
                update(id) { it.copy(messages=it.messages.map { m -> if(m.id==answerId) m.copy(streaming=false,progress=null,error=reason) else m }) }
            }
        }
    }
    private fun ensureChat(question:String):Long {
        selected.value?.let { return it }
        val c=Chat(++serial,question.take(30),null,emptyList()); val old=chats.value
        chats.value=old+c; try { persist() } catch(e:Exception) { chats.value=old; throw e }; selected.value=c.id; return c.id
    }
    private fun message(role:AskRole,text:String)=AskMessage(++serial,role,text,emptyList(),System.currentTimeMillis(),false)
    override fun stop() { mutablePending.value=null; job?.cancel(); job=null }
}
