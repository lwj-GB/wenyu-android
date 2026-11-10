package app.resound.transcribe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 用户对一段录音的补充；不写入转写原文，也不用于覆盖声纹身份。 */
data class ConversationContext(val segmentId: Long, val participants: String, val background: String, val updatedAt: Long) {
    fun json(): JSONObject = JSONObject().put("segmentId", segmentId).put("participants", participants)
        .put("background", background).put("updatedAt", updatedAt).put("source", "user")
}

class ConversationContextStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("conversation-context", Context.MODE_PRIVATE)

    fun get(segmentId: Long): ConversationContext? = prefs.getString(segmentId.toString(), null)?.let { raw ->
        val json = JSONObject(raw)
        ConversationContext(segmentId, json.getString("participants"), json.getString("background"), json.getLong("updatedAt"))
    }

    fun forSegments(segmentIds: Collection<Long>): List<ConversationContext> = segmentIds.distinct().sorted().mapNotNull(::get)

    fun save(segmentId: Long, participants: String, background: String, updatedAt: Long = System.currentTimeMillis()) {
        require(segmentId > 0 && updatedAt >= 0)
        val people = participants.trim()
        val notes = background.trim()
        require(people.length <= 200 && notes.length <= 2000) { "对话对象最多200字，背景最多2000字" }
        val edit = prefs.edit()
        if (people.isBlank() && notes.isBlank()) edit.remove(segmentId.toString())
        else edit.putString(segmentId.toString(), ConversationContext(segmentId, people, notes, updatedAt).json().toString())
        check(edit.commit()) { "补充信息未能保存，请重试" }
    }
}

internal fun conversationContextsJson(contexts: List<ConversationContext>): JSONArray =
    JSONArray(contexts.distinctBy { it.segmentId }.sortedBy { it.segmentId }.map { it.json() })
