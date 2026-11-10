package app.resound.ui.analysis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.resound.analysis.*
import app.resound.skills.SkillTrigger
import app.resound.skills.SKILL_ZONE
import app.resound.ui.theme.*
import java.time.LocalDate

@Composable internal fun PendingPersonName(name:String,count:Int,modifier:Modifier=Modifier) {
    Row(modifier,verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(name,style=RsType.headline,color=Rs.colors.text)
        if(count>0) Box(Modifier.padding(top=2.dp).size(7.dp).background(Rs.colors.critical,CircleShape)
            .testTag("person-pending-dot").semantics{contentDescription="$count 项待核对"})
    }
}

@Composable internal fun DailyQuestionsPanel(personId:String,repo:AnalysisSkillRepository,onOpen:(Long)->Unit) {
    val all by repo.dailyQuestions.collectAsState()
    val questions=all.filter{it.personId==personId && it.status==DailyQuestionStatus.Pending}
    if(questions.isNotEmpty()) Column(Modifier.testTag("daily-questions-panel"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("资料待核对 · ${questions.size}",style=RsType.title,color=Rs.colors.text)
        questions.forEach { q ->
            TextButton(onClick={onOpen(q.id)},modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(q.text,style=RsType.body,color=Rs.colors.text)
                    Text("${q.date} · 补充回答",style=RsType.caption,color=Rs.colors.textSecondary)
                }
            }
        }
    }
}

@Composable internal fun DailyQuestionAnswerDialog(q:AnalysisDailyQuestion,repo:AnalysisSkillRepository,onDismiss:()->Unit,onError:(String)->Unit) {
    key(q.id) {
        var answer by remember{mutableStateOf("")}
        var date by remember{mutableStateOf("")}
        var error by remember{mutableStateOf<String?>(null)}
        fun resolve(status:DailyQuestionStatus) {runCatching{repo.resolveQuestion(q.id,status)}.onSuccess{onDismiss()}.onFailure{error=it.message;onError(it.message?:"未保存，请重试")}}
        androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(16.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),color=Rs.colors.card) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("补充资料",style=RsType.title,color=Rs.colors.text)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(q.text,style=RsType.body)
                        Text("提问日期：${q.date}",style=RsType.caption)
                        if(q.reason.isNotBlank())Text(q.reason,style=RsType.subhead,color=Rs.colors.textSecondary)
                        RoundInput(answer,{answer=it},label={Text("你的回答")},modifier=Modifier.fillMaxWidth().testTag("daily-question-answer"),minLines=3)
                        Text("事情发生日期（可选）",style=RsType.caption)
                        RoundInput(date,{date=it},label={Text("例如 2026-11-11")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("daily-question-date"))
                        error?.let{Text(it,color=Rs.colors.critical,style=RsType.caption)}
                        Row {TextButton(onClick={resolve(DailyQuestionStatus.Unknown)}){Text("不知道")};TextButton(onClick={resolve(DailyQuestionStatus.Skipped)}){Text("跳过")}}
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(onClick=onDismiss){Text("稍后回答")}
                        TextButton(enabled=answer.isNotBlank(),onClick={
                            runCatching {
                                val occurred=if(date.isBlank())null else try{LocalDate.parse(date.trim()).atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli()}catch(_:Exception){throw IllegalArgumentException("请按年-月-日填写发生日期")}
                                repo.answerQuestion(q.id,answer.trim(),occurred)
                            }.onSuccess{onDismiss()}.onFailure{error=it.message?:"回答未保存"}
                        }){Text("保存回答")}
                    }
                }
            }
        }
    }
}

@Composable internal fun DailyQuestionsSettings(repo:AnalysisSkillRepository,onError:(String)->Unit) {
    val skills by repo.skills.collectAsState()
    val skill=skills.firstOrNull{it.id==DAILY_QUESTIONS_SKILL}?:return
    var opened by remember{mutableStateOf(false)}
    var timePicker by remember{mutableStateOf(false)}
    val trigger=skill.trigger as? SkillTrigger.Daily
    val minutes=trigger?.minutes?:1230
    val time="%02d:%02d".format(minutes/60,minutes%60)
    val context=LocalContext.current
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(!granted)onError("通知权限未开启，问题仍会保存在人物页")
    }
    TextButton(onClick={opened=true},modifier=Modifier.testTag("daily-questions-settings")) {
        Text("每日资料补充 · $time · ${skill.maxCardsPerDay}问",style=RsType.subhead)
    }
    fun change(action:()->Unit){runCatching(action).onFailure{onError(it.message?:"设置未保存")}}
    if(opened)AlertDialog(onDismissRequest={opened=false},title={Text("每日资料补充")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){Text("每天整理待核对问题",Modifier.weight(1f));Switch(skill.enabled,{on->change{repo.setEnabled(skill.id,on)}})}
            TextButton(onClick={timePicker=true}){Text("提醒时间 · $time")}
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("每人每天")
                listOf(2,3).forEach{count->FilterChip(selected=skill.maxCardsPerDay==count,onClick={change{repo.setQuestionCount(count)}},label={Text("${count}问")})}
            }
            Row(verticalAlignment=Alignment.CenterVertically){Text("生成后通知",Modifier.weight(1f));Switch(skill.push,{on->change{repo.setPush(skill.id,on)};if(on && Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.POST_NOTIFICATIONS)})}
            Text("仅为已开启人物自动分析授权的人生成。未处理问题会保留，不重复堆积。按设定时间安排，省电和后台限制可能使通知延后。",style=RsType.subhead,color=Rs.colors.textSecondary)
            Text("模型、提示词和手动生成在下方同名技能中设置。",style=RsType.caption,color=Rs.colors.textSecondary)
        }
    },confirmButton={TextButton(onClick={opened=false}){Text("完成")}})
    if(timePicker)app.resound.ui.verify.WheelTimeSheet("每天提醒时间",minutes,onDismiss={timePicker=false}){next->change{repo.setSchedule(skill.id,next,trigger?.weekdays.orEmpty())};timePicker=false}
}
