package app.resound.ui

import android.app.Application
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.capture.CaptureStatus
import app.resound.ui.settings.SettingsInput
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import app.resound.ui.today.CaptureQuickControls
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class CaptureHoldAndKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private class Capture : CaptureController {
        override val status = MutableStateFlow(CaptureStatus(CaptureState.Listening, 0))
        var stopped = 0
        var pauses = mutableListOf<Int>()
        override fun start() { status.value = CaptureStatus(CaptureState.Listening, 0) }
        override fun stop() { stopped++; status.value = CaptureStatus(CaptureState.Stopped, 0) }
        override fun pause(minutes: Int) { pauses += minutes; status.value = CaptureStatus(CaptureState.Paused, 0, pausedUntil = 60_000L * minutes) }
        override fun resume() { status.value = CaptureStatus(CaptureState.Listening, 0) }
    }
    private fun show(capture: Capture) {
        compose.setContent { ResoundTheme(ThemeMode.Light) { CaptureQuickControls(capture) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
    private fun press() {
        val point = compose.onNodeWithContentDescription("长按1.5秒关闭聆听").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(point) }
    }
    @Test fun tapAndEarlyReleaseCannotStopListening() {
        val capture = Capture(); show(capture)
        compose.onNodeWithContentDescription("长按1.5秒关闭聆听").performTouchInput { click() }
        press(); compose.mainClock.advanceTimeBy(1400)
        compose.runOnIdle { assertEquals(0, capture.stopped) }
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { assertEquals(CaptureState.Listening, capture.status.value.state) }
    }
    @Test fun holdStopsOnceAndReleaseDoesNotRestart() {
        val capture = Capture(); show(capture)
        press(); compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { assertEquals(1, capture.stopped) }
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { assertEquals(1, capture.stopped); assertEquals(CaptureState.Stopped, capture.status.value.state) }
    }
    @Test fun cancelledHoldDoesNotAccumulateIntoTheNextPress() {
        val capture = Capture(); show(capture)
        press(); compose.mainClock.advanceTimeBy(1000)
        compose.onRoot().performTouchInput { cancel() }
        press(); compose.mainClock.advanceTimeBy(600)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { assertEquals(0, capture.stopped) }
    }
    @Test fun adjacentTapChoosesDurationEvenWhenAlreadyPaused() {
        val capture = Capture(); show(capture)
        compose.onNodeWithContentDescription("选择暂停时长").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("20 分钟").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("暂停 20 分钟").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertEquals(listOf(20), capture.pauses); assertEquals(0, capture.stopped) }
        compose.onNodeWithContentDescription("选择暂停时长").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("现在恢复").assertExists()
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun secretIsMaskedButRequestsNormalTextInput() {
        var captured: EditorInfo? = null
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            InterceptPlatformTextInput(interceptor = { request, _ ->
                val info = EditorInfo()
                request.createInputConnection(info)
                captured = info
                awaitCancellation()
            }) { SettingsInput("fictional-key", {}, "Key", secret = true) }
        } }
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.runOnIdle {
            val info = requireNotNull(captured) { "text input session did not start" }
            assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
            val variation = info.inputType and InputType.TYPE_MASK_VARIATION
            assertNotEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, variation)
            assertNotEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, variation)
            assertNotEquals(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, variation)
        }
    }
}
