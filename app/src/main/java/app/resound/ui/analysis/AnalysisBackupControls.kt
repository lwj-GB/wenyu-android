package app.resound.ui.analysis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.resound.analysis.*
import app.resound.ui.theme.*
import app.resound.ui.verify.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
internal fun AnalysisBackupControls(store:AnalysisStore,active:Boolean) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val isActive by rememberUpdatedState(active)
    var generation by remember{mutableIntStateOf(0)}
    var awaitingPicker by remember{mutableStateOf(false)}
    var export by remember{mutableStateOf<String?>(null)}
    var restore by remember{mutableStateOf<Pair<String,AnalysisBackupPreview>?>(null)}
    var busy by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(active){generation++;restore=null;export=null;message=null}
    LifecycleEventEffect(Lifecycle.Event.ON_STOP){if(!awaitingPicker){generation++;restore=null;export=null}}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        awaitingPicker=false
        val snapshot=export;export=null
        if(uri!=null&&snapshot!=null&&isActive){val token=generation;busy=true;scope.launch{try{withContext(Dispatchers.IO){writeAnalysisDocument(context,uri,snapshot)};if(isActive&&generation==token)message="文字备份已写入所选文件"}catch(e:CancellationException){throw e}catch(e:Exception){if(isActive&&generation==token)message=e.message?:"导出失败"}finally{busy=false}}}
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        awaitingPicker=false
        if(uri!=null&&isActive){val token=generation;busy=true;scope.launch{try{val snapshot=withContext(Dispatchers.IO){readAnalysisDocument(context,uri)};val preview=store.previewBackup(snapshot);if(isActive&&generation==token)restore=snapshot to preview}catch(e:CancellationException){throw e}catch(e:Exception){if(isActive&&generation==token)message=e.message?:"备份无效，未恢复"}finally{busy=false}}}
    }
    RsCard {
        Text("分析文字资料",style=RsType.headline,color=Rs.colors.text)
        Text("备份人物、材料、事件、报告及分析设置快照。不含原音、密钥、个人录音库或聊天。恢复只合并文字资料，不覆盖冲突，不改本机设置；原音路径不导出，历史来源引用不会变成可播放文件。",style=RsType.subhead,color=Rs.colors.textSecondary)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(enabled=!busy,onClick={runCatching{export=store.exportBackup();awaitingPicker=true;create.launch(analysisDocumentName("文鱼-分析文字资料","json"))}.onFailure{export=null;awaitingPicker=false;message=it.message}}){Text("导出备份")}
            TextButton(enabled=!busy,onClick={message=null;runCatching{awaitingPicker=true;open.launch(arrayOf("application/json","text/plain","application/octet-stream"))}.onFailure{awaitingPicker=false;message="无法打开文件选择器"}}){Text("恢复备份")}
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let{Text(it,style=RsType.subhead,color=Rs.colors.textSecondary)}
    }
    restore?.let{(text,preview)->AlertDialog(onDismissRequest={restore=null},title={Text("确认合并文字备份")},text={Text("人物${preview.people}个，事件${preview.events}条，材料${preview.materials}份，报告${preview.reports}份。引用提示${preview.audioReferences}处，不含原音文件。\n\n同ID内容冲突会拒绝整份恢复；相同资料不会重复。不覆盖现有资料，不恢复模型凭据、原音、个人库、聊天或设置。")},confirmButton={TextButton(onClick={if(isActive)runCatching{store.restoreBackup(text,confirmed=true);restore=null;message="文字资料已合并"}.onFailure{restore=null;message=it.message?:"合并失败，资料未改动"}}){Text("确认合并")}},dismissButton={TextButton(onClick={restore=null}){Text("取消")}})}
}
