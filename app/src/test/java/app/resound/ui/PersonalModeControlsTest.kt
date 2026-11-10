package app.resound.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.resound.PipelineProgress
import app.resound.digest.ItemStatus
import app.resound.digest.MemoryEvent
import app.resound.digest.MemoryItem
import app.resound.digest.MemoryRepository
import app.resound.ui.items.TodoScreen
import app.resound.ui.preview.*
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import app.resound.ui.today.OverviewCard
import app.resound.ui.today.TodayScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h800dp-xhdpi")
class PersonalModeControlsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun reset() {
        ShellNav.request.value = null
        PipelineProgress.finish()
        compose.activity.getSharedPreferences("ui", 0).edit().putBoolean("startChecklistDismissed", true).commit()
    }

    @After fun clear() {
        ShellNav.request.value = null
        PipelineProgress.finish()
    }

    @Composable private fun LargeText(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
            ResoundTheme(ThemeMode.Light, content = content)
        }
    }

    @Test fun listeningCardKeepsRunAndModelActionsAtLargeFont() {
        var runs = 0
        var modelOpens = 0
        val memory = object : MemoryRepository by FakeMemoryRepository() {
            override fun runNow() { runs++ }
        }
        val capture = FakeCaptureController().apply { stop() }
        compose.setContent { LargeText {
            OverviewCard(capture, memory, FakeTranscriptRepository(), true, 60_000, 2, 1, false) { modelOpens++ }
        } }
        compose.onNodeWithText("没在聆听", substring = true).assertIsDisplayed()
        compose.onNodeWithText("录到人声").assertIsDisplayed()
        compose.onNodeWithText("件事").assertIsDisplayed()
        compose.onNodeWithText("待确认").assertIsDisplayed()
        compose.onNodeWithText("现在整理").assertIsDisplayed().performClick()
        compose.onNodeWithText("模型").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, runs); assertEquals(1, modelOpens) }
    }

    @Test fun missingModelKeepsRunVisibleAndOpensConfigurationWithoutSending() {
        var runs = 0
        var modelOpens = 0
        val memory = object : MemoryRepository by FakeMemoryRepository() {
            override fun runNow() { runs++ }
        }
        compose.setContent { LargeText {
            OverviewCard(FakeCaptureController().apply { stop() }, memory, FakeTranscriptRepository(), false,
                0, 0, 0, true) { modelOpens++ }
        } }
        compose.onNodeWithText("现在整理").assertIsDisplayed().performClick()
        compose.onNodeWithText("收起模型").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, runs); assertEquals(2, modelOpens) }
    }

    @Test fun allSixPersonalTabsReachTodoAndReturnToFullToday() {
        val capture = FakeCaptureController().apply { stop() }
        val transcripts = FakeTranscriptRepository()
        val memory = FakeMemoryRepository()
        val settings = FakeDigestSettings()
        val tabs: List<Pair<String, @Composable () -> Unit>> = listOf(
            "今天" to { TodayScreen(capture, FakeStatsRepository(), transcripts, memory, settings, ThemeMode.Light, {}, ShellBottomPadding) },
            "问问" to { Text("测试问问页") },
            "记忆" to { Text("测试记忆页") },
            "待办" to { TodoScreen(memory, ShellBottomPadding) },
            "设置" to { Text("测试设置页") },
            "原话" to { Text("测试原话页") },
        )
        compose.setContent { LargeText { AppShell(tabs) } }
        repeat(6) { compose.onNodeWithTag("shell-tab-$it").assertIsDisplayed().assertHasClickAction() }
        compose.onNodeWithTag("shell-tab-4").performClick()
        compose.onNode(hasText("待确认", substring = true) and hasAnyAncestor(hasTestTag("shell-content-3"))).assertIsDisplayed()
        compose.onNodeWithTag("shell-tab-1").performClick()
        compose.onNodeWithText("个人模式").assertIsDisplayed()
        compose.onNodeWithText("现在整理").assertIsDisplayed()
        compose.onNodeWithText("模型").assertIsDisplayed().performClick()
        compose.onNodeWithText("整理用的大模型").performScrollTo().assertIsDisplayed()
    }

    @Test fun todayKeepsSideBySidePendingTodoCardsWithAcceptAndIgnore() {
        val now = System.currentTimeMillis()
        val rows = MutableStateFlow(listOf(
            MemoryItem(101, 1, "待办", "我要做", "虚构待办甲", null, null, "我", ItemStatus.Pending, 2, at = now + 1),
            MemoryItem(102, 1, "待办", "我要做", "虚构待办乙", null, null, "我", ItemStatus.Pending, 1, at = now),
        ))
        val memory = object : MemoryRepository by FakeMemoryRepository() {
            override fun events(date: java.time.LocalDate) = flowOf(emptyList<MemoryEvent>())
            override fun pendingItems() = rows.map { list -> list.filter { it.status == ItemStatus.Pending } }
            override fun setItemStatus(id: Long, status: ItemStatus) {
                rows.value = rows.value.map { if (it.id == id) it.copy(status = status) else it }
            }
        }
        val capture = FakeCaptureController().apply { stop() }
        val transcripts = FakeTranscriptRepository()
        compose.setContent { LargeText {
            TodayScreen(capture, FakeStatsRepository(), transcripts, memory, FakeDigestSettings(), ThemeMode.Light, {}, 0.dp)
        } }
        compose.onAllNodes(hasScrollToIndexAction(), useUnmergedTree = true).onFirst().performScrollToNode(hasText("虚构待办甲"))
        val first = compose.onNodeWithText("虚构待办甲", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("虚构待办乙", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("原待办小卡仍横向并排", first.left < second.left && kotlin.math.abs(first.top - second.top) < 1f)
        compose.onNode(hasText("接受") and hasAnyAncestor(hasText("虚构待办甲"))).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ItemStatus.Accepted, rows.value.first().status) }
        compose.onNodeWithText("虚构待办甲").assertDoesNotExist()
        compose.onNode(hasText("忽略") and hasAnyAncestor(hasText("虚构待办乙"))).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ItemStatus.Ignored, rows.value.last().status) }
        compose.onNodeWithText("虚构待办乙").assertDoesNotExist()
    }
}
