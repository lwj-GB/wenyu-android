package app.resound.ui

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.AppGraph
import app.resound.analysis.*
import app.resound.digest.*
import app.resound.ui.analysis.AnalysisIntelligencePage
import app.resound.ui.theme.ResoundTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application=Application::class,sdk=[35],qualifiers="w393dp-h852dp-xhdpi")
class AnalysisIntelligenceUiTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Before fun clear(){listOf("analysis-mode.json","analysis-skills.json","analysis-mode.json.bak","analysis-skills.json.bak").forEach{compose.activity.deleteFile(it)}}
    @Test fun skillsAreEditableWithoutSelectingPersonAndTrialRequestsScope() {
        val store=AnalysisStore(compose.activity)
        store.createPerson(mapOf("q1" to "虚构小林"))
        compose.setContent{ResoundTheme(analysisMode=true){AnalysisIntelligencePage(store,store.state.collectAsState().value,0.dp,true)}}
        compose.onNodeWithText("工作整理").performScrollTo().assertExists()
        compose.onNodeWithText("社交商务").performScrollTo().assertExists()
        compose.onNodeWithText("工作整理").performScrollTo().performClick()
        compose.onNodeWithText("给大模型的要求（提示词）").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("先编辑技能，运行前再确认人物。")
        compose.onNodeWithText("保存").performClick()
        Assert.assertEquals("先编辑技能，运行前再确认人物。",AnalysisSkillRepository(compose.activity,store).prompt("analysis_0"))
        compose.onNodeWithText("试跑").performScrollTo().performClick()
        compose.onAllNodesWithText("先选择人物并确认范围，再运行技能")[0].assertExists()
        compose.onNodeWithText("虚构小林").performClick()
        compose.onNodeWithText("确认范围").assertExists()
        Assert.assertTrue(store.intelligence.selectedPeople.value.isEmpty())
        compose.onNodeWithText("取消").performClick()
        Assert.assertTrue(store.intelligence.selectedPeople.value.isEmpty())
    }
    @Test fun selectsPersonAuthorisesAndOpensEditableBaseSkill() {
        val context=compose.activity
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AppGraph.llmProviders=AndroidLlmProviders(context,LlmClient(),object:ProviderKeys{
            override fun has(id:String)=false;override fun read(id:String)="";override fun write(id:String,key:String){};override fun remove(id:String){}
        })
        val store=AnalysisStore(context);val person=store.createPerson(mapOf("q1" to "虚构小林"))
        compose.setContent{ResoundTheme(analysisMode=true){AnalysisIntelligencePage(store,store.state.collectAsState().value,0.dp,true)}}
        compose.onNodeWithContentDescription("选择分析人物").performClick()
        compose.onNodeWithText("虚构小林").performClick()
        compose.onNodeWithText("确认范围").performClick()
        compose.onNodeWithText("运行设置").performClick()
        compose.onNode(isToggleable() and hasAnySibling(hasText("允许该人物按技能条件自动分析"))).performClick()
        compose.waitForIdle()
        Assert.assertTrue(AnalysisSkillRepository(context,store).authorised.value.contains(person))
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("工作整理").performScrollTo().performClick()
        compose.onNodeWithText("什么时候运行").performScrollTo().assertExists()
        compose.onNodeWithText("用哪个模型").performScrollTo().assertExists()
        compose.onNodeWithText("卡片上有什么").performScrollTo().assertExists()
        compose.onNodeWithText("试一下").performScrollTo().assertExists()
        compose.onNodeWithText("给大模型的要求（提示词）").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("仅整理当前人物的明确工作事项，保留出处与未知。")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        Assert.assertEquals("仅整理当前人物的明确工作事项，保留出处与未知。",AnalysisSkillRepository(context,store).prompt("analysis_0"))
    }
}
