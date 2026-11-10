package app.resound.ui.transcript

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.transcribe.*
import app.resound.ui.preview.FakeTranscribeSettings
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class TranscriptPeriodUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun collapseHidesBothRecordingsAndPlaybackFocusReopensTheirPeriod() {
        val date = LocalDate.now()
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun segment(id: Long, minute: Long, text: String): SegmentTranscript {
            val at = start + minute * 60_000
            return SegmentTranscript(id, at, at + 1000, TranscriptStatus.Done,
                listOf(TranscriptLine(id, at, at + 1000, text, null, segmentId = id)), audioAvailable = false)
        }
        val repo = object : TranscriptRepository {
            override fun day(date: LocalDate) = MutableStateFlow(listOf(segment(2, 20, "虚构原话乙"), segment(1, 10, "虚构原话甲")))
            override val queue = MutableStateFlow(TranscribeQueueState(0, 0, false, null, null, null, 0, 0))
            override fun runNow() {}
            override fun retryFailed() {}
        }
        val settings = FakeTranscribeSettings().apply { saveKey("synthetic-only") }
        PlayerNav.transcriptFocus = null
        compose.setContent { ResoundTheme(ThemeMode.Light) { TranscriptScreen(repo, settings, 0.dp) } }
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("虚构原话乙", substring = true))
        compose.onNodeWithText("虚构原话乙", substring = true).assertExists()
        list.performScrollToNode(hasText("00–02"))
        compose.onNodeWithText("00–02").performClick()
        compose.onNodeWithText("虚构原话甲", substring = true).assertDoesNotExist()
        compose.onNodeWithText("虚构原话乙", substring = true).assertDoesNotExist()
        compose.onNodeWithText("保存原音").assertDoesNotExist()
        compose.runOnIdle { PlayerNav.transcriptFocus = date to 2L }
        compose.waitForIdle()
        compose.onNodeWithText("虚构原话乙", substring = true).assertExists()
        compose.onNodeWithText("虚构原话甲", substring = true).assertExists()
    }
    @Test fun onlyOccupiedPeriodsAppearAndFocusSkipsMissingEarlierPeriods() {
        val date = LocalDate.now()
        val start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun segment(id: Long, minute: Long, text: String): SegmentTranscript {
            val at = start + minute * 60_000
            return SegmentTranscript(id, at, at + 1000, TranscriptStatus.Done,
                listOf(TranscriptLine(id, at, at + 1000, text, null, segmentId = id)), audioAvailable = false)
        }
        val entries = MutableStateFlow(listOf(segment(3, 490, "八点十分的虚构原话"), segment(4, 150, "两点半的虚构原话")))
        val repo = object : TranscriptRepository {
            override fun day(date: LocalDate) = entries
            override val queue = MutableStateFlow(TranscribeQueueState(0, 0, false, null, null, null, 0, 0))
            override fun runNow() {}
            override fun retryFailed() {}
        }
        val settings = FakeTranscribeSettings().apply { saveKey("synthetic-only") }
        PlayerNav.transcriptFocus = null
        compose.setContent { ResoundTheme(ThemeMode.Light) { TranscriptScreen(repo, settings, 0.dp) } }
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("08–10"))
        compose.onNodeWithText("08–10").assertExists()
        compose.onNodeWithText("00–02").assertDoesNotExist()
        compose.onNodeWithText("04–06").assertDoesNotExist()
        compose.onNodeWithText("22–24").assertDoesNotExist()
        compose.runOnIdle { PlayerNav.transcriptFocus = date to 4L }
        compose.waitForIdle()
        compose.onNodeWithText("两点半的虚构原话", substring = true).assertExists()
        compose.onNodeWithText("02–04").assertExists()
        compose.runOnIdle { entries.value = emptyList() }
        compose.waitForIdle()
        compose.onNodeWithText("02–04").assertDoesNotExist()
        compose.onNodeWithText("08–10").assertDoesNotExist()
    }

    @Test fun settingsStartCollapsedWithoutCredentialsAndCloseAfterPageOrBackgroundExit() {
        val active = androidx.compose.runtime.mutableStateOf(true)
        val repo = object : TranscriptRepository {
            override fun day(date: LocalDate) = MutableStateFlow(emptyList<SegmentTranscript>())
            override val queue = MutableStateFlow(TranscribeQueueState(0, 0, false, null, null, null, 0, 0))
            override fun runNow() { error("Settings visibility must not start transcription") }
            override fun retryFailed() {}
        }
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            androidx.compose.runtime.CompositionLocalProvider(app.resound.ui.LocalShellPageActive provides active.value) {
                TranscriptScreen(repo, FakeTranscribeSettings(), 0.dp)
            }
        } }
        compose.onNodeWithText("模型和转写设置").assertExists().performClick()
        compose.onNodeWithText("收起设置").assertExists()
        compose.runOnIdle { active.value = false }
        compose.waitForIdle()
        compose.runOnIdle { active.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("模型和转写设置").assertExists().performClick()
        compose.onNodeWithText("收起设置").assertExists()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithText("模型和转写设置").assertExists()
        compose.onNodeWithText("收起设置").assertDoesNotExist()
    }

}
