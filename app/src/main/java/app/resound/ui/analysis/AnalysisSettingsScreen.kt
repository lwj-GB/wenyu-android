package app.resound.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.resound.AppGraph
import app.resound.analysis.*
import app.resound.ui.theme.*
import app.resound.ui.verify.*

/** Navigational state is deliberately transient; saved analysis settings stay in AnalysisStore. */
@Composable
internal fun AnalysisSettingsScreen(store:AnalysisStore,state:AnalysisState,active:Boolean) {
    var panel by remember {mutableStateOf<String?>(null)}
    var editing by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var kindsOpen by remember{mutableStateOf(false)}
    LaunchedEffect(active) { panel=null;editing=null;error=null;kindsOpen=false }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { panel=null;editing=null;error=null;kindsOpen=false }
    val context=LocalContext.current
    Column(Modifier.fillMaxSize().testTag("analysis-page-4")) {
        Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
            if(panel!=null) TextButton(onClick={panel=null}){Text("返回")}
            Text(when(panel){"skills"->"分析技能";"model"->"分析模型";"about"->"关于文鱼";else->"设置"},style=RsType.largeTitle,color=Rs.colors.text)
        }
        if(panel=="about") {
            val version=remember(context){runCatching{context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrNull()}
            Box(Modifier.weight(1f)){app.resound.ui.settings.AboutPage(version,0.dp)}
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            error?.let {Text(it,style=RsType.subhead,color=Rs.colors.critical)}
            when(panel) {
                "skills" -> {
                    Text("基础",style=RsType.section,color=Rs.colors.textSecondary)
                    RsCard(padding=PaddingValues(0.dp)) {
                        SettingRow("报告整理","补充写作要求；始终区分事实、推测与未知") {editing="report"}
                        HorizontalDivider(color=Rs.colors.separator,modifier=Modifier.padding(horizontal=18.dp))
                        SettingRow("人物问答","补充回答偏好；始终保留人物范围和出处") {editing="ask"}
                    }
                    Text("组合技能",style=RsType.section,color=Rs.colors.textSecondary)
                    Text("可以同时开启多项。全部关闭时仍按基础规则整理。保存不会立即发送材料。",style=RsType.subhead,color=Rs.colors.textSecondary)
                    RsCard(padding=PaddingValues(0.dp)) {
                        analysisStyles.entries.forEachIndexed { index,(name,description) ->
                            Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)){Text(name,style=RsType.headline,color=Rs.colors.text);Text(description,style=RsType.subhead,color=Rs.colors.textSecondary)}
                                Switch(name in state.skills,onCheckedChange={on->runCatching{val old=store.state.value.skills;store.setSkills(if(on)old+name else old-name)}.onFailure{error=it.message}},modifier=Modifier.testTag("analysis-skill-$name"))
                            }
                            if(index<analysisStyles.size-1)HorizontalDivider(color=Rs.colors.separator,modifier=Modifier.padding(horizontal=18.dp))
                        }
                    }
                }
                "model" -> {
                    if(AppGraph.isLlmProvidersReady) app.resound.ui.settings.ProvidersCard(AppGraph.llmProviders)
                    else Text("模型配置尚未初始化，请打开完整应用后重试。",style=RsType.body,color=Rs.colors.textSecondary)
                    Text("两种模式共用服务配置。分析发送提醒可在设置首页开启或关闭。",style=RsType.subhead,color=Rs.colors.textSecondary)
                }
                else -> {
                    RsCard(padding=PaddingValues(0.dp)) {
                        SettingRow("分析技能","基础规则与${state.skills.size}项已选技能") {panel="skills"}
                        HorizontalDivider(color=Rs.colors.separator,modifier=Modifier.padding(horizontal=18.dp))
                        SettingRow("分析模型","管理两种模式共用的模型配置") {panel="model"}
                    }
                    RsCard {
                        Text("默认报告类型",style=RsType.headline,color=Rs.colors.text)
                        Text("首次打开和更改此设置后，优先显示此类型；仍可在报告内切换。",style=RsType.subhead,color=Rs.colors.textSecondary)
                        Box{Pill(state.defaultReportKind,false,onClick={kindsOpen=true});DropdownMenu(kindsOpen,onDismissRequest={kindsOpen=false}){analysisReportKinds.forEach{kind->DropdownMenuItem(text={Text(kind)},onClick={runCatching{store.setDefaultReportKind(kind);kindsOpen=false}.onFailure{error=it.message}})}}}
                    }
                    RsCard(padding=PaddingValues(0.dp)) { AnalysisSendConfirmationSetting() }
                    AnalysisBackupControls(store,active)
                    RsCard {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)){Text("资料更新后整理报告",style=RsType.headline,color=Rs.colors.text);Text("开启后，新增材料或关联事件会发送该人物资料，生成当前类型报告并保留旧版。",style=RsType.subhead,color=Rs.colors.textSecondary)}
                            Switch(state.autoUpdate,onCheckedChange={runCatching{store.setAutoUpdate(it)}.onFailure{error=it.message}})
                        }
                    }
                    RsCard(padding=PaddingValues(0.dp)){SettingRow("关于文鱼","功能、隐私和开源来源"){panel="about"}}
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    editing?.let { kind ->
        BasePromptEditor(kind,if(kind=="report")state.reportPrompt else state.askPrompt,onDismiss={editing=null},onSave={store.setBasePrompt(kind,it);editing=null})
    }
}

@Composable private fun SettingRow(title:String,description:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(18.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text(title,style=RsType.headline,color=Rs.colors.text);Text(description,style=RsType.subhead,color=Rs.colors.textSecondary)}
        Text("›",style=RsType.title,color=Rs.colors.textTertiary)
    }
}

@Composable private fun BasePromptEditor(kind:String,current:String,onDismiss:()->Unit,onSave:(String)->Unit) {
    var draft by remember(kind){mutableStateOf(current)}
    var error by remember{mutableStateOf<String?>(null)}
    androidx.compose.ui.window.Dialog(onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(16.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),color=Rs.colors.card) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(if(kind=="report")"报告整理" else "人物问答",style=RsType.title,color=Rs.colors.text)
                Text("基础规则始终启用：限所选人物材料，保留出处，区分事实、推测和未知，核对反馈供复核。下方可补充语气、关注点和表达方式，不能关闭这些规则。",style=RsType.subhead,color=Rs.colors.textSecondary)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){RoundInput(draft,{if(it.length<=4000)draft=it},minLines=6,label={Text("补充你的整理要求（可留空）")})}
                error?.let{Text(it,color=Rs.colors.critical)}
                Row {TextButton(onClick=onDismiss){Text("取消")};TextButton(onClick={runCatching{onSave(draft)}.onFailure{error=it.message}}){Text("保存")}}
            }
        }
    }
}
