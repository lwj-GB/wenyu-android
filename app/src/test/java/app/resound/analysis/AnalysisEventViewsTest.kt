package app.resound.analysis

import android.app.Application
import android.content.Context
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
class AnalysisEventViewsTest {
    @Test fun `legacy event dates and categories stay unknown and counts reflect records only`() {
        val e=AnalysisEvent("e","虚构会议","文本提及见过十次",1000)
        val p=AnalysisPerson("p","虚构甲",emptyMap(),events=listOf(e),materials=listOf(
            AnalysisMaterial("m","原话","虚构记录",2000,sourceKey="personal-transcript:1"),
            AnalysisMaterial("m2","重复引用","虚构记录",2500,sourceKey="personal-transcript:1")))
        val state=AnalysisState(people=listOf(p),events=listOf(AnalysisEvent("other","独立事件","未关联",5000)))
        val row=analysisEventRows(state).first { it.event.id=="e" }
        assertTrue(row.dateIsRecorded); assertEquals("未分类",row.category); assertEquals(listOf("p"),row.personIds)
        val stats=analysisInteractionStats(state,"p",ZoneId.of("UTC"))
        assertEquals(1,stats.recordedDays); assertEquals(1,stats.importedSegments); assertEquals(1,stats.eventCount)
        assertEquals(2500L,stats.latestRecordedAt)
    }
    @Test fun `explicit event metadata persists and invalid links are rejected`() {
        val context=RuntimeEnvironment.getApplication() as Context
        context.deleteFile("analysis-mode.json"); context.deleteFile("analysis-mode.json.bak")
        val store=AnalysisStore(context); val id=store.createPerson(mapOf("q1" to "虚构甲"))
        store.addStandaloneEvent("虚构会议","资料",occurredAt=1000,category="合作",personIds=listOf(id))
        val restored=AnalysisStore(context)
        val row=analysisEventRows(restored.state.value).single()
        assertFalse(row.dateIsRecorded); assertEquals(1000L,row.timelineAt); assertEquals("合作",row.category)
        assertEquals(listOf(id),row.personIds); assertEquals(2L,restored.person(id).revision)
        try { store.addStandaloneEvent("无效","资料",personIds=listOf("missing")); fail("invalid person") } catch (_:IllegalArgumentException) {}
        assertEquals(1,store.state.value.events.size)
    }
    @Test fun `collections use explicit importance while timelines retain recorded chronology`() {
        val state=AnalysisState(events=listOf(
            AnalysisEvent("late","普通事件","很紧急很开心也不会自动提级",9000,category="工作"),
            AnalysisEvent("urgent","工作急事","虚构",1000,category="工作",importance="紧急"),
            AnalysisEvent("sad","情感转折","虚构",2000,category="感情",importance="重要·难过"),
            AnalysisEvent("happy","值得纪念","虚构",3000,category="朋友",importance="重要·开心")))
        val rows=analysisEventRows(state)
        assertEquals("late",rows.first().event.id)
        assertEquals(listOf("紧急","重要·难过","重要·开心","日常"),analysisEventCollections(rows).map{it.first})
        assertEquals("日常",rows.first().event.importance)
        assertTrue(analysisEventCategories.containsAll(listOf("工作","学习","朋友","感情")))
    }
    @Test fun `existing event metadata can change persist and preserve text and ownership`() {
        val context=RuntimeEnvironment.getApplication() as Context
        context.deleteFile("analysis-mode.json"); context.deleteFile("analysis-mode.json.bak")
        val store=AnalysisStore(context); val person=store.createPerson(mapOf("q1" to "虚构甲"))
        val event=store.addStandaloneEvent("虚构工作","原文保持",personIds=listOf(person))
        store.updateEventMetadata(event,"工作","紧急")
        val restored=AnalysisStore(context)
        val row=analysisEventRows(restored.state.value).single()
        assertEquals("工作",row.category);assertEquals("紧急",row.event.importance)
        assertEquals("原文保持",row.event.text);assertEquals(listOf(person),row.personIds)
        val revision=restored.person(person).revision
        restored.updateEventMetadata(event,"工作","紧急")
        assertEquals(revision,restored.person(person).revision)
        try { restored.updateEventMetadata(event,"工作","自动高风险");fail("invalid importance") } catch(_:IllegalArgumentException) {}
    }
    @Test fun `uncertain review can return to pending without changing report text`() {
        val context=RuntimeEnvironment.getApplication() as Context
        context.deleteFile("analysis-mode.json"); context.deleteFile("analysis-mode.json.bak")
        val store=AnalysisStore(context); val id=store.createPerson(mapOf("q1" to "虚构甲"))
        store.append(id,AnalysisReport("r",analysisReportKinds.first(),"保留原报告",listOf(AnalysisClaim("c","待核对判断",listOf("q1"))),1,1000))
        store.review(id,"r","c","不确定")
        store.review(id,"r","c","")
        val report=AnalysisStore(context).person(id).reports.single()
        assertEquals("",report.claims.single().verdict); assertEquals("保留原报告",report.markdown)
    }

}
