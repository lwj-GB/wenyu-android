package app.resound.analysis

import android.app.Application
import android.content.Context
import app.resound.skills.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
class AnalysisSkillRepositoryTest {
    private lateinit var context:Context
    private lateinit var store:AnalysisStore
    private lateinit var person:String
    @Before fun setup(){context=RuntimeEnvironment.getApplication();listOf("analysis-mode.json","analysis-mode.json.bak","analysis-skills.json","analysis-skills.json.bak").forEach{context.deleteFile(it)};store=AnalysisStore(context);person=store.createPerson(mapOf("q1" to "虚构小林"));store.addMaterial(person,"原话","明确提出想读历史书")}
    private fun output(source:String="q1")="""{"cards":[{"title":"虚构卡片","summary":"材料整理","sources":["$source"],"values":{"summary":"明确陈述与未知分开","next_steps":["下次确认"]}}]}"""
    @Test fun `stale worker reloads revoked person authorization before sending`()=runBlocking {
        val seed=AnalysisSkillRepository(context,store){_,_,_->output()};seed.selectPerson(person)
        val file=java.io.File(context.filesDir,"analysis-skills.json")
        val json=org.json.JSONObject(file.readText()).put("authorised",org.json.JSONArray(listOf(person)))
        json.getJSONArray("skills").getJSONObject(0).getJSONObject("definition").put("trigger",org.json.JSONObject().put("type","AfterDigest").put("minGapMinutes",0))
        file.writeText(json.toString())
        var calls=0
        val worker=AnalysisSkillRepository(context,store){_,_,_->calls++;output()}
        val ui=AnalysisSkillRepository(context,store){_,_,_->output()};ui.authorise(person,false)
        worker.runDue()
        assertEquals(0,calls)
        assertFalse(AnalysisSkillRepository(context,store).authorised.value.contains(person))
    }
    @Test fun `trial and actual run use selected model fields sources and persist cards separately`()=runBlocking {
        var model:String?=null;var system="";var input=""
        val repo=AnalysisSkillRepository(context,store){p,s,u->model=p;system=s;input=u;output()}
        repo.selectPerson(person);repo.setPreset("analysis_0","chosen-model");repo.savePrompt("analysis_0","自定义工作要求").getOrThrow()
        assertTrue(repo.trial("analysis_0",LocalDate.now()) is SkillRunResult.Cards)
        assertTrue(repo.cardsOf("analysis_0").first().isEmpty())
        assertTrue(repo.runNow("analysis_0",LocalDate.now()) is SkillRunResult.Cards)
        assertEquals("chosen-model",model);assertTrue(system.contains("自定义工作要求"));assertTrue(input.contains("next_steps"));assertFalse(input.contains("个人全天采集"))
        val restored=AnalysisSkillRepository(context,store)
        assertEquals(1,restored.cardsOf("analysis_0").first().size)
        assertTrue(restored.runHistory.value.size>=2)
        assertTrue(restored.cardsOf("analysis_0").first().single().summary.contains("[q1] 虚构小林"))
    }
    @Test fun `unknown evidence and changed materials cannot save cards`()=runBlocking {
        val unknown=AnalysisSkillRepository(context,store){_,_,_->output("unknown")};unknown.selectPerson(person)
        assertTrue(unknown.runNow("analysis_0",LocalDate.now()) is SkillRunResult.Failed)
        val late=AnalysisSkillRepository(context,store){_,_,_->store.addMaterial(person,"迟到","新增事实");output()};late.selectPerson(person)
        assertTrue(late.runNow("analysis_0",LocalDate.now()) is SkillRunResult.Failed)
        assertTrue(late.cardsOf("analysis_0").first().isEmpty())
    }
    @Test fun `draft validates skill definition and does not save before confirmation`()=runBlocking {
        val repo=AnalysisSkillRepository(context,store){_,_,_->"""{"skill":{"name":"沟通复盘","summary":"整理沟通事实与问题","icon":"chat","trigger":{"type":"Manual"},"scope":{"range":"Week","speakers":"All","includeLines":true},"maxCardsPerDay":1,"fields":[{"key":"summary","label":"概括","type":"Paragraph","hint":"描述事实","required":true}],"prompt":"只依据明确选择的人物资料与出处整理，区分事实推测和未知；没有相关资料时不出卡。"}}"""}
        val before=repo.skills.value.size
        val draft=repo.draft(listOf(DraftTurn(true,"帮我做沟通复盘"))).getOrThrow() as DraftStep.Done
        assertEquals(before,repo.skills.value.size)
        repo.saveMine(draft.draft.skill,draft.draft.prompt).getOrThrow()
        assertEquals(before+1,AnalysisSkillRepository(context,store).skills.value.size)
    }
    @Test fun `background run preserves person selected after worker was created`()=runBlocking {
        val other=store.createPerson(mapOf("q1" to "虚构小周"))
        val seed=AnalysisSkillRepository(context,store){_,_,_->output()}
        seed.selectPerson(person)
        val file=java.io.File(context.filesDir,"analysis-skills.json")
        val json=org.json.JSONObject(file.readText()).put("authorised",org.json.JSONArray(listOf(person)))
        json.getJSONArray("skills").getJSONObject(0).getJSONObject("definition")
            .put("trigger",org.json.JSONObject().put("type","AfterDigest").put("minGapMinutes",0))
        file.writeText(json.toString())
        var calls=0
        val worker=AnalysisSkillRepository(context,store){_,_,_->calls++;output()}
        val ui=AnalysisSkillRepository(context,store)
        ui.selectPerson(other)
        worker.runDue()
        assertEquals(1,calls)
        assertEquals(setOf(other),AnalysisSkillRepository(context,store).selectedPeople.value)
    }

    @Test @Config(sdk=[35])
    fun `failed card save keeps since last run material available on retry`()=runBlocking {
        val material=store.person(person).materials.single()
        val blocker=java.io.File(context.filesDir,"analysis-skills.json.new")
        var calls=0
        val sent=mutableListOf<String>()
        val repo=AnalysisSkillRepository(context,store){_,_,user->
            sent+=user
            if(calls++==0) {
                check(blocker.mkdir())
                java.io.File(blocker,"block-write").writeText("synthetic write failure")
            }
            output()
        }
        repo.selectPerson(person)
        val mine=repo.skills.value.first().copy(id="my_retry",origin=SkillOrigin.Mine,
            scope=SkillScope(SkillRange.SinceLastRun,SkillSpeakers.All,true))
        repo.saveMine(mine,"仅按明确来源整理").getOrThrow()
        try {
            val failed=repo.runNow(mine.id,LocalDate.now())
            assertTrue(failed is SkillRunResult.Failed)
            assertTrue(repo.cardsOf(mine.id).first().isEmpty())
        } finally {
            java.io.File(blocker,"block-write").delete()
            blocker.delete()
        }
        assertTrue(repo.runNow(mine.id,LocalDate.now()) is SkillRunResult.Cards)
        assertTrue(sent.all { org.json.JSONObject(it).getJSONObject("sources").has(material.id) })
    }

}
