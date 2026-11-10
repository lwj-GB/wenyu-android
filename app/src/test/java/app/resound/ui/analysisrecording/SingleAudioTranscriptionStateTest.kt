package app.resound.ui.analysisrecording

import org.junit.Assert.*
import org.junit.Test

class SingleAudioTranscriptionStateTest {
    @Test fun transcriptionCompletesWithActualTextAndElapsedWithoutAnalysisSideEffect() {
        val initial=SingleAudioTranscriptionState(text="旧转写",completedMillis=100)
        val running=initial.start()
        assertTrue(running.running)
        assertNull(running.text)
        assertNull(running.completedMillis)
        val completed=running.complete("新转写原文",1234)
        assertFalse(completed.running)
        assertEquals("新转写原文",completed.text)
        assertEquals(1234L,completed.completedMillis)
    }
    @Test fun emptyTranscriptCannotBePresentedAsCompleted() {
        try{SingleAudioTranscriptionState().start().complete("  ",100);fail("blank should fail")}catch(_:IllegalArgumentException){}
    }
}
