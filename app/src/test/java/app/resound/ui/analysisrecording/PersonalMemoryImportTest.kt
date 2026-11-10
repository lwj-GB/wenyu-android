package app.resound.ui.analysisrecording

import app.resound.digest.*
import org.junit.Assert.*
import org.junit.Test

class PersonalMemoryImportTest {
    private fun event(id: Long, time: Long) = MemoryEvent(id,time,time+1000,"闲聊","虚构讨论$id","已整理的讨论摘要$id",
        listOf("讨论读书", "讨论周末安排"),listOf("虚构小林"),null,null,null,false,
        listOf(MemoryItem(id,id,"决定",null,"周末去图书馆",null,null,null,ItemStatus.Pending,null)))

    @Test fun `selected organized events contain summary timeline and categorized text only`() {
        val events=listOf(event(2,2000),event(1,1000))
        val selected=selectedPersonalMemories(events,setOf(2,99))
        assertEquals(listOf(2L),selected.map { it.segmentId })
        assertEquals("personal-memory:2",selected.single().sourceKey)
        assertTrue(selected.single().text.contains("已整理的讨论摘要2"))
        assertTrue(selected.single().text.contains("• 讨论周末安排"))
        assertTrue(selected.single().text.contains("决定：周末去图书馆"))
        assertFalse(selected.single().text.contains("已整理的讨论摘要1"))
    }

    @Test fun `select all uses current organized events deduplicates and toggles off`() {
        val events=listOf(event(2,2000),event(1,1000),event(2,2000))
        val available=personalMemoryChoices(events)
        val selected=toggleAllMemories(available,setOf(1,99))
        assertEquals(setOf(1L,2L),selected)
        assertEquals(listOf(1L,2L),selectedPersonalMemories(events,selected).map { it.segmentId })
        assertTrue(toggleAllMemories(available,selected).isEmpty())
        assertTrue(selectedPersonalMemories(events,emptySet()).isEmpty())
    }
}
