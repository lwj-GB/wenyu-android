package app.resound.ui.settings

import android.app.Application
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.ui.LocalShellPageSelected
import app.resound.ui.LocalShellPageActive
import app.resound.ui.ShellNav
import app.resound.ui.preview.*
import app.resound.ui.theme.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @After fun clearRequest() { ShellNav.settingsPage.value = null }

    @Test fun navigationResetsOnlyAfterLeavingTabAndPreservesTemporaryBackground() {
        val active = mutableStateOf(true)
        val capture = FakeCaptureController()
        val transcribe = FakeTranscribeSettings()
        val digest = FakeDigestSettings()
        ShellNav.settingsPage.value = SettingsPage.Model
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            CompositionLocalProvider(LocalShellPageSelected provides active.value, LocalShellPageActive provides active.value) {
                SettingsScreen(capture,transcribe,digest,ThemeMode.Light,{},0.dp) { page, _ ->
                    val inner = LocalSettingsInner.current
                    Text("测试子页：${page.name}")
                    LaunchedEffect(page) { if(page==SettingsPage.Skills) inner(InnerPage("技能详情","技能") {}) }
                }
            }
        } }
        compose.onNodeWithText("测试子页：Model").assertExists()
        compose.runOnIdle { active.value=false }
        compose.waitForIdle()
        compose.runOnIdle { active.value=true }
        compose.waitForIdle()
        compose.onNodeWithText("测试子页：Model").assertDoesNotExist()
        compose.onNodeWithText("设置",useUnmergedTree=true).assertExists()
        compose.runOnIdle { ShellNav.settingsPage.value=SettingsPage.Skills }
        compose.waitForIdle()
        compose.onNodeWithText("技能详情").assertExists()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithText("测试子页：Skills").assertExists()
        compose.onNodeWithText("技能详情").assertExists()
    }
    @Test fun globalSkillGroupsRouteDirectlyAcrossModes() {
        val analysis = mutableStateOf(false)
        val theme = mutableStateOf(ThemeMode.Light)
        val capture=FakeCaptureController(); val transcribe=FakeTranscribeSettings();val digest=FakeDigestSettings()
        compose.setContent { ResoundTheme(theme.value,analysisMode=analysis.value) {
            val shared: @Composable () -> Unit = {
                SettingsScreen(capture,transcribe,digest,theme.value,{theme.value=it},0.dp) { page,bottom ->
                    Text("全局子页：${page.name}")
                }
            }
            if(analysis.value) app.resound.ui.analysis.AnalysisModeScreen(themeMode=theme.value,onThemeMode={theme.value=it},settingsContent=shared,onExit={analysis.value=false})
            else shared()
        } }
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        java.io.File("/tmp/wenyu-global-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithText("个人智能").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithTag("personal-coming-soon").performScrollTo().assertIsNotEnabled().assertHasNoClickAction()
        compose.onNodeWithTag("analysis-coming-soon").performScrollTo().assertIsNotEnabled().assertHasNoClickAction()
        compose.onNodeWithText("整理技能").performScrollTo().performClick()
        compose.onNodeWithText("全局子页：Skills").assertExists()
        compose.runOnIdle { analysis.value=true }
        // Global requests are handled by the same SettingsScreen in the analysis pager.
        compose.onAllNodesWithText("设置").onLast().performClick()
        compose.onNodeWithText("分析技能").performScrollTo().performClick()
        compose.onNodeWithText("全局子页：AnalysisIntelligence").assertExists()
        compose.runOnIdle { ShellNav.settingsPage.value=SettingsPage.Backup }
        compose.onNodeWithText("全局子页：Backup").assertExists()
        compose.runOnIdle { ShellNav.settingsPage.value=SettingsPage.Capture }
        compose.onNodeWithText("全局子页：Capture").assertExists()
    }

    @Test fun modelSurvivesCancelledPagerDragAndTemporarySystemWindow() {
        val capture=FakeCaptureController(); val transcribe=FakeTranscribeSettings(); val digest=FakeDigestSettings()
        ShellNav.settingsPage.value=SettingsPage.Model
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            app.resound.ui.AppShell(listOf(
                "今天" to { Text("今天内容") }, "问问" to { Text("问问内容") },
                "记忆" to { Text("记忆内容") }, "待办" to { Text("待办内容") },
                "设置" to {
                    SettingsScreen(capture,transcribe,digest,ThemeMode.Light,{},0.dp) { _,_ ->
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                            repeat(20) { Text("模型设置第$it 项",modifier=androidx.compose.ui.Modifier.height(80.dp)) }
                        }
                    }
                }
            ),4 to 1L)
        } }
        compose.onNodeWithText("模型设置第0 项").assertExists()
        val surface=compose.onNodeWithTag("shell-content-4")
        surface.performTouchInput { down(center);moveBy(androidx.compose.ui.geometry.Offset(80f,0f),200) }
        compose.waitForIdle()
        try { compose.onNodeWithText("模型设置第0 项").assertExists() }
        finally { surface.performTouchInput { up() } }
        compose.waitForIdle()
        compose.onNodeWithText("模型设置第12 项").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithText("模型设置第12 项").assertExists()
        compose.onNodeWithText("今天").performClick()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("模型设置第0 项").assertDoesNotExist()
    }

}
