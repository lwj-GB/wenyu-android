package app.resound.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.ui.analysisrecording.*
import app.resound.ui.theme.ResoundTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application=Application::class,sdk=[35],qualifiers="w393dp-h852dp-xhdpi")
class AnalysisRecordingPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun recordingHeaderDisplaysStoppedPausedAndRecordingStates() {
        // Static presentation states only. The native test clock does not deliver snapshot
        // recomposition while manually advanced; microphone sampling is a separate device check.
        compose.mainClock.autoAdvance=false
        compose.setContent{ResoundTheme(analysisMode=true){androidx.compose.foundation.layout.Column {
            RecordingWaveHeader("虚构未录音",0f,false,false,false)
            RecordingWaveHeader("虚构暂停",0f,true,false,false)
            RecordingWaveHeader("虚构录音",0f,true,true,false)
        }}}
        compose.onNodeWithText("虚构未录音").assertExists()
        compose.onNodeWithText("虚构暂停").assertExists()
        compose.onNodeWithText("虚构录音").assertExists()
        compose.onNodeWithText("未录音").assertExists()
        compose.onNodeWithText("已暂停").assertExists()
        compose.onNodeWithText("录音中").assertExists()
        compose.onNodeWithText("录音材料").assertDoesNotExist()
    }
    @Test fun transcriptionShowsRealProgressAndSavedTextBeforeExplicitAnalysisAction() {
        var analysed=false
        compose.mainClock.autoAdvance=false
        compose.setContent{ResoundTheme(analysisMode=true){androidx.compose.foundation.layout.Column {
            TranscriptionResult(SingleAudioTranscriptionState(running=true),2500,null)
            TranscriptionResult(SingleAudioTranscriptionState(text="虚构转写：周末一起读书",completedMillis=4200),0,{analysed=true})
        }}}
        compose.onNodeWithText("正在转写 · 已用2秒").assertExists()
        compose.onNodeWithText("转写已保存 · 耗时4秒").assertExists()
        compose.onNodeWithText("虚构转写：周末一起读书").assertExists()
        Assert.assertFalse(analysed)
        compose.onNodeWithText("现在分析").performClick()
        Assert.assertTrue(analysed)
    }
    @Test fun memoryChoicePresentsEventTitleMetadataAndPreviewWithoutInternalSourceWarning() {
        var preview by mutableStateOf(false)
        val item=SelectedPersonalTranscript(1,1000,2000,"个人记忆整理（AI整理，需核对）\n后台来源原文","personal-memory","讨论读书","商量周末去图书馆","闲聊",listOf("约定周六出发"),listOf("约定"))
        compose.setContent{ResoundTheme(analysisMode=true){PersonalMemoryChoiceBody(item,preview)}}
        compose.onNodeWithText("讨论读书").assertExists()
        compose.onNodeWithText("商量周末去图书馆").assertExists()
        compose.onNodeWithText("闲聊",substring=true).assertExists()
        compose.onNodeWithText("个人记忆整理",substring=true).assertDoesNotExist()
        compose.onNodeWithText("AI整理，需核对",substring=true).assertDoesNotExist()
        compose.onNodeWithText("约定周六出发").assertDoesNotExist()
        compose.runOnIdle{preview=true}
        compose.onNodeWithText("约定周六出发").assertExists()
        compose.onNodeWithText("后台来源原文",substring=true).assertDoesNotExist()
        Assert.assertTrue(item.text.contains("后台来源原文"))
    }
}
