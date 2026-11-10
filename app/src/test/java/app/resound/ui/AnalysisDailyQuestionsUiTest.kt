package app.resound.ui

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.analysis.*
import app.resound.ui.analysis.*
import app.resound.ui.theme.ResoundTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application=Application::class,sdk=[35],qualifiers="w393dp-h852dp-xhdpi")
class AnalysisDailyQuestionsUiTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Before fun setup(){listOf("analysis-mode.json","analysis-skills.json").forEach{compose.activity.deleteFile(it);compose.activity.deleteFile("$it.bak")};WorkManagerTestInitHelper.initializeTestWorkManager(compose.activity)}
    @Test fun contactDotFollowsPendingQuestionsAndAnswersBecomeMaterial()=runBlocking {
        val store=AnalysisStore(compose.activity)
        val id=store.createPerson(mapOf("q1" to "虚构高中同学","q2" to "同学"))
        val repo=AnalysisSkillRepository(compose.activity,store){_,_,_->JSONObject().put("cards",JSONArray((1..3).map{n->JSONObject().put("title","问题$n：这次约定是什么时候？").put("summary","补充时间").put("sources",JSONArray(listOf("q2"))).put("values",JSONObject().put("reason","发生时间尚不清楚").put("event_time","未知"))})).toString()}
        repo.selectPerson(id);repo.runNow(DAILY_QUESTIONS_SKILL,LocalDate.of(2026,11,11))
        compose.setContent{ResoundTheme(analysisMode=true){AnalysisModeScreen(analysisStore=store){}}}
        compose.onNode(hasText("通讯录") and hasAnyAncestor(hasTestTag("analysis-page-0"))).performClick()
        compose.onNodeWithTag("person-pending-dot",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("问题1：这次约定是什么时候？").performScrollTo().performClick()
        compose.onNodeWithTag("daily-question-answer").performTextInput("上周一起商量的")
        compose.onNodeWithTag("daily-question-date").performTextInput("日期不明")
        compose.onNodeWithText("保存回答").performClick()
        compose.onNodeWithText("请按年-月-日填写发生日期").assertExists()
        compose.onNodeWithTag("daily-question-date").performTextClearance()
        compose.onNodeWithTag("daily-question-date").performTextInput("2026-11-08")
        compose.onNodeWithText("保存回答").performClick()
        compose.runOnIdle{Assert.assertEquals(1,store.person(id).materials.size);Assert.assertTrue(store.person(id).materials.single().text.contains("上周一起商量的"))}
        compose.onNodeWithText("问题2：这次约定是什么时候？").performScrollTo().performClick()
        compose.onNodeWithText("不知道").performClick()
        compose.onNodeWithText("问题3：这次约定是什么时候？").performScrollTo().performClick()
        compose.onNodeWithText("跳过").performClick()
        compose.onNode(hasText("通讯录") and hasAnyAncestor(hasTestTag("analysis-page-0"))).performScrollTo().performClick()
        compose.onNodeWithTag("person-pending-dot",useUnmergedTree=true).assertDoesNotExist()
        compose.runOnIdle{Assert.assertEquals(1,store.person(id).materials.size)}
    }
    @Test fun notificationRequestSelectsTheMatchingPerson() {
        val store=AnalysisStore(compose.activity)
        store.createPerson(mapOf("q1" to "虚构甲"))
        val second=store.createPerson(mapOf("q1" to "虚构乙"))
        var consumed=false
        compose.setContent{ResoundTheme(analysisMode=true){AnalysisModeScreen(analysisStore=store,requestedPersonId=second,onPersonRequestHandled={consumed=true}){}}}
        compose.onNode(hasText("虚构乙") and hasAnyAncestor(hasTestTag("person-recording-card"))).assertExists()
        compose.onNode(hasText("虚构甲") and hasAnyAncestor(hasTestTag("person-recording-card"))).assertDoesNotExist()
        compose.runOnIdle{Assert.assertTrue(consumed)}
    }
    @Test fun dailySettingsSaveTwoQuestionsAndDefaultTime() {
        val store=AnalysisStore(compose.activity)
        compose.setContent{ResoundTheme(analysisMode=true){DailyQuestionsSettings(store.intelligence){throw AssertionError(it)}}}
        compose.onNodeWithTag("daily-questions-settings").performClick()
        compose.onNodeWithText("提醒时间 · 20:30").assertExists()
        compose.onNodeWithText("2问").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("每日资料补充 · 20:30 · 2问").assertExists()
        Assert.assertEquals(2,AnalysisSkillRepository(compose.activity,store).skills.value.first{it.id==DAILY_QUESTIONS_SKILL}.maxCardsPerDay)
    }
}
