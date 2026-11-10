package app.resound.ui.analysisrecording

import app.resound.transcribe.*
import org.junit.Assert.*
import org.junit.Test

class PersonalTranscriptImportTest {
    private fun segment(id: Long, at: Long, text: String) = SegmentTranscript(id,at,at+1000,TranscriptStatus.Done,
        listOf(TranscriptLine(id,at,at+1000,text,null)),audioAvailable=false)

    @Test fun onlySelectedNonemptySegmentsAreCopiedWithStableIdentityAndSourceText() {
        val entries = listOf(segment(1,1000,"已选原文"),segment(2,2000,"未选私密原文"),segment(3,3000,"  "))
        val result = selectedPersonalTranscripts(entries,setOf(1,3))
        assertEquals(1,result.size)
        assertEquals("personal-transcript:1",result.single().sourceKey)
        assertTrue(result.single().text.contains("已选原文"))
        assertFalse(result.single().text.contains("未选私密原文"))
    }
    @Test fun snapshotIsSortedDeduplicatedAndMissingSelectionDoesNotCreateMaterial() {
        val entries = listOf(segment(2,2000,"后"),segment(1,1000,"前"),segment(2,2000,"重复"))
        assertEquals(listOf(1L,2L),selectedPersonalTranscripts(entries,setOf(1,2,99)).map { it.segmentId })
        assertTrue(selectedPersonalTranscripts(entries,emptySet()).isEmpty())
    }
}
