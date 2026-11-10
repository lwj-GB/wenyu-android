package app.resound.analysis

import app.resound.digest.MemoryEvent
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AnalysisSocialStatsTest {
    private val date=LocalDate.of(2026,11,10)
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun event(id:Long,people:List<String>,emotion:String?=null,day:LocalDate=date)=MemoryEvent(
        id,day.atStartOfDay(zone).toInstant().toEpochMilli(),day.atStartOfDay(zone).toInstant().toEpochMilli(),
        "闲聊","虚构记忆","内容里写开心不会变成情绪标签",emptyList(),people,null,emotion,null,false,emptyList())
    @Test fun `today stats count memory mentions rather than every saved profile`() {
        val a=event(1,listOf("虚构同学"," 我 ","未知","说话人2"),"愉快")
        val stats=analysisSocialStats(listOf(a,a,event(2,listOf("虚构同学","虚构朋友")),event(3,listOf("昨天的人"),day=date.minusDays(1))),date,zone)
        assertEquals(listOf("虚构同学","虚构朋友"),stats.people)
        assertEquals(2,stats.eventCount)
        assertEquals("😊",stats.emotionEmoji)
    }
    @Test fun `emotion labels are explicit and missing emotion stays unrecorded`() {
        val none=analysisSocialStats(listOf(event(1,emptyList())),date,zone)
        assertTrue(none.emotions.isEmpty());assertEquals("—",none.emotionEmoji)
        val mixed=analysisSocialStats(listOf(event(1,emptyList(),"平静"),event(2,emptyList(),"难过")),date,zone)
        assertEquals("😌😔",mixed.emotionEmoji)
        assertNull(recordedEmotionEmoji("文字里觉得开心"))
    }
}
