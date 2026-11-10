package app.resound.ui

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.analysis.*
import app.resound.ui.preview.*
import app.resound.ui.analysis.AnalysisModeScreen
import app.resound.ui.theme.ResoundTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application=Application::class,sdk=[35],qualifiers="w393dp-h852dp-xhdpi")
class AnalysisModeUiTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Before fun clear() { compose.activity.filesDir.resolve("analysis-mode.json").delete() }
    @After fun clean() { compose.activity.filesDir.resolve("analysis-mode.json").delete() }
    private fun show() { compose.setContent { ResoundTheme(analysisMode=true) { AnalysisModeScreen {} } } }
    @Test fun smartAnalysisCreatesMissingPersonAndAcceptsProfileOnlyBeforeMaterialConfirmation() {
        val store=AnalysisStore(compose.activity)
        compose.setContent { ResoundTheme(analysisMode=true) { AnalysisModeScreen(analysisStore=store) {} } }
        compose.onNodeWithTag("analysis-smart-action").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("必填；可以使用昵称").performTextInput("虚构按钮人物")
        compose.onNodeWithText("先录音建档").performClick()
        val personId=store.state.value.people.single().id
        compose.onNodeWithTag("analysis-local-transcribe").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("本地转写").assertExists()
        val record=compose.onNodeWithText("● 开始录音").fetchSemanticsNode().boundsInRoot
        val local=compose.onNodeWithTag("analysis-local-transcribe").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("本地转写位于录音右侧",local.left>record.right)
        compose.onNodeWithTag("analysis-smart-action").assertIsEnabled().performClick()
        compose.onNodeWithText("尚未添加原文材料",substring=true).assertExists()
        compose.onNodeWithText("发送并分析").assertIsEnabled()
        compose.onNodeWithText("补充材料").assertExists()
        compose.onNodeWithText("编辑档案").assertExists()
        compose.onNodeWithText("取消").performClick()
        Assert.assertTrue(store.person(personId).reports.isEmpty())
        compose.runOnIdle { store.addMaterial(personId,"虚构素材","虚构原话：周六一起去图书馆。") }
        compose.onNodeWithTag("analysis-smart-action").performClick()
        compose.onNodeWithText("虚构按钮人物的基础回答、1份材料",substring=true).assertExists()
        compose.onNodeWithText("取消").performClick()
        Assert.assertTrue(store.person(personId).reports.isEmpty())
    }
    @Test fun switchingPersonChangesVisibleProfile() {
        val store=AnalysisStore(compose.activity)
        val id=store.createPerson(mapOf("q1" to "虚构甲","q2" to "虚构同事甲"))
        store.append(id,AnalysisReport("preview",analysisReportKinds.first(),"## 相处中的一个线索\n用户描述这是一位同事。目前资料有限，尚不能判断稳定性格。\n\n可以先从一次具体合作谈起，了解对方偏好的沟通方式。",listOf(AnalysisClaim("c1","他可能更喜欢提前说明安排。",listOf("q2"))),1,System.currentTimeMillis()))
        store.createPerson(mapOf("q1" to "虚构乙","q2" to "虚构朋友乙"))
        show()
        compose.onNode(hasText("虚构甲") and hasAnyAncestor(hasTestTag("person-recording-card"))).assertExists()
        savePreview("person")
        compose.onNode(hasText("通讯录") and hasAnyAncestor(hasTestTag("analysis-page-0"))).performClick()
        compose.onNodeWithText("虚构乙",useUnmergedTree=true).performClick()
        compose.onNode(hasText("虚构乙") and hasAnyAncestor(hasTestTag("person-recording-card"))).assertExists()
        compose.onNode(hasText("虚构甲") and hasAnyAncestor(hasTestTag("person-recording-card"))).assertDoesNotExist()
    }
    @Test fun wizardCreatesPersistentProfile() {
        show()
        compose.onNodeWithText("＋ 新建人物").performClick()
        compose.onNodeWithText("必填；可以使用昵称").performTextInput("虚构新人物")
        repeat(14) { compose.onNodeWithText("下一题").performClick();compose.waitForIdle() }
        compose.onNodeWithText("保存人物").performClick()
        compose.waitForIdle()
        Assert.assertEquals("虚构新人物",AnalysisStore(compose.activity).state.value.people.single().name)
    }
    @Test fun materialEntrySavesToSelectedPerson() {
        val store=AnalysisStore(compose.activity)
        val id=store.createPerson(mapOf("q1" to "虚构材料人物"))
        show()
        compose.onNodeWithText("＋ 补充材料").performScrollTo().performClick()
        compose.onNodeWithText("标题").performTextInput("虚构材料")
        compose.onNodeWithText("粘贴原文或填写你的观察").performTextInput("虚构原句：喜欢安静的书店。")
        compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
        compose.waitForIdle()
        Assert.assertEquals("虚构原句：喜欢安静的书店。",AnalysisStore(compose.activity).person(id).materials.single().text)
    }
    @Test fun modeTitleAndThemeUseHostCallbacks() {
        var exited=false
        var chosen=app.resound.ui.theme.ThemeMode.System
        compose.setContent { ResoundTheme(analysisMode=true) { AnalysisModeScreen(onExit={exited=true},onThemeMode={chosen=it}) } }
        compose.onNodeWithContentDescription("切换明暗主题").performClick()
        Assert.assertEquals(app.resound.ui.theme.ThemeMode.Light,chosen)
        compose.onNodeWithText("分析模式").performClick()
        Assert.assertTrue(exited)
        Assert.assertEquals("文鱼",compose.activity.getString(app.resound.R.string.app_name))
    }
    @Test fun pagesKeepTheirOwnActionsAndStandaloneEventCanSave() {
        show()
        compose.onNodeWithTag("shell-tab-3").performClick()
        compose.onNodeWithText("选择人物").assertDoesNotExist()
        compose.onNode(hasText("＋ 新建人物") and hasAnyAncestor(hasTestTag("analysis-page-3"))).assertDoesNotExist()
        compose.onNodeWithText("＋ 新建").performClick()
        compose.onNodeWithText("事件名称").performTextInput("虚构事件")
        compose.onNodeWithText("发生了什么").performTextInput("虚构独立事件原文")
        compose.onNodeWithText("保存事件").performClick()
        Assert.assertEquals("虚构事件",AnalysisStore(compose.activity).state.value.events.single().title)
        compose.onNodeWithText("虚构事件").performClick()
        compose.onNodeWithText("虚构独立事件原文").assertExists()
        compose.onNodeWithText("修改分类").performScrollTo().performClick()
        compose.onNode(hasText("工作") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("标记重要程度").performScrollTo().performClick()
        compose.onNode(hasText("紧急") and hasAnyAncestor(isPopup())).performClick()
        Assert.assertEquals("工作",AnalysisStore(compose.activity).state.value.events.single().category)
        Assert.assertEquals("紧急",AnalysisStore(compose.activity).state.value.events.single().importance)
        compose.onNodeWithText("合集").assertDoesNotExist()
        savePreview("five-pages-events")
        compose.onNodeWithText("按类").performClick()
        compose.onNodeWithText("工作").assertExists()
        compose.onNodeWithText("按人").performClick()
        compose.onNodeWithText("未关联人物").assertExists()
        compose.onNodeWithTag("shell-tab-2").performClick()
        compose.onNodeWithTag("shell-tab-2").assertExists()
        savePreview("ask-layout")
    }
    @Test fun relationshipUsesSelectionAndNameIsCompact() {
        show()
        compose.onNodeWithText("＋ 新建人物").performClick()
        compose.onNodeWithText("必填；可以使用昵称").performTextInput("虚构同学")
        savePreview("compact-name")
        compose.onNodeWithText("下一题").performClick()
        compose.onNodeWithText("选择关系").performClick()
        compose.onNodeWithText("同学").performClick()
        compose.onNodeWithText("先录音建档").performClick()
        compose.onNodeWithTag("person-recording-card").assertExists()
        Assert.assertEquals("同学",AnalysisStore(compose.activity).state.value.people.single().answers["q2"])
        savePreview("person-recording")
    }
    @Test fun reviewTabShowsProgressAndUncertainFilterWithoutRecentEventCard() {
        val store=AnalysisStore(compose.activity)
        val id=store.createPerson(mapOf("q1" to "虚构核对人物"))
        store.addEvent(id,"虚构相关事件","原文")
        store.append(id,AnalysisReport("r",analysisReportKinds.first(),"报告正文",listOf(AnalysisClaim("c","虚构判断",listOf("q1"))),store.person(id).revision,1000))
        show()
        compose.onNode(hasText("待核对") and hasAnyAncestor(hasTestTag("analysis-page-0"))).performScrollTo().performClick()
        compose.onNode(hasText("近期关联事件") and hasAnyAncestor(hasTestTag("analysis-page-0"))).assertDoesNotExist()
        compose.onNode(hasText("核对进度 · 0 / 1") and hasAnyAncestor(hasTestTag("analysis-page-0"))).assertExists()
        compose.onNode(hasText("不确定") and hasAnyAncestor(hasTestTag("analysis-page-0"))).performScrollTo().performClick()
        compose.onNode(hasText("暂不确定") and hasAnyAncestor(hasTestTag("analysis-page-0"))).performScrollTo().performClick()
        compose.onNode(hasText("重新待核对") and hasAnyAncestor(hasTestTag("analysis-page-0"))).performScrollTo().performClick()
        Assert.assertEquals("",AnalysisStore(compose.activity).person(id).reports.single().claims.single().verdict)
    }
    @Test fun personalHeaderShowsDateAndCurrentMode() {
        var entered=false
        compose.setContent { ResoundTheme { app.resound.ui.today.TodayScreen(FakeCaptureController(),FakeStatsRepository(),FakeTranscriptRepository(),FakeMemoryRepository(),FakeDigestSettings(),app.resound.ui.theme.ThemeMode.System,{},0.dp,onAnalysisMode={entered=true}) } }
        compose.onNodeWithText("个人模式").performClick()
        Assert.assertTrue(entered)
        compose.onNodeWithText(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("M月d日"))).assertExists()
        savePreview("personal-header")
    }
    @Test fun fivePagesSwipeAndContactsShowRealCounts() {
        val store=AnalysisStore(compose.activity)
        val id=store.createPerson(mapOf("q1" to "虚构联系人","q2" to "同学"))
        store.addMaterial(id,"测试材料","虚构原话")
        show()
        compose.onNodeWithTag("shell-tab-4").assertExists()
        compose.onNodeWithTag("shell-tab-5").assertDoesNotExist()
        compose.onNode(hasText("通讯录") and hasAnyAncestor(hasTestTag("analysis-page-0"))).performClick()
        compose.onNodeWithText("同学 · 1份材料 · 0份报告").assertExists()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("analysis-pages").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNode(hasText("虚构联系人") and hasAnyAncestor(hasTestTag("analysis-page-1"))).assertExists()
        savePreview("five-pages-report")
    }
    @Test fun analysisSettingsCombineSkillsEditBaseAndResetAfterLeaving() {
        show()
        compose.onNodeWithTag("shell-tab-4").performClick()
        compose.onNodeWithText("分析技能").performClick()
        compose.onNodeWithTag("analysis-skill-情感关系").performScrollTo().performClick()
        compose.onNodeWithTag("analysis-skill-社交商务").performScrollTo().performClick()
        val stored=AnalysisStore(compose.activity)
        Assert.assertTrue(stored.state.value.skills.containsAll(listOf("情感关系","社交商务")))
        savePreview("combined-skills")
        compose.onNode(hasText("报告整理") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("补充你的整理要求（可留空）").performTextInput("先解释原话，再提出一个可选行动。")
        compose.onNodeWithText("保存").performClick()
        Assert.assertEquals("先解释原话，再提出一个可选行动。",AnalysisStore(compose.activity).state.value.reportPrompt)
        compose.onNodeWithTag("shell-tab-2").performClick()
        compose.onNodeWithTag("shell-tab-4").performClick()
        compose.onNodeWithTag("analysis-skill-情感关系").assertDoesNotExist()
        compose.onNodeWithText("关于文鱼").assertExists()
    }
    @Test fun aboutUsesProjectAndUpstreamSeparately() {
        compose.setContent { ResoundTheme { app.resound.ui.settings.AboutPage(null,0.dp) } }
        compose.onNodeWithText("文鱼是什么").assertExists()
        compose.onNodeWithText("问题反馈").assertDoesNotExist()
        compose.onNodeWithText("本项目主页 · 灵犀桥").performScrollTo().assertExists()
        compose.onNodeWithText("上游来源 · TaoSheng").performScrollTo().assertExists()
        Assert.assertEquals("https://github.com/lwj-GB/lingxi-bridge",app.resound.ui.settings.HOMEPAGE_URL)
        Assert.assertEquals("https://github.com/0xCoach/TaoSheng",app.resound.ui.settings.UPSTREAM_URL)
    }
    @Test fun personWorkbenchOpensLinkedEventAndCreatesPrelinkedDraft() {
        val store=AnalysisStore(compose.activity)
        val id=store.createPerson(mapOf("q1" to "虚构卡片人物"))
        store.addEvent(id,"虚构关联会谈","虚构会谈原文")
        show()
        compose.onNode(hasText("虚构关联会谈") and hasAnyAncestor(hasTestTag("analysis-page-0"))).performScrollTo().performClick()
        compose.onNode(hasText("虚构会谈原文") and hasAnyAncestor(hasTestTag("analysis-page-0"))).assertExists()
        compose.onNodeWithText("＋ 记录关联事件").performScrollTo().performClick()
        compose.onNodeWithText("事件名称").performTextInput("虚构后续事件")
        compose.onNodeWithText("发生了什么").performTextInput("虚构后续原文")
        compose.onNodeWithText("保存事件").performClick()
        Assert.assertEquals(listOf(id),AnalysisStore(compose.activity).state.value.events.single().personIds)
    }
    @Test fun defaultReportAndProfileChangesSaveWithoutReplacingMaterials() {
        val store=AnalysisStore(compose.activity)
        val id=store.createPerson(mapOf("q1" to "虚构旧昵称","q2" to "朋友"))
        store.addMaterial(id,"保留材料","虚构原句")
        show()
        compose.onNodeWithTag("shell-tab-4").performClick()
        compose.onNode(hasText("性格与沟通") and hasAnyAncestor(hasTestTag("analysis-page-4"))).performScrollTo().performClick()
        compose.onNode(hasText("兴趣偏好") and hasAnyAncestor(isPopup())).performClick()
        Assert.assertEquals("兴趣偏好",AnalysisStore(compose.activity).state.value.defaultReportKind)
        compose.onNodeWithTag("shell-tab-0").performClick()
        compose.onNodeWithText("档案").assertDoesNotExist()
        compose.onNodeWithTag("person-profile-open").assertHasClickAction().performScrollTo().performClick()
        compose.onNodeWithText("编辑档案").performClick()
        compose.onNode(hasText("虚构旧昵称") and hasSetTextAction()).performTextReplacement("虚构新昵称")
        compose.onNodeWithText("保存档案").performClick()
        val saved=AnalysisStore(compose.activity).person(id)
        Assert.assertEquals("虚构新昵称",saved.name)
        Assert.assertEquals("虚构原句",saved.materials.single().text)
    }
    @Test fun recordingCardLeadsProfileAndRetainsActions() {
        AnalysisStore(compose.activity).createPerson(mapOf("q1" to "虚构同学","q2" to "朋友","q3" to "研究生"))
        show()
        compose.onNodeWithTag("person-recording-card").assertIsDisplayed()
        compose.onNodeWithText("● 开始录音").assertIsDisplayed()
        compose.onNodeWithText("导入音频").assertIsDisplayed()
        savePreview("recording-first-purple")
        val recordingY=compose.onNodeWithTag("person-recording-card").fetchSemanticsNode().positionInRoot.y
        val summaryY=compose.onNodeWithText("交集记录").fetchSemanticsNode().positionInRoot.y
        Assert.assertTrue("录音应位于交集记录之前",recordingY<summaryY)
        compose.onNodeWithText("档案").assertDoesNotExist()
        compose.onNodeWithTag("person-profile-open").assertHasClickAction().performScrollTo().performClick()
        compose.onNodeWithText("编辑档案").assertExists()
    }
    @Test fun eventSegmentedViewsShowRealDataAndEmptyDayOffersReturn() {
        val store=AnalysisStore(compose.activity)
        val person=store.createPerson(mapOf("q1" to "虚构朋友"))
        val yesterday=java.time.LocalDate.now().minusDays(1).atTime(14,30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        store.addStandaloneEvent("虚构讨论","一起讨论学习计划。",occurredAt=yesterday,category="学习",personIds=listOf(person))
        show()
        compose.onNodeWithTag("shell-tab-3").performClick()
        compose.onNodeWithTag("event-view-switch").assertIsDisplayed()
        compose.onNodeWithTag("event-view-0").assertIsSelected()
        compose.onNodeWithText("查看最近记录").performClick()
        compose.onNode(hasText("虚构讨论") and hasAnyAncestor(hasTestTag("analysis-page-3"))).assertIsDisplayed()
        compose.onNodeWithTag("event-view-1").performClick().assertIsSelected()
        compose.onNode(hasText("学习") and hasAnyAncestor(hasTestTag("analysis-page-3"))).assertExists()
        savePreview("event-segmented-class")
        compose.onNodeWithTag("event-view-2").performClick().assertIsSelected()
        compose.onNode(hasText("虚构朋友") and hasAnyAncestor(hasTestTag("analysis-page-3"))).assertExists()
        compose.onNode(hasText("虚构讨论") and hasAnyAncestor(hasTestTag("analysis-page-3"))).assertExists()
        savePreview("event-segmented-person")
    }
    @Test fun dailySocialFoldUsesRecordedMemoryPeopleAndEmotion() {
        val date=java.time.LocalDate.now()
        val at=date.atTime(12,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val event=app.resound.digest.MemoryEvent(1,at,at,"闲聊","虚构谈话","虚构内容",emptyList(),listOf("虚构甲","虚构乙"),null,"愉快",null,false,emptyList())
        compose.setContent {ResoundTheme(analysisMode=true){androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(20.dp)){app.resound.ui.analysis.SocialDaySummary(listOf(event),date)}}}
        compose.onNodeWithText("2人 · 1件事 · 😊").assertExists()
        compose.onNodeWithTag("social-day-details").assertDoesNotExist()
        compose.onNodeWithTag("social-day-toggle").performClick()
        compose.onNodeWithText("提到").assertExists();compose.onNodeWithText("事件").assertExists();compose.onNodeWithText("状态 · 编辑").assertExists()
        compose.onNodeWithTag("social-status-edit").performClick()
        compose.onNodeWithText("😌 放松").performClick()
        compose.onNodeWithText("保存状态").performClick()
        Assert.assertEquals("😌 放松",compose.activity.getSharedPreferences("ui",0).getString("social-status-$date",null))
        compose.onNodeWithText("2人 · 1件事 · 😌 放松").assertExists()
        compose.onNodeWithTag("social-status-edit").performClick()
        compose.onNodeWithTag("daily-status-input").performTextReplacement("🌱 慢慢来")
        compose.onNodeWithText("保存状态").performClick()
        compose.onNodeWithText("2人 · 1件事 · 🌱 慢慢来").assertExists()
        compose.onNodeWithTag("social-status-edit").performClick()
        compose.onNodeWithText("恢复自动显示").performClick()
        Assert.assertFalse(compose.activity.getSharedPreferences("ui",0).contains("social-status-$date"))
        compose.onNodeWithText("2人 · 1件事 · 😊").assertExists()
        savePreview("social-day-expanded")
        compose.onNodeWithTag("social-day-toggle").performClick()
        compose.onNodeWithTag("social-day-details").assertDoesNotExist()
    }
    @Test fun pendingClaimsUseLatestVersionAcceptIgnoreAndReopenWithoutChangingReport() {
        val store=AnalysisStore(compose.activity)
        val id=store.createPerson(mapOf("q1" to "虚构确认人物"))
        store.append(id,AnalysisReport("old",analysisReportKinds.first(),"旧版原文",listOf(AnalysisClaim("old-c","旧版判断",listOf("q1"))),1,500))
        store.append(id,AnalysisReport("new",analysisReportKinds.first(),"新版原文",listOf(AnalysisClaim("new-c","新版判断",listOf("q1"))),1,1000))
        show()
        compose.onNode(hasText("待确认 · 1") and hasAnyAncestor(hasTestTag("person-pending"))).performScrollTo().assertExists()
        compose.onNode(hasText("旧版判断") and hasAnyAncestor(hasTestTag("person-pending"))).assertDoesNotExist()
        compose.onNode(hasText("查看依据") and hasAnyAncestor(hasTestTag("person-pending"))).performClick()
        compose.onNodeWithText("核对依据").assertExists();compose.onNodeWithText("关闭").performClick()
        compose.onNode(hasText("接受") and hasAnyAncestor(hasTestTag("person-pending"))).performClick()
        Assert.assertEquals("准确",AnalysisStore(compose.activity).person(id).reports.last().claims.single().verdict)
        compose.onNode(hasText("已处理") and hasAnyAncestor(hasTestTag("person-pending"))).performClick()
        compose.onNodeWithText("已接受").assertExists()
        compose.onNodeWithText("重新待确认").performClick()
        compose.onNodeWithText("返回待确认").performClick()
        compose.onNode(hasText("忽略") and hasAnyAncestor(hasTestTag("person-pending"))).performClick()
        val saved=AnalysisStore(compose.activity).person(id)
        Assert.assertEquals("已忽略",saved.reports.last().claims.single().verdict)
        Assert.assertEquals("新版原文",saved.reports.last().markdown)
        compose.onNode(hasText("已处理") and hasAnyAncestor(hasTestTag("person-pending"))).performClick()
        savePreview("pending-processed")
    }
    private fun savePreview(name:String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val dir=java.io.File("/tmp/lingxi-analysis-preview").also { it.mkdirs() }
            java.io.File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test fun settingsWithoutConfiguredModelStaysReadable() {
        show()
        compose.onNodeWithTag("shell-tab-4").performClick()
        compose.onNodeWithText("分析技能").performClick()
        compose.onNodeWithText("心理学视角").assertExists()
        compose.onNodeWithText("情感关系").assertExists()
        compose.onNodeWithText("社交商务").assertExists()
    }
}
