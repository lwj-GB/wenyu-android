package app.resound.analysis

import app.resound.digest.MemoryEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Statistics of saved daily memories; names and emotions retain their status as recorded labels. */
data class AnalysisSocialStats(val people:List<String>,val eventCount:Int,val emotions:List<String>) {
    val emotionEmoji:String get()=emotions.mapNotNull(::recordedEmotionEmoji).distinct().take(3).joinToString("").ifBlank{"—"}
}
fun recordedEmotionEmoji(emotion:String):String?=when(emotion.trim()) {
    "愉快","开心","高兴","快乐","兴奋" -> "😊"
    "平静","放松","轻松" -> "😌"
    "难过","悲伤","伤心","低落" -> "😔"
    "焦虑","担心","紧张" -> "😟"
    "生气","愤怒","烦躁" -> "😠"
    "疲惫","疲倦" -> "😴"
    else -> null
}
fun analysisSocialStats(events:List<MemoryEvent>,date:LocalDate,zone:ZoneId=ZoneId.systemDefault()):AnalysisSocialStats {
    val today=events.filter{Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate()==date}.distinctBy{it.id}
    val unknown=setOf("我","本人","用户","未知","不详","待确认","有人","其他人","对方","多人","两人")
    val people=today.flatMap{it.people}.map{it.trim()}.filter{it.isNotBlank() && it !in unknown && !it.matches(Regex("说话人[\\d一二三四五六七八九十]*"))}.distinct()
    return AnalysisSocialStats(people,today.size,today.mapNotNull{it.emotion?.trim()?.takeIf{e->e.isNotEmpty()}}.distinct())
}
