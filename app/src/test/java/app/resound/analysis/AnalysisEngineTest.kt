package app.resound.analysis

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
class AnalysisEngineTest {
    private lateinit var store: AnalysisStore
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication() as Context
        context.deleteFile("analysis-mode.json"); context.deleteFile("analysis-mode.json.bak")
        store=AnalysisStore(context)
    }
    @Test fun `profile suggestions need evidence and confirmation and reject stale save`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构小林", "q3" to "学生"))
        val material=store.addMaterial(id,"录音转写","小林说喜欢读历史书")
        val engine=AnalysisEngine(store) { _,_ -> """{"fields":[{"id":"q6","text":"历史书","sources":["$material"]}]}""" }
        val proposal=engine.suggestProfile(id)
        assertNull(store.person(id).answers["q6"])
        store.applyProfileSuggestions(id,proposal.revision,proposal.fields.associate{it.id to it.text})
        assertEquals("历史书",store.person(id).answers["q6"])
        assertEquals("学生",store.person(id).answers["q3"])
        try { store.applyProfileSuggestions(id,proposal.revision,mapOf("q8" to "游泳"));fail("stale") }catch(_:IllegalArgumentException){}
    }
    @Test fun `profile cannot overwrite filled fields or cite unknown evidence`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构小林"))
        store.addMaterial(id,"录音转写","多人讨论读书")
        val engine=AnalysisEngine(store) { _,_ -> """{"fields":[{"id":"q6","text":"历史书","sources":["missing"]}]}""" }
        try {engine.suggestProfile(id);fail("bad source")}catch(_:IllegalArgumentException){}
        store.applyProfileSuggestions(id,store.person(id).revision,mapOf("q1" to "另一人"))
        assertEquals("虚构小林",store.person(id).name)
    }
    @Test fun `skill is sent and unknown markdown source cannot be saved`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构小林"))
        store.setStyle("情感关系")
        var system=""
        val engine=AnalysisEngine(store) { s,_ -> system=s; """{"markdown":"推测[not-a-source]","claims":[]}""" }
        try { engine.generate(id,"兴趣偏好"); fail("unknown citation") } catch (_:IllegalArgumentException) {}
        assertTrue(system.contains("双方感受")); assertTrue(store.person(id).reports.isEmpty())
    }
    @Test fun `late model result after material change is discarded`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构小林"))
        val engine=AnalysisEngine(store) { _,_ ->
            store.addMaterial(id,"补充","用户新增材料")
            """{"markdown":"姓名由用户提供[q1]","claims":[]}"""
        }
        try { engine.generate(id,"兴趣偏好"); fail("stale result") } catch (_:IllegalStateException) {}
        assertTrue(store.person(id).reports.isEmpty())
    }
    @Test fun `question includes latest report as explicitly uncertain source`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构小林"))
        store.append(id,AnalysisReport("r","兴趣偏好","资料不足",emptyList(),1,1))
        var sent=""
        val engine=AnalysisEngine(store) { _,user -> sent=user; """{"text":"目前资料不足[report:r]","sources":["report:r"]}""" }
        assertEquals(listOf("report:r"),engine.ask(id,"送什么礼物").sources)
        assertTrue(sent.contains("历史AI推测"))
        assertTrue(sent.contains("不得作为新增独立证据"))
    }
    @Test fun `style round trip rejects response even if final style matches`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构小林"))
        val engine=AnalysisEngine(store) { _,_ ->
            store.setStyle("情感关系"); store.setStyle("心理学视角")
            """{"markdown":"用户提供姓名[q1]","claims":[]}"""
        }
        try { engine.generate(id,"兴趣偏好"); fail("stale style generation") } catch (_:IllegalStateException) {}
        assertTrue(store.person(id).reports.isEmpty())
    }
    @Test fun `unsupported prose and blank claims are rejected`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构小林"))
        for (output in listOf(
            """{"markdown":"他喜欢科幻","claims":[]}""",
            """{"markdown":"用户提供姓名[q1]","claims":[{"text":"  ","sources":["q1"]}]}"""
        )) {
            val engine=AnalysisEngine(store) { _,_ -> output }
            try { engine.generate(id,"兴趣偏好"); fail("invalid analysis") } catch (_:IllegalArgumentException) {}
        }
        assertTrue(store.person(id).reports.isEmpty())
    }

    @Test fun `combined skills enter both prompts and report retains selected snapshot`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构甲"))
        store.setSkills(listOf("情感关系","社交商务"))
        val prompts=mutableListOf<String>()
        val engine=AnalysisEngine(store) { system,user ->
            prompts+=system
            if(user.contains("question")) """{"text":"资料不足","sources":[]}"""
            else """{"markdown":"资料不足","claims":[]}"""
        }
        val report=engine.generate(id,"交往建议")
        engine.ask(id,"怎样相处")
        assertEquals(listOf("情感关系","社交商务"),report.skills)
        assertTrue(prompts.all { it.contains("双方感受") && it.contains("合作边界") && it.contains("观察事实") && it.contains("引用sources") })
        store.setSkills(emptyList())
        assertEquals(listOf("情感关系","社交商务"),store.person(id).reports.single().skills)
        prompts.clear(); engine.ask(id,"还有什么")
        assertTrue(prompts.single().contains("观察事实")); assertFalse(prompts.single().contains("合作边界"))
    }

    @Test fun `prompt edits apply by task and round trip invalidates in flight result`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构甲"))
        store.setBasePrompt("report","报告偏好用短段落")
        store.setBasePrompt("ask","问答偏好先给可行行动")
        val seen=mutableListOf<String>()
        val engine=AnalysisEngine(store) { system,user ->
            seen+=system
            if(user.contains("question")) """{"text":"资料不足","sources":[]}""" else """{"markdown":"资料不足","claims":[]}"""
        }
        engine.generate(id,"交往建议"); engine.ask(id,"怎样沟通")
        assertTrue(seen[0].contains("报告偏好用短段落")); assertFalse(seen[0].contains("问答偏好先给可行行动"))
        assertTrue(seen[1].contains("问答偏好先给可行行动")); assertTrue(seen[1].contains("始终遵守基础规范"))
        val stale=AnalysisEngine(store) { _,_ ->
            store.setBasePrompt("ask","改动"); store.setBasePrompt("ask","问答偏好先给可行行动")
            """{"text":"资料不足","sources":[]}"""
        }
        try { stale.ask(id,"怎样沟通"); fail("prompt generation must reject") } catch (_:IllegalStateException) {}
    }
    @Test fun `ignored claim remains reviewable but is not evidence feedback`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构忽略人物"))
        store.append(id,AnalysisReport("r",analysisReportKinds.first(),"保留原文",listOf(AnalysisClaim("c","忽略的推测",listOf("q1"))),1,1000))
        store.review(id,"r","c","已忽略")
        var sent=""
        val engine=AnalysisEngine(store){_,user->sent=user;"""{"text":"资料不足","sources":[]}"""}
        engine.ask(id,"有什么需要了解")
        assertEquals(0,org.json.JSONObject(sent).getJSONArray("feedback").length())
        val restored=AnalysisStore(RuntimeEnvironment.getApplication())
        assertEquals("已忽略",restored.person(id).reports.single().claims.single().verdict)
        assertEquals("保留原文",restored.person(id).reports.single().markdown)
        store.previewBackup(store.exportBackup())
        Unit
    }

    @Test fun `report prose uses structured sources without visible identifiers`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构小林"))
        val material=store.addMaterial(id,"合作记录","11月11日约定三天后交付，预算1200元")
        var instruction=""
        val engine=AnalysisEngine(store) { system,_ ->
            instruction=system
            """{"markdown":"目前约定三天后交付，预算1200元。可以先确认交付范围。","claims":[{"text":"交付范围还需要核对。","sources":["$material"]}]}"""
        }
        val report=engine.generate(id,"交往建议")
        assertEquals("目前约定三天后交付，预算1200元。可以先确认交付范围。",report.markdown)
        assertEquals(listOf(material),report.claims.single().sources)
        assertTrue(instruction.contains("正文不写[来源id]"))
        assertFalse(report.markdown.contains(material))
    }

    @Test fun `legacy source markers are hidden in prose and exports without changing stored links`() = runBlocking {
        val id=store.createPerson(mapOf("q1" to "虚构小林","q7" to "喜欢清淡口味"))
        val material=store.addMaterial(id,"周末安排","2026年11月11日18:30约定两人吃饭，预算120元")
        val engine=AnalysisEngine(store) { _,_ ->
            """{"markdown":"2026年11月11日18:30两人吃饭，预算120元。[$material]偏好清淡。[q7]","claims":[{"text":"偏好清淡。[q7]","sources":["q7","$material"]}]}"""
        }
        val report=engine.generate(id,"兴趣偏好")
        assertEquals("2026年11月11日18:30两人吃饭，预算120元。偏好清淡。",report.markdown)
        assertEquals("偏好清淡。",report.claims.single().text)
        val old=report.copy(id="old-report",markdown="过去的报告正文[$material] [q7]",at=report.at+1)
        store.append(id,old)
        val exported=store.reportMarkdown(id,old.id)
        assertTrue(exported.contains("周末安排"))
        assertTrue(exported.contains("喜欢清淡口味"))
        assertFalse(exported.contains(material))
        assertFalse(exported.contains("[q7]"))
        assertEquals(listOf("q7",material),store.person(id).reports.last().claims.single().sources)
        assertTrue(store.person(id).reports.last().markdown.contains(material))
    }

    @Test fun `readable text keeps ordinary numbers dates brackets and markdown links`() {
        val uuid="12345678-1234-1234-1234-123456789abc"
        val text="## 2. 交付安排\n2026-11-11 18:30，2至3人，1200元。[2026] [普通说明] [q1]【来源：$uuid，q2】\n[原文](https://example.test/2026)\n[]"
        val cleaned=readableAnalysisText(text,listOf("q1","q2",uuid))
        assertTrue(cleaned.contains("## 2. 交付安排"))
        assertTrue(cleaned.contains("2026-11-11 18:30，2至3人，1200元。[2026] [普通说明]"))
        assertTrue(cleaned.contains("[原文](https://example.test/2026)"))
        assertTrue(cleaned.contains("[]"))
        assertFalse(cleaned.contains("[q1]"))
        assertFalse(cleaned.contains(uuid))
    }

}
