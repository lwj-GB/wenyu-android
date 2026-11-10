package app.resound.ui.settings

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.ResoundTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35], qualifiers = "w320dp-h568dp-xhdpi")
class AboutPageLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun scenariosAndSourceLinksRemainReadableOnNarrowScreenWithLargerText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                ResoundTheme { AboutPage("0.1.1", 0.dp) }
            }
        }
        val page = compose.onNodeWithTag("about-page").fetchSemanticsNode().boundsInRoot
        listOf("文鱼是什么", "使用场景", "回顾一天，找回遗漏", "记住重要的人的细节",
            "沟通前，理清事情经过", "会谈后，整理需要跟进的事", "资料与隐私",
            "本项目主页 · 灵犀桥", "上游来源 · TaoSheng").forEach { title ->
            val node = compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("text must retain left and right page margins: $title", bounds.left > page.left && bounds.right < page.right)
        }
    }
}
