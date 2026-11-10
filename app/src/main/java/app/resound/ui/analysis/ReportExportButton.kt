package app.resound.ui.analysis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.resound.analysis.*
import app.resound.ui.theme.*
import kotlinx.coroutines.*

/** The filename and body always refer to the same captured report, even if selection later changes. */
@Composable internal fun ReportExportButton(store:AnalysisStore,person:AnalysisPerson,report:AnalysisReport) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var pending by remember{mutableStateOf<Pair<String,String>?>(null)}
    var message by remember(person.id,report.id){mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    val currentPerson by rememberUpdatedState(person.id)
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")){uri->
        val snapshot=pending;pending=null
        if(uri!=null&&snapshot!=null){busy=true;scope.launch{try{withContext(Dispatchers.IO){writeAnalysisDocument(context,uri,snapshot.second)};if(currentPerson==snapshot.first)message="Markdown已写入所选文件"}catch(e:CancellationException){throw e}catch(e:Exception){if(currentPerson==snapshot.first)message=e.message?:"导出失败"}finally{busy=false}}}
    }
    TextButton(enabled=!busy,onClick={runCatching{pending=person.id to store.reportMarkdown(person.id,report.id);val date=java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.CHINA).format(java.util.Date(report.at));create.launch(analysisDocumentName("${person.name}-${report.kind}-$date","md"))}.onFailure{pending=null;message=it.message}}){Text(if(busy)"正在写入…" else "导出Markdown")}
    message?.let{Text(it,style=RsType.subhead,color=Rs.colors.textSecondary)}
}
