package app.resound.analysis

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], manifest=Config.NONE, application=Application::class)
class AnalysisStoreTest {
    private lateinit var context: Context
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.deleteFile("analysis-mode.json")
        context.deleteFile("analysis-mode.json.bak")
    }
    @Test fun `person materials events and feedback survive restart without touching personal preferences`() {
        context.getSharedPreferences("digest",0).edit().putString("sentinel","keep").commit()
        val s=AnalysisStore(context)
        val id=s.createPerson(mapOf("q1" to "虚构小林", "q2" to "同学"))
        s.addMaterial(id,"兴趣", "他说喜欢科幻")
        s.addEvent(id,"读书会", "一起讨论了一本书")
        val revision=s.person(id).revision
        s.append(id,AnalysisReport("r","兴趣偏好","可能喜欢科幻",listOf(AnalysisClaim("c","可能喜欢科幻",listOf("q2"))),revision,1))
        s.review(id,"r","c","不确定")
        s.setAutoUpdate(true)
        val restored=AnalysisStore(context)
        assertEquals(s.state.value,restored.state.value)
        assertEquals("不确定",restored.person(id).reports.single().claims.single().verdict)
        assertEquals("keep",context.getSharedPreferences("digest",0).getString("sentinel",null))
    }
    @Test fun `stale result is rejected and previous report is retained`() {
        val s=AnalysisStore(context); val id=s.createPerson(mapOf("q1" to "虚构小周"))
        val report=AnalysisReport("r","兴趣偏好","资料不足",emptyList(),s.person(id).revision,1)
        s.append(id,report)
        s.addMaterial(id,"新材料","新的用户陈述")
        try { s.append(id,report.copy(id="late")); fail("must reject stale result") } catch (_:IllegalStateException) {}
        assertEquals(listOf("r"),s.person(id).reports.map { it.id })
    }
    @Test fun `corrupt file is never silently replaced`() {
        val f=java.io.File(context.filesDir,"analysis-mode.json")
        f.writeText("invalid-json")
        val store=AnalysisStore(context)
        assertNotNull(store.loadError)
        try { store.createPerson(mapOf("q1" to "虚构测试")); fail("must block overwrite") } catch (_:IllegalStateException) {}
        assertEquals("invalid-json",f.readText())
    }
    @Test fun `legacy style migrates preserving selection and empty combination survives restart`() {
        val original=AnalysisStore(context)
        original.createPerson(mapOf("q1" to "虚构甲"))
        val file=java.io.File(context.filesDir,"analysis-mode.json")
        val json=org.json.JSONObject(file.readText())
        json.remove("skills"); json.put("style","社交商务"); file.writeText(json.toString())
        val migrated=AnalysisStore(context)
        assertEquals(listOf("社交商务"),migrated.state.value.skills)
        migrated.setSkills(listOf("社交商务","情感关系","社交商务"))
        assertEquals(listOf("情感关系","社交商务"),AnalysisStore(context).state.value.skills)
        migrated.setSkills(emptyList())
        migrated.setBasePrompt("report","短段落")
        migrated.setBasePrompt("ask","先说行动")
        assertEquals("短段落",AnalysisStore(context).state.value.reportPrompt)
        assertEquals("先说行动",AnalysisStore(context).state.value.askPrompt)
        assertTrue(AnalysisStore(context).state.value.skills.isEmpty())
    }

}
