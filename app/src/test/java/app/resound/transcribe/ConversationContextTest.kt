package app.resound.transcribe

import android.app.Application
import android.content.Context
import app.resound.data.TranscriptLineEntity
import app.resound.digest.DigestPrompt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ConversationContextTest {
    @Test fun `notes persist for one selected segment and can be removed without changing other segments`() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("conversation-context", Context.MODE_PRIVATE).edit().clear().commit()
        val store = ConversationContextStore(context)
        store.save(9, " 我与虚构的小林 ", "讨论测试项目", 1000)
        store.save(10, "虚构的小周", "另一段背景", 2000)
        assertEquals("我与虚构的小林", ConversationContextStore(context).get(9)?.participants)
        assertEquals(listOf(9L), store.forSegments(listOf(9)).map { it.segmentId })
        store.save(9, "", "", 3000)
        assertNull(store.get(9))
        assertEquals("虚构的小周", store.get(10)?.participants)
    }

    @Test fun `digest receives only matching notes as user claims with original numbering and unknown speakers intact`() {
        val lines = listOf(
            TranscriptLineEntity(1, 9, 1000, 2000, "虚构原话一", null),
            TranscriptLineEntity(2, 10, 3000, 4000, "虚构原话二", null),
        )
        val (text, indexed) = DigestPrompt.transcript(lines)
        val input = DigestPrompt.withConversationContext(text, lines, listOf(
            ConversationContext(9, "我与虚构的小林", "讨论测试项目", 5000),
            ConversationContext(99, "其他录音的对象", "不得混入", 5000),
        ))
        assertTrue(input.contains("我与虚构的小林"))
        assertTrue(input.contains("\"lineNumbers\":[1]"))
        assertFalse(input.contains("不得混入"))
        assertTrue(input.endsWith(text))
        assertEquals(lines, indexed)
        assertEquals("某人", DigestPrompt.speaker(indexed[0]))
        assertTrue(DigestPrompt.CONVERSATION_CONTEXT_RULES.contains("不是录音原话"))
        assertTrue(DigestPrompt.CONVERSATION_CONTEXT_RULES.contains("不得据此覆盖声纹判断"))
    }
}
