package app.resound.ui.analysis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.resound.ui.theme.Rs
import app.resound.skills.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.analysis.*
import app.resound.digest.preset
import app.resound.ui.theme.RsType

@Composable
fun AnalysisIntelligencePage(store:AnalysisStore,state:AnalysisState,bottomPadding:Dp,active:Boolean) {
    val repo=remember(store){store.intelligence}
    val selected by repo.selectedPeople.collectAsState()
    val authorised by repo.authorised.collectAsState()
    val history by repo.runHistory.collectAsState()
    var personMenu by remember{mutableStateOf(false)}
    var optionsOpen by remember{mutableStateOf(false)}
    var historyOpen by remember{mutableStateOf(false)}
    var confirm by remember{mutableStateOf<String?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val notificationPermission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){granted->
        if(!granted)error="通知权限未开启，待核对问题仍保存在人物页"
    }
    LaunchedEffect(active){personMenu=false;historyOpen=false;confirm=null;optionsOpen=false}
    val uiRepo=remember(repo){object:SkillRepository by repo {
        private fun ready():Boolean {if(repo.selectedPeople.value.isNotEmpty())return true;error="先选择人物并确认范围，再运行技能";personMenu=true;return false}
        override fun generate(skillId:String,date:LocalDate){if(ready())repo.generate(skillId,date)}
        override suspend fun trial(skillId:String,date:LocalDate,promptOverride:String?):SkillRunResult {if(!ready())return SkillRunResult.Failed("先选择人物并确认范围，再运行技能");return try{repo.trial(skillId,date,promptOverride)}catch(e:CancellationException){throw e}catch(e:Exception){SkillRunResult.Failed(e.message?:"试跑失败")}}
        override suspend fun runNow(skillId:String,date:LocalDate):SkillRunResult {if(!ready())return SkillRunResult.Failed("先选择人物并确认范围，再运行技能");return repo.runNow(skillId,date)}
    }}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            val person=state.people.firstOrNull{it.id in selected}
            Box {
                Row(Modifier.clip(CircleShape).clickable{personMenu=true}.semantics{contentDescription="选择分析人物"}.padding(end=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(40.dp).background(Rs.colors.accentSoft,CircleShape),contentAlignment=Alignment.Center){Text(person?.name?.take(1)?:"＋",style=RsType.headline,color=Rs.colors.accent)}
                    Text(person?.name?:"选择人物",style=RsType.body,color=Rs.colors.text)
                }
                DropdownMenu(personMenu,{personMenu=false}){
                    if(state.people.isEmpty())DropdownMenuItem(text={Text("先在人物页新建档案")},onClick={},enabled=false)
                    state.people.forEach{p->DropdownMenuItem(text={Text(p.name)},onClick={confirm=p.id;personMenu=false})}
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick={optionsOpen=true}){Text("运行设置",style=RsType.body)}
        }
        error?.let{Text(it,Modifier.padding(horizontal=16.dp),style=RsType.subhead,color=Rs.colors.critical)}
        DailyQuestionsSettings(repo){error=it}
        val presets=if(AppGraph.isLlmProvidersReady)AppGraph.llmProviders.saved.collectAsState().value.map{it.preset()}else emptyList()
        Box(Modifier.weight(1f)){app.resound.ui.skills.SkillsPage(uiRepo,presets,bottomPadding,corePage={_,_->},includeAskCore=false,coreAsSkill=true,allowPush=false)}
    }
    if(optionsOpen)AlertDialog(onDismissRequest={optionsOpen=false},title={Text("分析运行设置")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        val person=state.people.firstOrNull{it.id in selected}
        Text(person?.let{"当前人物：${it.name}"}?:"尚未选择人物；可先编辑技能，运行前需确认范围。",style=RsType.body)
        person?.let{p->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(p.id in authorised,{on->runCatching{repo.authorise(p.id,on)}.onSuccess{
            if(on && android.os.Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }.onFailure{error=it.message}});Text("允许该人物按技能条件自动分析",Modifier.weight(1f),style=RsType.body)}}
        Text("自动检查约每15分钟一次，受系统后台限制。仅使用已确认人物的档案与技能所选日期资料，不发送原音或个人全天采集。",style=RsType.subhead)
        TextButton(onClick={optionsOpen=false;historyOpen=true}){Text("运行记录（${history.size}）")}
    }},confirmButton={TextButton(onClick={optionsOpen=false}){Text("完成")}})

    confirm?.let { id ->
        val person=state.people.firstOrNull { it.id==id }
        AnalysisSendConfirmation(
            title="使用${person?.name.orEmpty()}的材料",confirmLabel="确认范围",onDismiss={confirm=null},
            enabled=person!=null,onConfirm={runCatching { repo.selectPerson(id);error=null;confirm=null }.onFailure { error=it.message;confirm=null }},
        ) { Text("技能运行会发送该人物已保存的档案、所选日期范围内的文字材料和关联事件。每项技能使用其所选模型，未指定时沿用当前整理模型。不会自动获取其他人物或个人录音库。") }
    }
    if(historyOpen)AlertDialog(onDismissRequest={historyOpen=false},title={Text("分析运行记录")},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())){history.asReversed().forEach{Text(it,style=RsType.subhead)}}},confirmButton={TextButton(onClick={historyOpen=false}){Text("关闭")}})
}
