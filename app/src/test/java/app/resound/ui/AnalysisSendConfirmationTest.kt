package app.resound.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import app.resound.ui.analysis.AnalysisSendConfirmation
import app.resound.ui.analysis.AnalysisSendConfirmationSetting
import app.resound.ui.theme.ResoundTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application=Application::class,sdk=[35],qualifiers="w320dp-h640dp-xhdpi")
class AnalysisSendConfirmationTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val prefs get()=compose.activity.getSharedPreferences("ui",Context.MODE_PRIVATE)
    @Before fun reset(){prefs.edit().remove("analysis-send-confirmation").commit()}

    @Test fun cancellationDoesNotSavePreferenceAndNarrowLargeTextFooterFits() {
        var open by mutableStateOf(true)
        var calls=0
        compose.setContent {
            ResoundTheme(analysisMode=true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.3f)) {
                    if(open) AnalysisSendConfirmation("确认发送", "发送并分析", { calls++;open=false }, { open=false }) { Text("发送当前人物已选材料。") }
                }
            }
        }
        compose.onNodeWithText("发送并分析").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("不再提示").assertIsDisplayed()
        val dialog=compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        val actions=compose.onNodeWithTag("analysis-send-confirmation-actions").fetchSemanticsNode().boundsInRoot
        val check=compose.onNodeWithTag("analysis-send-do-not-remind").fetchSemanticsNode().boundsInRoot
        assertTrue(actions.right<=dialog.right && actions.left>=dialog.left)
        assertTrue(actions.top>=check.bottom && actions.bottom<=dialog.bottom)
        compose.onNodeWithTag("analysis-send-do-not-remind").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0,calls)
        assertTrue(prefs.getBoolean("analysis-send-confirmation",true))
        compose.runOnIdle { open=true }
        compose.onNodeWithText("确认发送").assertIsDisplayed()
    }

    @Test fun confirmedSkipPersistsAcrossEntryPointsAndSettingsRestoresReminder() {
        var entry by mutableStateOf<String?>("报告")
        val sent=mutableListOf<String>()
        compose.setContent {
            ResoundTheme(analysisMode=true) {
                Column {
                    AnalysisSendConfirmationSetting()
                    TextButton(onClick={entry="问答"}) { Text("打开问答") }
                    entry?.let { current ->
                        key(current) {
                            AnalysisSendConfirmation("发送$current", "确认发送", { sent+=current;entry=null }, { entry=null }) { Text("当前人物的材料") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("analysis-send-do-not-remind").performClick()
        compose.onNodeWithText("确认发送").performClick()
        compose.waitForIdle()
        assertEquals(listOf("报告"),sent)
        assertFalse(prefs.getBoolean("analysis-send-confirmation",true))
        compose.onNodeWithText("打开问答").performClick()
        compose.waitForIdle()
        assertEquals(listOf("报告","问答"),sent)
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("analysis-send-confirmation-setting").performClick()
        assertTrue(prefs.getBoolean("analysis-send-confirmation",false))
        compose.onNodeWithText("打开问答").performClick()
        compose.onNodeWithText("发送问答").assertIsDisplayed()
        assertEquals(2,sent.size)
    }

    @Test fun skippedReminderWaitsForEnabledAndUsesLatestCallbackOnlyOnce() {
        prefs.edit().putBoolean("analysis-send-confirmation",false).commit()
        var enabled by mutableStateOf(false)
        var current by mutableStateOf("旧请求")
        val sent=mutableListOf<String>()
        compose.setContent {
            ResoundTheme(analysisMode=true) {
                val selected=current
                AnalysisSendConfirmation("发送", "确认", {sent+=selected}, {}, enabled) { Text("材料") }
            }
        }
        compose.waitForIdle()
        assertTrue(sent.isEmpty())
        compose.runOnIdle { current="当前请求";enabled=true }
        compose.waitForIdle()
        assertEquals(listOf("当前请求"),sent)
        compose.runOnIdle { current="再次重绘";enabled=false }
        compose.waitForIdle()
        compose.runOnIdle { enabled=true }
        compose.waitForIdle()
        assertEquals(listOf("当前请求"),sent)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test @Config(qualifiers="w320dp-h568dp-xhdpi")
    fun longScopeCanBeReadWithoutMovingSendActionsOffScreen() {
        compose.setContent {
            ResoundTheme(analysisMode=true) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.3f)) {
                    AnalysisSendConfirmation("生成沟通报告", "发送并分析", {}, {}) {
                        Column {
                            Text("当前模型：虚构测试模型。地址：https://example.invalid/v1/chat/completions。" +
                                "本次发送当前人物的基础回答、已选文字材料、关联事件及核对反馈。" +
                                "尚未添加原文材料时，只依据已填档案分析，资料不足的部分保留未知。" +
                                "你可以先补充材料或编辑档案，再决定是否发送。")
                            Text("材料范围说明结束")
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("材料范围说明结束").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("发送并分析").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithTag("analysis-send-do-not-remind").assertIsDisplayed()
    }
}
