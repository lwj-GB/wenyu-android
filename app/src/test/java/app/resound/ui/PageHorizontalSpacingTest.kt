package app.resound.ui

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.ask.AskMessage
import app.resound.ask.AskRepository
import app.resound.ask.AskRole
import app.resound.ui.ask.AskScreen
import app.resound.ui.items.MemoryScreen
import app.resound.ui.preview.FakeAskRepository
import app.resound.ui.preview.FakeMemoryRepository
import app.resound.ui.theme.ResoundTheme
import kotlinx.coroutines.flow.flowOf
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
class PageHorizontalSpacingTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun memoryCalendarStaysCenteredAndHourRailKeepsClearOfEventCards() {
        compose.setContent { ResoundTheme { MemoryScreen(FakeMemoryRepository(), null, null, null, {}, 0.dp) } }
        val page = compose.onNodeWithTag("memory-page").fetchSemanticsNode().boundsInRoot
        val calendar = compose.onNodeWithTag("memory-calendar").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("memory-heading").fetchSemanticsNode().boundsInRoot
        assertEquals(calendar.left - page.left, page.right - calendar.right, 1f)
        assertEquals(title.left - page.left, page.right - title.right, 1f)
        compose.onNodeWithTag("memory-list").performScrollToKey("ev1")
        val card = compose.onNodeWithTag("memory-event-1").fetchSemanticsNode().boundsInRoot
        val rail = compose.onNodeWithTag("memory-hour-index").fetchSemanticsNode().boundsInRoot
        assertTrue("hour rail must not cover the timeline card", rail.left > card.right)
        assertTrue("hour rail stays inside the page", rail.right <= page.right)
        assertTrue("hour rail stays beside the visible timeline", rail.top <= card.top + 1f)
    }

    @Test @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun narrowAskAnswerHasEqualSideMarginsAndLongTextStaysInside() {
        val text = "虚构回答：" + "这里是一段需要在窄屏正常换行并保留右侧间距的文字。".repeat(4)
        val repo = object : AskRepository by FakeAskRepository() {
            override val messages = flowOf(listOf(AskMessage(2, AskRole.Assistant, text, emptyList(), 0, false)))
        }
        compose.setContent { ResoundTheme { AskScreen(repo, 0.dp) } }
        val list = compose.onNodeWithTag("ask-message-list").fetchSemanticsNode().boundsInRoot
        val answer = compose.onNodeWithTag("ask-answer-2").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        assertEquals(answer.left - list.left, list.right - answer.right, 1f)
        assertTrue("answer retains both page margins", answer.left > list.left && answer.right < list.right)
        assertTrue("long answer stays inside the card", body.left >= answer.left && body.right <= answer.right)
    }
}
