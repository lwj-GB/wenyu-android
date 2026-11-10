package app.resound.analysis

import android.app.Application
import android.content.Context
import app.resound.skills.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class AnalysisDailyQuestionsTest {
    private lateinit var context:Context
    private lateinit var store:AnalysisStore
    private lateinit var person:String
    private val day=LocalDate.of(2026,11,11)
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        listOf("analysis-mode.json","analysis-skills.json").forEach { n -> context.deleteFile(n);context.deleteFile("$n.bak") }
        store=AnalysisStore(context)
        person=store.createPerson(mapOf("q1" to "虚构小林","q2" to "高中同学"))
    }
    private fun output(count:Int=3,suffix:String="")=JSONObject().put("cards",JSONArray((1..count).map { i ->
        JSONObject().put("title","第${i}件事${suffix}发生在什么时候？").put("summary","补充时间")
            .put("sources",JSONArray(listOf("q2"))).put("values",JSONObject().put("reason","关系记录缺少具体时间").put("event_time","未知"))
    })).toString()
    private fun repo(count:Int=3)=AnalysisSkillRepository(context,store) { _,_,_ -> output(count) }.also { it.selectPerson(person) }
    @Test fun generationPersistsQuestionsAndDoesNotReplaceAnsweredBatch()=runBlocking {
        val repo=repo();assertTrue(repo.runNow(DAILY_QUESTIONS_SKILL,day) is SkillRunResult.Cards)
        assertEquals(3,repo.pendingQuestions(person).size)
        val first=repo.pendingQuestions(person).first()
        repo.answerQuestion(first.id,"上周日",1234)
        assertEquals(DailyQuestionStatus.Answered,repo.dailyQuestions.value.first { it.id==first.id }.status)
        assertTrue(store.person(person).materials.single().text.contains("上周日"))
        repo.answerQuestion(first.id,"上周日",1234)
        assertEquals(1,store.person(person).materials.size)
        assertTrue(repo.runNow(DAILY_QUESTIONS_SKILL,day) is SkillRunResult.Nothing)
        assertTrue(repo.runNow(DAILY_QUESTIONS_SKILL,day.plusDays(1)) is SkillRunResult.Nothing)
        val restored=AnalysisSkillRepository(context,store)
        assertEquals(2,restored.pendingQuestions(person).size)
        assertEquals(1234L,restored.dailyQuestions.value.first { it.id==first.id }.occurredAt)
    }
    @Test fun unknownAndSkippedResolveWithoutInventingMaterialAndOldInstancesRefresh()=runBlocking {
        val repo=repo();val stale=AnalysisSkillRepository(context,store)
        repo.runNow(DAILY_QUESTIONS_SKILL,day)
        stale.refreshDailyQuestions()
        val questions=stale.pendingQuestions(person)
        stale.resolveQuestion(questions[0].id,DailyQuestionStatus.Unknown)
        stale.resolveQuestion(questions[1].id,DailyQuestionStatus.Skipped)
        assertTrue(store.person(person).materials.isEmpty())
        repo.refreshDailyQuestions()
        assertEquals(1,repo.pendingQuestions(person).size)
    }
    @Test fun oldSkillConfigurationGetsNewDefaultWithoutDroppingCustomSettings() {
        val repo=repo();repo.savePrompt("analysis_0","保留我的要求").getOrThrow()
        val file=File(context.filesDir,"analysis-skills.json");val json=JSONObject(file.readText())
        val old=json.getJSONArray("skills");json.put("skills",JSONArray((0 until old.length()).map { old.getJSONObject(it) }.filter { it.getJSONObject("definition").getString("id")!=DAILY_QUESTIONS_SKILL }))
        file.writeText(json.toString())
        val migrated=AnalysisSkillRepository(context,store);migrated.refreshDailyQuestions()
        assertTrue(migrated.skills.value.any { it.id==DAILY_QUESTIONS_SKILL })
        assertEquals("保留我的要求",migrated.prompt("analysis_0"))
    }
    @Test fun requestedCountMustMatchAndSourceMustExist()=runBlocking {
        val repo=repo(2);repo.setQuestionCount(2)
        assertTrue(repo.runNow(DAILY_QUESTIONS_SKILL,day) is SkillRunResult.Cards)
        assertEquals(2,repo.dailyQuestions.value.size)
        val other=store.createPerson(mapOf("q1" to "虚构另一人"));repo.selectPerson(other)
        assertTrue(repo.runNow(DAILY_QUESTIONS_SKILL,day) is SkillRunResult.Failed)
        assertTrue(repo.pendingQuestions(other).isEmpty())
    }
    @Test fun wallClockScheduleMovesToNextConfiguredDay() {
        val now=ZonedDateTime.of(2026,11,11,20,31,0,0,SKILL_ZONE)
        val next=AnalysisQuestionSchedule.nextRun(now,SkillTrigger.Daily(20*60+30))
        assertEquals(LocalDate.of(2026,11,12),next.toLocalDate())
        assertEquals(20,next.hour);assertEquals(30,next.minute)
    }
    @Test fun backgroundGenerationRequiresAuthorizationAndHonorsTime()=runBlocking {
        var calls=0
        val repo=AnalysisSkillRepository(context,store) { _,_,_ -> calls++;output() };repo.selectPerson(person)
        val after=day.atTime(21,0).atZone(SKILL_ZONE)
        repo.runDue(after);assertEquals(0,calls)
        // Seed persisted authorization directly: scheduling itself is an Android integration path.
        val file=File(context.filesDir,"analysis-skills.json");file.writeText(JSONObject(file.readText()).put("authorised",JSONArray(listOf(person))).toString())
        repo.runDue(day.atTime(20,29).atZone(SKILL_ZONE));assertEquals(0,calls)
        repo.runDue(after);assertEquals(1,calls)
        repo.runDue(after);assertEquals(1,calls)
        assertEquals(3,repo.pendingQuestions(person).size)
    }
    @Test fun authorizedBackgroundPushContainsOnlyReminderAndRoutesToCorrectPerson()=runBlocking {
        org.robolectric.Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifications=context.getSystemService(android.app.NotificationManager::class.java)
        notifications.cancelAll()
        val repo=repo()
        val after=day.atTime(21,0).atZone(SKILL_ZONE)
        repo.runDue(after)
        assertTrue(notifications.activeNotifications.isEmpty())
        val file=File(context.filesDir,"analysis-skills.json")
        file.writeText(JSONObject(file.readText()).put("authorised",JSONArray(listOf(person))).toString())
        repo.runDue(after)
        val notification=notifications.activeNotifications.single().notification
        assertFalse(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("虚构小林"))
        val intent=org.robolectric.Shadows.shadowOf(notification.contentIntent).savedIntent
        assertEquals(OPEN_ANALYSIS_QUESTIONS,intent.getStringExtra("open"))
        assertEquals(person,intent.getStringExtra(EXTRA_ANALYSIS_PERSON))
        repo.pendingQuestions(person).forEach { repo.resolveQuestion(it.id,DailyQuestionStatus.Skipped) }
        assertTrue(notifications.activeNotifications.isEmpty())
    }
    @Test fun answerMergesOtherInstanceChangesAndDeniedNotificationsKeepPendingQuestions()=runBlocking {
        val repo=repo()
        repo.runNow(DAILY_QUESTIONS_SKILL,day)
        val fresh=AnalysisStore(context)
        val other=fresh.createPerson(mapOf("q1" to "另一个虚构人物"))
        fresh.addMaterial(person,"外部追加","保留这个新材料")
        repo.answerQuestion(repo.pendingQuestions(person).first().id,"周二讨论过")
        val persisted=AnalysisStore(context)
        assertEquals(other,persisted.person(other).id)
        assertEquals(2,persisted.person(person).materials.size)
        assertEquals(2,store.person(person).materials.size)
        org.robolectric.Shadows.shadowOf(context as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifications=context.getSystemService(android.app.NotificationManager::class.java)
        notifications.cancelAll()
        AnalysisQuestionNotifications.post(context,person,2)
        assertTrue(notifications.activeNotifications.isEmpty())
        assertEquals(2,repo.pendingQuestions(person).size)
    }

    @Test fun twoPeopleUseGloballyUniqueQuestionIdsAndAnswersRemainScoped()=runBlocking {
        val repo=repo()
        repo.runNow(DAILY_QUESTIONS_SKILL,day)
        // A restored clock can be ahead of the current clock: new IDs must exceed saved IDs.
        val file=File(context.filesDir,"analysis-skills.json")
        val json=JSONObject(file.readText());val cards=json.getJSONArray("cards")
        val future=System.currentTimeMillis()+60_000
        for(i in 0 until cards.length()) cards.getJSONObject(i).put("id",future+i)
        file.writeText(json.toString());repo.refreshDailyQuestions()
        val firstIds=repo.dailyQuestions.value.map { it.id }.toSet()
        val other=store.createPerson(mapOf("q1" to "虚构小周","q2" to "合作方"))
        repo.selectPerson(other);repo.runNow(DAILY_QUESTIONS_SKILL,day)
        val all=repo.dailyQuestions.value
        assertEquals(6,all.size);assertEquals(6,all.map { it.id }.distinct().size)
        assertTrue(repo.pendingQuestions(other).all { it.id>firstIds.max() })
        val second=repo.pendingQuestions(other).first()
        repo.answerQuestion(second.id,"周三讨论过")
        assertTrue(store.person(person).materials.isEmpty())
        assertEquals(1,store.person(other).materials.size)
        assertEquals(3,repo.pendingQuestions(person).size)
        assertEquals(2,repo.pendingQuestions(other).size)
    }

}
