package app.resound.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class ShellNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Before fun resetNavigation() { ShellNav.request.value = null }
    @After fun clearNavigation() { ShellNav.request.value = null }

    @Test fun handledSettingsRequestDoesNotReplayAfterReturningFromAnalysis() {
        var analysis by androidx.compose.runtime.mutableStateOf(false)
        val labels = listOf("今天", "问问", "记忆", "待办", "设置", "原话")
        val tabs = labels.mapIndexed { route, label ->
            val content: @Composable () -> Unit = { Text("页面-$route") }
            label to content
        }
        compose.setContent { ResoundTheme {
            if (analysis) Text("分析模式") else AppShell(tabs)
        } }
        compose.runOnIdle { ShellNav.go(4) }
        compose.onNodeWithTag("shell-content-4").assertExists()
        compose.onNodeWithText("今天").performClick()
        compose.onNodeWithTag("shell-content-0").assertExists()
        compose.runOnIdle { analysis = true }
        compose.onNodeWithText("分析模式").assertExists()
        compose.runOnIdle { analysis = false }
        compose.onNodeWithTag("shell-content-0").assertExists()
        compose.runOnIdle { assertNull("已执行的导航请求不能在切回个人模式时重放", ShellNav.request.value) }
    }

    @Test fun stableRoutesKeepTodayBetweenRawSpeechAndMemory() {
        val order = shellRouteOrder(6)
        val today = order.indexOf(0)
        assertEquals(5, order[today - 1])
        assertEquals(2, order[today + 1])
        assertEquals(1, order[order.indexOf(2) + 1])
        assertEquals(setOf(0, 1, 2, 3, 4, 5), order.toSet())
    }

    @Test fun askAndMemoryKeepSystemFontScaleAndSavedStateWithoutPreloadingQueries() {
        val starts = IntArray(6)
        val streams = List(6) { route -> flow { starts[route]++; emit(route); awaitCancellation() } }
        val labels = listOf("今天", "问问", "记忆", "待办", "设置", "原话")
        val tabs = labels.mapIndexed { route, label ->
            val content: @Composable () -> Unit = {
            val current by streams[route].collectWhenPageActive(initial = -1)
            var count by rememberSaveable { mutableIntStateOf(0) }
            val fontScale = LocalDensity.current.fontScale
            Column {
                Text("route-$route:$current@$fontScale", Modifier.testTag("route-$route"))
                Text("count-$route:$count", Modifier.clickable { count++ })
            }
            }
            label to content
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.4f)) {
                ResoundTheme(ThemeMode.Light) { AppShell(tabs) }
            }
        }
        compose.onNodeWithTag("route-0").assertTextEquals("route-0:0@1.4")
        compose.runOnIdle { assertEquals(0, starts[2]); assertEquals(0, starts[5]) }
        compose.onNodeWithText("记忆").performClick()
        compose.onNodeWithTag("route-2").assertTextEquals("route-2:2@1.4")
        compose.runOnIdle { assertEquals(0, starts[1]) }
        compose.onNodeWithText("count-2:0").performClick()
        compose.onNodeWithText("问问").performClick()
        compose.onNodeWithTag("route-1").assertTextEquals("route-1:1@1.4")
        compose.onNodeWithText("记忆").performClick()
        compose.onNodeWithTag("route-2").assertTextEquals("route-2:2@1.4")
        compose.onNodeWithText("count-2:1").assertExists()
    }
}
