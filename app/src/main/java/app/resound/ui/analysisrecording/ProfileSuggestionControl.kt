package app.resound.ui.analysisrecording

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.resound.analysis.*
import app.resound.ui.theme.RsType
import app.resound.ui.analysis.AnalysisSendConfirmation
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun ProfileSuggestionControl(store: AnalysisStore, engine: AnalysisEngine, person: AnalysisPerson) {
    key(person.id) {
        val scope=rememberCoroutineScope()
        var confirm by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var proposal by remember { mutableStateOf<AnalysisEngine.ProfileProposal?>(null) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var message by remember { mutableStateOf<String?>(null) }
        FilledTonalButton(enabled=!busy && person.materials.isNotEmpty(),onClick={ confirm=true }) {
            Text(if(busy) "正在提取档案…" else "AI 补充空白档案",style=RsType.body)
        }
        message?.let { Text(it,style=RsType.subhead) }
        if(confirm) AnalysisSendConfirmation(
            title="提取档案建议", confirmLabel="提取建议", onDismiss={confirm=false},
            enabled=!busy && person.materials.isNotEmpty(), onConfirm={
                confirm=false;busy=true;message=null
                scope.launch { try {
                    val result=engine.suggestProfile(person.id)
                    proposal=result;selected=result.fields.map { it.id }.toSet()
                    if(result.fields.isEmpty())message="材料还不足以补齐空白档案，可以继续添加资料。"
                } catch(e:CancellationException){throw e} catch(e:Exception){message=e.message?:"提取失败，请重试"} finally {busy=false} }
            },
        ) { Text(runCatching { engine.displayTarget() }.getOrDefault("当前分析模型")+"\n\n发送${person.name}的档案、已添加材料与关联事件。AI仅提出有来源的空白字段；你选择确认后才保存，已填内容保留。") }
        proposal?.takeIf { it.fields.isNotEmpty() }?.let { result ->
            AlertDialog(onDismissRequest={proposal=null},title={Text("核对档案建议")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
                result.fields.forEach { field ->
                    Row { Checkbox(field.id in selected,{checked->selected=if(checked)selected+field.id else selected-field.id})
                        Column(Modifier.weight(1f)) { Text(analysisQuestions.first{it.id==field.id}.title,style=RsType.headline);Text(field.text)
                            field.sources.forEach { id ->
                                val material=person.materials.firstOrNull{it.id==id}
                                val event=analysisEventRows(store.state.value).firstOrNull { it.event.id==id && person.id in it.personIds }?.event
                                val question=analysisQuestions.firstOrNull{it.id==id}
                                val source=when { material!=null->"${material.title}\n${material.text}"
                                    event!=null->"${event.title}\n${event.text}"
                                    question!=null->"${question.title}\n${person.answers[id].orEmpty()}"
                                    else->id }
                                Text("来源：$source",style=RsType.subhead)
                            }
                        }
                    }
                }
            }},confirmButton={TextButton(enabled=selected.isNotEmpty(),onClick={
                try {store.applyProfileSuggestions(person.id,result.revision,result.fields.filter{it.id in selected}.associate{it.id to it.text});proposal=null;message="已补充所选空白档案"}
                catch(e:Exception){proposal=null;message=e.message?:"保存失败，请重新提取"}
            }){Text("保存所选建议")}},dismissButton={TextButton(onClick={proposal=null}){Text("取消")}})
        }
    }
}
