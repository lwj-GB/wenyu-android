package app.resound.analysis

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
class AnalysisAskRepositoryTest {
    private lateinit var context:Context
    private lateinit var store:AnalysisStore
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        for(name in listOf("analysis-mode.json","analysis-mode.json.bak","analysis-ask.json","analysis-ask.json.bak")) context.deleteFile(name)
        store=AnalysisStore(context)
    }
    @Test fun `explicit person requires confirmation and sends no other person`()=runBlocking {
        store.createPerson(mapOf("q1" to "张三","q8" to "科幻"))
        store.createPerson(mapOf("q1" to "李四","q8" to "秘密兴趣"))
        var sent=""; var calls=0
        val engine=AnalysisEngine(store) { _,user -> calls++; sent=user; """{"text":"他提到科幻[q8]","sources":["q8"]}""" }
        val repo=AnalysisAskRepository(context,store,this,engine)
        repo.ask("张三喜欢什么")
        assertEquals(0,calls); assertEquals("张三",repo.pending.value?.personName)
        repo.confirmPending(); yield()
        assertEquals(1,calls); assertFalse(sent.contains("秘密兴趣")); assertFalse(sent.contains("李四"))
        assertEquals(2,repo.messages.first().size)
        val restored=AnalysisAskRepository(context,store,this,engine)
        val conversation=restored.history("",10).first().single()
        restored.openConversation(conversation.id)
        assertEquals("他提到科幻[q8]",restored.messages.first().last().text)
        assertEquals("科幻",restored.messages.first().last().sources.single().quote)
    }
    @Test fun `ambiguous name clarifies locally and relationship disambiguates`()=runBlocking {
        store.createPerson(mapOf("q1" to "张三","q2" to "同学"))
        store.createPerson(mapOf("q1" to "张三","q2" to "同事"))
        val repo=AnalysisAskRepository(context,store,this,AnalysisEngine(store) { _,_ -> error("must not upload") })
        repo.ask("张三喜欢什么")
        assertNull(repo.pending.value); assertTrue(repo.messages.first().last().text.contains("同名"))
        assertEquals("张三",repo.prepare("同事张三喜欢什么").personName)
        assertNotNull(repo.prepare("同事张三喜欢什么").personId)
    }
    @Test fun `personal segment import is opt in idempotent and independent events persist`() {
        val a=store.createPerson(mapOf("q1" to "虚构甲")); val b=store.createPerson(mapOf("q1" to "虚构乙"))
        assertTrue(store.importPersonalTranscript(a,12,100,200,"虚构原话"))
        assertFalse(store.importPersonalTranscript(a,12,100,200,"虚构原话"))
        assertTrue(store.person(b).materials.isEmpty())
        store.addStandaloneEvent("虚构会议","记录", "/private/fake.wav")
        val restored=AnalysisStore(context)
        assertEquals("personal-transcript:12",restored.person(a).materials.single().sourceKey)
        assertEquals("/private/fake.wav",restored.state.value.events.single().audioPath)
    }
}
