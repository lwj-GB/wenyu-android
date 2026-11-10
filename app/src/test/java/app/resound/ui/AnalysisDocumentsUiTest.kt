package app.resound.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import app.resound.analysis.*
import app.resound.ui.analysis.*
import app.resound.ui.theme.ResoundTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application=Application::class,sdk=[35],qualifiers="w393dp-h852dp-xhdpi")
class AnalysisDocumentsUiTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Before fun cleanStore(){compose.activity.filesDir.resolve("analysis-mode.json").delete()}
    @After fun clearStore(){compose.activity.filesDir.resolve("analysis-mode.json").delete()}
    private fun render(uri:Uri,content:@Composable ()->Unit){
        val registry=object:ActivityResultRegistry(){
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){
                dispatchResult(requestCode,android.app.Activity.RESULT_OK,Intent().setData(uri))
            }
        }
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=registry}
        compose.setContent{CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner){ResoundTheme(analysisMode=true){content()}}}
    }
    @Test fun backupExportWritesAndRestoreRequiresExplicitConfirmation(){
        val store=AnalysisStore(compose.activity)
        store.createPerson(mapOf("q1" to "虚构备份人物"))
        val target=File(compose.activity.cacheDir,"analysis-documents-probe.json");target.delete()
        render(Uri.fromFile(target)){AnalysisBackupControls(store,true)}
        compose.onNodeWithText("导出备份").performClick()
        compose.waitForIdle()
        compose.waitUntil(5000){target.exists()&&target.length()>0}
        Assert.assertTrue(target.readText().contains("虚构备份人物"))
        // Replace the selected file with a second valid backup. Nothing changes before confirmation.
        val original=target.readText()
        val marker="虚构导入人物"
        target.writeText(original.replace("虚构备份人物",marker).replace(store.state.value.people.single().id,"fixture-import-id"))
        compose.onNodeWithText("恢复备份").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithText("确认合并文字备份").fetchSemanticsNodes().isNotEmpty()}
        Assert.assertEquals(1,store.state.value.people.size)
        compose.onNodeWithText("确认合并").performClick()
        Assert.assertEquals(2,store.state.value.people.size)
        Assert.assertTrue(store.state.value.people.any{it.name==marker})
        target.delete()
    }
    @Test fun reportMarkdownExportWritesCapturedReportWithSource(){
        val store=AnalysisStore(compose.activity)
        val id=store.createPerson(mapOf("q1" to "虚构导出人物","q7" to "喜欢清淡口味"))
        val report=AnalysisReport("report-export","兴趣偏好","资料显示喜欢清淡口味[q7]",listOf(AnalysisClaim("claim","偏好清淡",listOf("q7"))),1,System.currentTimeMillis())
        store.append(id,report)
        val target=File(compose.activity.cacheDir,"analysis-report-probe.md");target.delete()
        render(Uri.fromFile(target)){ReportExportButton(store,store.person(id),report)}
        compose.onNodeWithText("导出Markdown").performClick()
        compose.waitForIdle()
        compose.waitUntil(5000){target.exists()&&target.length()>0}
        val result=target.readText()
        Assert.assertTrue(result.contains("喜欢清淡口味"))
        Assert.assertTrue(result.contains("虚构导出人物"))
        target.delete()
    }
}
