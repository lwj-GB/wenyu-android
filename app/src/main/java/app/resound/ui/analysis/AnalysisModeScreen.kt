package app.resound.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.resound.analysis.*
import app.resound.ui.theme.*
import app.resound.ui.verify.*
import app.resound.ui.ask.AnswerText
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisModeScreen(themeMode: ThemeMode = ThemeMode.System, onThemeMode: (ThemeMode)->Unit = {}, analysisStore: AnalysisStore? = null, settingsContent: (@Composable () -> Unit)? = null, requestedPersonId:String?=null, onPersonRequestHandled:()->Unit={}, onExit: () -> Unit) {
    val context = LocalContext.current
    val store = analysisStore ?: remember { AnalysisStore(context.applicationContext) }
    val engine = remember(store) { AnalysisEngine(store) }
    val state by store.state.collectAsState()
    val intelligence=remember(store){store.intelligence}
    val dailyQuestions by intelligence.dailyQuestions.collectAsState()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME){intelligence.refreshDailyQuestions()}
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val person = state.people.firstOrNull { it.id == selected } ?: state.people.firstOrNull()
    val pager=rememberPagerState(initialPage=0) { 5 }
    val page=pager.currentPage
    var create by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var dailyQuestion by remember{mutableStateOf<Long?>(null)}
    var editor by remember { mutableStateOf<String?>(null) }
    var eventDraftPerson by remember {mutableStateOf<String?>(null)}
    var confirm by remember { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf(state.defaultReportKind) }
    LaunchedEffect(state.defaultReportKind){kind=state.defaultReportKind}
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(requestedPersonId) {
        requestedPersonId?.let { id ->
            if(state.people.any{it.id==id}) {selected=id;create=false;pager.scrollToPage(0)}
            onPersonRequestHandled()
        }
    }
    val askRepo = remember(store,scope) { AnalysisAskRepository(context.applicationContext,store,scope) }
    val askPending by askRepo.pending.collectAsState()
    val c = Rs.colors
    val palette = c
    val currentId by rememberUpdatedState(person?.id)
    LaunchedEffect(person?.id) { error=null;dailyQuestion=null }
    fun target() = runCatching { engine.displayTarget() }.getOrElse { "请先到个人模式设置模型" }
    var pending by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    fun runReport(id: String, reportKind: String) {
        if(busy)return
        busy=true; error=null
        scope.launch { try { engine.generate(id,reportKind) } catch(e:CancellationException){throw e} catch(e: Exception) { if(currentId==id) error=e.message ?: "分析未完成，请重试" } finally { busy=false } }
    }
    fun addText(id: String, title: String, text: String, event: Boolean, audioPath: String? = null) {
        try {
            if(event) store.addEvent(id,title,text) else store.addMaterial(id,title,text,audioPath)
            editor=null
            if(state.autoUpdate && audioPath==null) pending=pending+(id to kind)
        } catch(e: Exception) { error=e.message ?: "保存失败"; throw e }
    }
    LaunchedEffect(busy,pending) { if(!busy && pending.isNotEmpty()) { val next=pending.entries.first();pending=pending-next.key;runReport(next.key,next.value) } }
    CompositionLocalProvider(LocalRs provides palette) {
        MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(primary=palette.accent, surface=palette.card, background=palette.background, onSurface=palette.text)) {
            Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()) {
                store.loadError?.let { Text(it,color=palette.critical,modifier=Modifier.padding(20.dp)) }
                error?.let { Text(it,color=palette.critical,style=RsType.subhead,modifier=Modifier.padding(20.dp,8.dp)) }
                if(create) PersonWizard(onCancel={create=false},onSave={ answers ->
                    try { selected=store.createPerson(answers); create=false;scope.launch{pager.scrollToPage(0)} } catch(e:Exception){error=e.message}
                }) else {
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=20.dp),color=palette.accent)
                    val haze=rememberHazeState()
                    val keyboard=WindowInsets.isImeVisible
                    HorizontalPager(pager,Modifier.weight(1f).hazeSource(haze).testTag("analysis-pages"),beyondViewportPageCount=4,userScrollEnabled=!keyboard) { index ->
                        CompositionLocalProvider(app.resound.ui.LocalShellPageSelected provides (pager.settledPage==index), app.resound.ui.LocalShellPageActive provides (pager.settledPage==index&&!pager.isScrollInProgress)) {
                            if(index==2) app.resound.ui.ask.AskScreen(askRepo,0.dp)
                            else if(index==4) {
                                if(settingsContent!=null) settingsContent()
                                else AnalysisSettingsScreen(store,state,active=pager.settledPage==4)
                            }
                            else Column(Modifier.fillMaxSize().testTag("analysis-page-$index").verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                when(index) {
                                    0 -> {
                                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Small(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("M月d日")))
                                                Text("分析模式",style=RsType.largeTitle,color=palette.text,modifier=Modifier.clickable(onClick=onExit))
                                            }
                                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                                Button(enabled=!busy,modifier=Modifier.testTag("analysis-smart-action"),onClick={if(person==null)create=true else confirm="报告"}) { Text(if(busy) "分析中…" else "智能分析",style=RsType.body) }
                                                ThemeModePill(themeMode,onThemeMode)
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                            Pill("通讯录",false,onClick={picker=true})
                                            Pill("＋ 新建人物",true,onClick={create=true})
                                        }
                                        if(person==null) Empty("从一个人开始","新建人物，或从通讯录选择已有档案。") else {
                                            PersonRecordingCard {
                                                key(person.id) {
                                                    app.resound.ui.analysisrecording.AnalysisRecordingControl(person.id,ownerName=person.name,onOpenProfile={editor="档案"},
                                                        onAnalyze={confirm="报告"},
                                                        transcriptText=person.materials.lastOrNull{it.audioPath!=null}?.text,
                                                        importCompanion={
                                                            app.resound.ui.analysisrecording.PersonalTranscriptImportControl(person.id,person.name) { items ->
                                                                items.forEach{store.importPersonalTranscript(person.id,it.segmentId,it.startAt,it.endAt,it.text,it.sourceKind)}
                                                                if(state.autoUpdate)pending=pending+(person.id to kind)
                                                            }
                                                        }) { title,text,path ->
                                                            if(store.person(person.id).materials.none{it.audioPath==path && it.text==text})store.addMaterial(person.id,title,text,path)
                                                        }
                                                }
                                                DailySocialSummary()
                                            }
                                            DailyQuestionsPanel(person.id,intelligence){dailyQuestion=it}
                                            PersonPendingClaims(state,person,store){error=it}
                                            InteractionSummary(state,person.id)
                                            ReportBody(person,kind,{kind=it},busy,{confirm="报告"},store,{error=it},afterReport={
                                                PersonRecentEvents(state,person.id,onTimeline={scope.launch{pager.animateScrollToPage(3)}},onCreate={eventDraftPerson=person.id;editor="独立事件"})
                                            })
                                            Pill("＋ 补充材料",false,onClick={editor="材料"})
                                        }
                                    }
                                    1 -> {
                                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                            Text("报告",style=RsType.largeTitle,color=palette.text)
                                            Pill("通讯录",false,onClick={picker=true})
                                        }
                                        if(person==null) Empty("还没有人物报告","先在人物页新建或选择人物。") else {
                                            Heading(person.name)
                                            ReportBody(person,kind,{kind=it},busy,{confirm="报告"},store,{error=it},showHistory=true)
                                        }
                                    }
                                    3 -> AnalysisEventTimeline(state,store,{error=it},onCreate={eventDraftPerson=null;editor="独立事件"})

                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                    if(!keyboard) app.resound.ui.TabBar(listOf("人物","报告","问问","事件","设置"),page,{target->scope.launch{pager.animateScrollToPage(target)}},haze,Modifier.align(Alignment.CenterHorizontally))
                }
            }
            dailyQuestions.firstOrNull{it.id==dailyQuestion && it.personId==person?.id && it.status==DailyQuestionStatus.Pending}?.let{q->
                DailyQuestionAnswerDialog(q,intelligence,onDismiss={dailyQuestion=null},onError={error=it})
            }
            askPending?.let { request -> key(request) { AnalysisSendConfirmation(title="询问${request.personName}",confirmLabel="发送",onConfirm=askRepo::confirmPending,onDismiss=askRepo::cancelPending) {
                Text("${runCatching{askRepo.target()}.getOrDefault("请先在设置中配置分析模型")}\n\n发送${request.personName}的档案、材料、事件和已有报告，回答会附出处。")
            } } }
            if(picker) AlertDialog(onDismissRequest={picker=false},title={Text("通讯录")},text={Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState())) {
                state.people.forEachIndexed { index,p ->
                    Column(Modifier.fillMaxWidth().clickable{selected=p.id;picker=false}.padding(vertical=12.dp)) {
                        val claimCount=p.reports.groupBy{it.kind}.values.sumOf{reports->reports.last().claims.count{it.verdict.isBlank()}}
                        val questionCount=dailyQuestions.count{it.personId==p.id && it.status==DailyQuestionStatus.Pending}
                        PendingPersonName(p.name,claimCount+questionCount)
                        Small(listOf(p.answers["q2"].orEmpty(),"${p.materials.size}份材料","${p.reports.size}份报告").filter{it.isNotBlank()}.joinToString(" · "))
                    }
                    if(index<state.people.lastIndex) HorizontalDivider(color=palette.separator)
                }
                if(state.people.isEmpty()) Small("还没有人物，先新建一个。")
            }},confirmButton={TextButton(onClick={picker=false}){Text("关闭")}})
            if(editor=="独立事件") StandaloneEventEditor(state.people,eventDraftPerson,onDismiss={editor=null},onSave={t,text,path,category,people,importance->store.addStandaloneEvent(t,text,path,category=category,personIds=people,importance=importance);if(state.autoUpdate && path==null)pending=pending+people.associateWith{kind};editor=null})
            val owner=person
            if(editor!=null&&editor!="独立事件"&&owner!=null) when(editor) {
                "档案" -> AlertDialog(onDismissRequest={editor=null},title={Text(owner.name)},text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    app.resound.ui.analysisrecording.ProfileSuggestionControl(store,engine,owner)
                    analysisQuestions.forEach { q -> Small(q.title); Text(owner.answers[q.id].orEmpty().ifBlank{"未填写"},color=palette.text) }
                    owner.materials.forEach { m -> Heading(m.title); Text(m.text,color=palette.text) }
                }},confirmButton={TextButton(onClick={editor="编辑档案"}){Text("编辑档案")}},dismissButton={TextButton(onClick={editor=null}){Text("关闭")}})
                "编辑档案" -> PersonProfileEditor(owner,onDismiss={editor=null},onSave={store.updatePerson(owner.id,it);editor=null;if(state.autoUpdate)pending=pending+(owner.id to kind)})
                else -> MaterialEditor(owner,editor=="事件",{editor=null},{title,text,audio->addText(owner.id,title,text,editor=="事件",audio)})
            }
            if(confirm!=null&&owner!=null) {
                val events=analysisEventRows(state).filter{owner.id in it.personIds}.distinctBy{it.event.id}
                AnalysisSendConfirmation(title="生成${kind}报告",confirmLabel="发送并分析",enabled=!busy,
                    onConfirm={confirm=null;runReport(owner.id,kind)},onDismiss={confirm=null}) {
                    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("${target()}\n\n本次发送：${owner.name}的基础回答、${owner.materials.size}份材料、${events.size}件事件及核对反馈。")
                        if(owner.materials.isEmpty() && events.isEmpty())Text("尚未添加原文材料，本次只依据已填档案分析。资料不足的部分会保留未知，也可以先补充材料。",style=RsType.subhead)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={confirm=null;editor="材料"}){Text("补充材料")}
                            TextButton(onClick={confirm=null;editor="编辑档案"}){Text("编辑档案")}
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Heading(text:String) { Text(text,style=RsType.title,color=Rs.colors.text) }
@Composable private fun Small(text:String) { Text(text,style=RsType.subhead,color=Rs.colors.textSecondary) }
@Composable private fun Empty(title:String,text:String) { RsCard {Heading(title);Spacer(Modifier.height(10.dp));Small(text)} }

@Composable private fun PersonRecordingCard(content:@Composable ColumnScope.()->Unit) {
    val c=Rs.colors
    val shape=androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
    val heroAccent=if(c.isDark) androidx.compose.ui.graphics.lerp(c.accent,Color.Black,.38f) else c.accent
    Column(Modifier.fillMaxWidth().testTag("person-recording-card")
        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(
            androidx.compose.ui.graphics.lerp(heroAccent,Color.White,.12f),heroAccent,
            androidx.compose.ui.graphics.lerp(heroAccent,Color.Black,.12f))),shape)
        .padding(horizontal=18.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        CompositionLocalProvider(LocalRs provides c.copy(background=heroAccent,card=heroAccent,cardPressed=heroAccent,separator=Color.White.copy(alpha=.2f),text=Color.White,textSecondary=Color.White.copy(alpha=.82f),textTertiary=Color.White.copy(alpha=.68f),accent=Color.White,accentSoft=Color.White.copy(alpha=.15f))) {
            MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(surface=heroAccent,surfaceContainerHigh=heroAccent,background=heroAccent,primary=Color.White,onPrimary=heroAccent,onSurface=Color.White,onSurfaceVariant=Color.White.copy(alpha=.82f),secondaryContainer=Color.White.copy(alpha=.16f),onSecondaryContainer=Color.White)) {
                content()
            }
        }
    }
}

@Composable private fun DailySocialSummary() {
    var date by remember { mutableStateOf(java.time.LocalDate.now()) }
    LaunchedEffect(Unit) { while(true) { kotlinx.coroutines.delay(60_000);date=java.time.LocalDate.now() } }
    val ready=app.resound.AppGraph.isMemoryReady
    val flow=remember(date,ready){if(ready)app.resound.AppGraph.memory.events(date) else kotlinx.coroutines.flow.flowOf(emptyList())}
    val events by flow.collectAsState(initial=null)
    SocialDaySummary(events,date,ready)
}

@Composable internal fun SocialDaySummary(events:List<app.resound.digest.MemoryEvent>?,date:java.time.LocalDate,available:Boolean=true) {
    val stats=remember(events,date){events?.let{analysisSocialStats(it,date)}}
    val context=LocalContext.current
    val prefs=remember(context){context.getSharedPreferences("ui",0)}
    val statusKey="social-status-$date"
    var custom by remember(date){mutableStateOf(prefs.getString(statusKey,null))}
    var editStatus by remember(date){mutableStateOf(false)}
    val status=custom ?: stats?.emotionEmoji.orEmpty()

    var expanded by rememberSaveable(date.toString()){mutableStateOf(false)}
    val c=Rs.colors
    HorizontalDivider(color=c.text.copy(alpha=.2f))
    Row(Modifier.fillMaxWidth().testTag("social-day-toggle").clickable{expanded=!expanded}.padding(vertical=5.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text("今日社交",style=RsType.headline,color=c.text)
        Text(when { !available->"未连接";stats==null->"读取中…";else->"${stats.people.size}人 · ${stats.eventCount}件事 · ${status}" },style=RsType.subhead,color=c.textSecondary)
        Text(if(expanded)"收起 ⌃" else "展开 ⌄",style=RsType.caption,color=c.textSecondary)
    }
    if(expanded) Column(Modifier.testTag("social-day-details"),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        if(stats!=null&&available) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                SocialStat(stats.people.size.toString(),"提到")
                SocialStat(stats.eventCount.toString(),"事件")
                Column(Modifier.testTag("social-status-edit").clickable{editStatus=true}.padding(horizontal=6.dp)) {
                    Text(custom ?: if(stats.emotions.isEmpty())"未记录" else stats.emotionEmoji,style=RsType.title,color=c.text)
                    Text("状态 · 编辑",style=RsType.caption,color=c.textSecondary)
                }
            }
        }

    }
    if(editStatus) DailyStatusEditor(custom,onDismiss={editStatus=false},onSave={value->
        val editor=prefs.edit()
        if(value==null)editor.remove(statusKey) else editor.putString(statusKey,value)
        check(editor.commit()) { "状态未保存，请重试" }
        custom=value;editStatus=false
    })
}

@Composable private fun SocialStat(value:String,label:String) {
    Column {Text(value,style=RsType.title,color=Rs.colors.text);Text(label,style=RsType.caption,color=Rs.colors.textSecondary)}
}

@Composable private fun ReportBody(p:AnalysisPerson,kind:String,onKind:(String)->Unit,busy:Boolean,onGenerate:()->Unit,store:AnalysisStore,onError:(String)->Unit,showHistory:Boolean=false,afterReport:@Composable ()->Unit={}) {
    var tab by rememberSaveable(p.id) { mutableIntStateOf(0) }
    var reviewFilter by rememberSaveable(p.id) { mutableIntStateOf(0) }
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        analysisReportKinds.forEach { Pill(it,it==kind,onClick={onKind(it)}) }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Pill("报告",tab==0,onClick={tab=0});Pill("待核对",tab==1,onClick={tab=1})}
    val report=p.reports.lastOrNull{it.kind==kind}
    if(tab==0) {
        RsCard {
            if(report==null) { Heading("还没有${kind}报告");Small("先补充材料，再生成带来源的观察和建议。") }
            else { AnswerText(readableAnalysisReport(p,report)); ReportExportButton(store,p,report); Small(if(report.revision<p.revision) "资料已有更新，可生成新版" else "基于当前资料生成") }
            Spacer(Modifier.height(12.dp));Pill(if(busy)"分析中…" else if(report==null)"生成报告" else "更新报告",true,onClick={if(!busy)onGenerate()})
        }
        report?.claims?.take(3)?.let { claims -> if(claims.isNotEmpty()) {Heading("核心观察");claims.forEach{c->RsCard{Text(readableAnalysisText(c.text,c.sources),style=RsType.body,color=Rs.colors.text);Sources(p,c.sources,store.state.value)}}} }
        if(showHistory) p.reports.filter{it.kind==kind&&it.id!=report?.id}.asReversed().forEach { r ->
            var expanded by remember(r.id){mutableStateOf(false)}
            RsCard{TextButton(onClick={expanded=!expanded}){Text("历史报告 · "+java.text.SimpleDateFormat("M月d日 HH:mm",java.util.Locale.CHINA).format(java.util.Date(r.at)))};if(expanded){AnswerText(readableAnalysisReport(p,r));ReportExportButton(store,p,r)}}
        }
        afterReport()
    } else {
        val allClaims=report?.claims.orEmpty()
        val done=allClaims.count{it.verdict.isNotBlank()}
        Heading("核对进度 · $done / ${allClaims.size}")
        if(allClaims.isNotEmpty()) LinearProgressIndicator(progress={done.toFloat()/allClaims.size},modifier=Modifier.fillMaxWidth(),color=Rs.colors.accent)
        Small("核对只记录你的反馈，不改写报告原文；不确定的判断可以稍后重看。")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("未核对","暂不确定","已处理").forEachIndexed { i,label -> Pill(label,reviewFilter==i,onClick={reviewFilter=i}) }
        }
        val claims=allClaims.filter{when(reviewFilter){0->it.verdict.isBlank();1->it.verdict=="不确定";else->it.verdict.isNotBlank()}}
        if(claims.isEmpty()) Small(when(reviewFilter){0->"没有待核对的判断；生成报告后会出现在这里。";1->"没有标为不确定的判断";else->"还没有核对记录"})
        claims.forEach { claim -> RsCard { Text(readableAnalysisText(claim.text,claim.sources),style=RsType.body,color=Rs.colors.text);Sources(p,claim.sources,store.state.value)
            if(claim.verdict.isNotBlank()) {
                Small("你的核对：${claim.verdict}")
                TextButton(onClick={try{store.review(p.id,report!!.id,claim.id,"")}catch(e:Exception){onError(e.message?:"核对未保存")}}){Text("重新待核对",style=RsType.body)}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("准确","不准确","不确定").forEach {v->Pill(v,false,onClick={try{store.review(p.id,report!!.id,claim.id,v)}catch(e:Exception){onError(e.message?:"核对未保存")}})}}
        } }
    }
}

@Composable private fun Sources(p:AnalysisPerson,ids:List<String>,state:AnalysisState?=null) {
    ids.forEach { id ->
        val text = analysisQuestions.firstOrNull{it.id==id}?.let { "${it.title}：${p.answers[id].orEmpty()}" }
            ?: p.materials.firstOrNull{it.id==id}?.let{"${it.title}：${it.text}"}
            ?: p.events.firstOrNull{it.id==id}?.let{"${it.title}：${it.text}"}
            ?: state?.let { analysisEventRows(it).firstOrNull { row -> p.id in row.personIds && row.event.id==id }?.event }?.let { "${it.title}：${it.text}" }
            ?: p.reports.firstOrNull{"report:"+it.id==id}?.let{"${it.kind}报告：${it.markdown}"} ?: "原始材料暂不可用"
        var expanded by remember(id){mutableStateOf(false)}
        TextButton(onClick={expanded=!expanded}) {Text(if(expanded)"收起依据" else "查看依据 · "+text.take(24))}
        if(expanded)Small(text)
    }
}

@Composable private fun PersonWizard(onCancel:()->Unit,onSave:(Map<String,String>)->Unit) {
    val answers=rememberSaveable { mutableStateOf<Map<String,String>>(emptyMap()) }
    val pager=rememberPagerState{analysisQuestions.size}
    val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Small("认识一个人 · ${pager.currentPage+1} / ${analysisQuestions.size} · 左右滑动")
        HorizontalPager(pager,modifier=Modifier.weight(1f),userScrollEnabled=true) { index ->
            val q=analysisQuestions[index]
            RsCard {Heading(q.title);Spacer(Modifier.height(24.dp));if(index==1) {
                    var expanded by remember { mutableStateOf(false) }
                    Box { Pill(answers.value[q.id].orEmpty().ifBlank { "选择关系" },false,onClick={expanded=true})
                        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) { listOf("家人","朋友","同事","同学","伴侣","客户","其他").forEach { relation -> DropdownMenuItem(text={Text(relation)},onClick={answers.value=answers.value+(q.id to relation);expanded=false}) } }
                    }
                } else RoundInput(answers.value[q.id].orEmpty(),{answers.value=answers.value+(q.id to it)},Modifier.fillMaxWidth(),singleLine=index in listOf(0,2,3,6,7),minLines=if(index in listOf(0,2,3,6,7))1 else 3,label={Text(if(index==0)"必填；可以使用昵称" else "按你了解的填写")})
                Spacer(Modifier.height(20.dp));if(index>0)TextButton(onClick={answers.value=answers.value+(q.id to "不知道");scope.launch{if(index<analysisQuestions.lastIndex)pager.animateScrollToPage(index+1)}}){Text("不知道，先跳过")}
            }
        }
        if(answers.value["q1"].orEmpty().isNotBlank() && pager.currentPage<analysisQuestions.lastIndex) Pill("先录音建档",false,onClick={onSave(answers.value)})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Pill("返回",false,onClick={if(pager.currentPage>0)scope.launch{pager.animateScrollToPage(pager.currentPage-1)}else onCancel()})
            Pill(if(pager.currentPage==analysisQuestions.lastIndex)"保存人物" else "下一题",true,onClick={if(pager.currentPage==analysisQuestions.lastIndex)onSave(answers.value) else scope.launch{pager.animateScrollToPage(pager.currentPage+1)}})
        }
    }
}

@Composable private fun MaterialEditor(person:AnalysisPerson,event:Boolean,onDismiss:()->Unit,onSave:(String,String,String?)->Unit) {
    val id=person.id
    var title by rememberSaveable(id,event){mutableStateOf("")};var text by rememberSaveable(id,event){mutableStateOf("")}
    androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),color=Rs.colors.card) {
            Column(Modifier.padding(20.dp)) {
                Heading(if(event)"记录事件" else "补充人物材料")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
        RoundInput(title,{title=it},label={Text("标题")},modifier=Modifier.fillMaxWidth())
        RoundInput(text,{text=it},label={Text(if(event)"发生了什么？尽量保留原话和时间" else "粘贴原文或填写你的观察")},modifier=Modifier.fillMaxWidth(),minLines=4)
        if(!event) {
            val extension = when {
                person.answers["q3"].orEmpty().contains("学生") -> "他最近在学什么？哪件事让他有成就感？"
                person.answers["q2"].orEmpty().let { it.contains("同事") || it.contains("客户") } -> "合作时他最看重什么？最近有哪次具体的协作经历？"
                person.answers["q2"].orEmpty().let { it.contains("伴侣") || it.contains("恋") } -> "他在怎样的相处中更自在？你们最近有哪些需要澄清的期待？"
                else -> "他在${person.answers["q3"].orEmpty().ifBlank { "日常生活" }}中最看重什么？你们最近一次相处有什么变化？"
            }
            Small("可选延伸：$extension")
        }
        if(!event) key(id) { app.resound.ui.analysisrecording.AnalysisRecordingControl(id) { t,s,path -> onSave(t,s,path) } }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick=onDismiss){Text("关闭")}
                    TextButton(enabled=text.isNotBlank(),onClick={runCatching { onSave(title,text,null) }}){Text("保存")}
                }
            }
        }
    }
}


@Composable private fun StandaloneEventEditor(people:List<AnalysisPerson>,initialPersonId:String?,onDismiss:()->Unit,onSave:(String,String,String?,String?,List<String>,String)->Unit) {
    var category by rememberSaveable {mutableStateOf<String?>(null)}
    var importance by rememberSaveable {mutableStateOf("日常")}
    var linked by rememberSaveable {mutableStateOf(initialPersonId?.let{listOf(it)}?:emptyList())}
    var categoriesOpen by remember {mutableStateOf(false)}
    val draft=rememberSaveable { "event-draft:"+java.util.UUID.randomUUID().toString() }
    var title by rememberSaveable { mutableStateOf("") };var text by rememberSaveable {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    androidx.compose.ui.window.Dialog(onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(16.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),color=Rs.colors.card) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Heading("新建事件")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    RoundInput(title,{title=it},singleLine=true,label={Text("事件名称")})
                    RoundInput(text,{text=it},minLines=3,label={Text("发生了什么")})
                    Box {
                        Pill(category?:"分类（可选）",false,onClick={categoriesOpen=true})
                        DropdownMenu(categoriesOpen,onDismissRequest={categoriesOpen=false}) {
                            analysisEventCategories.forEach { option->DropdownMenuItem(text={Text(option)},onClick={category=option;categoriesOpen=false}) }
                        }
                    }
                    Heading("重要程度")
                    Small("按你的实际感受标记，紧急和重要事件会排在合集前面。")
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        analysisEventImportance.forEach { label -> Pill(label,importance==label,onClick={importance=label}) }
                    }
                    if(people.isNotEmpty()) {
                        Small("关联人物（可选）")
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            people.forEach {p->Pill(p.name,p.id in linked,onClick={linked=if(p.id in linked)linked-p.id else linked+p.id})}
                        }
                    }
                    app.resound.ui.analysisrecording.AnalysisRecordingControl(draft){t,s,path->onSave(t,s,path,category,linked,importance)}
                    error?.let {Small(it)}
                }
                Row { TextButton(onClick=onDismiss){Text("关闭")}; TextButton(enabled=text.isNotBlank(),onClick={runCatching{onSave(title,text,null,category,linked,importance)}.onFailure{error=it.message}}){Text("保存事件")} }
            }
        }
    }
}


/** Same quiet, rounded input treatment as the original chat composer. */
@Composable internal fun RoundInput(value:String,onValueChange:(String)->Unit,modifier:Modifier=Modifier,singleLine:Boolean=false,minLines:Int=1,label:@Composable ()->Unit) {
    val c=Rs.colors
    androidx.compose.foundation.text.BasicTextField(value,onValueChange,modifier.fillMaxWidth(),textStyle=RsType.body.copy(color=c.text),singleLine=singleLine,minLines=minLines,
        cursorBrush=androidx.compose.ui.graphics.SolidColor(c.accent),decorationBox={inner->
            Column(Modifier.background(c.background,androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(horizontal=16.dp,vertical=14.dp)) {
                if(value.isEmpty()) CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides RsType.body,androidx.compose.material3.LocalContentColor provides c.textTertiary){label()}
                inner()
            }
        })
}


@Composable private fun PersonPendingClaims(state:AnalysisState,person:AnalysisPerson,store:AnalysisStore,onError:(String)->Unit) {
    var showProcessed by rememberSaveable(person.id){mutableStateOf(false)}
    var opened by remember(person.id){mutableStateOf<Pair<String,String>?>(null)}
    val cardState=androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(person.id,showProcessed){cardState.scrollToItem(0)}
    val reports=person.reports.groupBy{it.kind}.values.mapNotNull{it.lastOrNull()}.sortedByDescending{it.at}
    val all=reports.flatMap{r->r.claims.map{r to it}}
    val pending=all.filter{it.second.verdict.isBlank()}
    val shown=all.filter{if(showProcessed)it.second.verdict.isNotBlank() else it.second.verdict.isBlank()}
    Column(Modifier.testTag("person-pending"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("待确认 · ${pending.size}",style=RsType.title,color=Rs.colors.text)
            TextButton(onClick={showProcessed=!showProcessed}){Text(if(showProcessed)"返回待确认" else "已处理",style=RsType.subhead)}
        }
        if(shown.isEmpty())Text(if(showProcessed)"还没有处理记录" else "当前没有待确认内容",style=RsType.subhead,color=Rs.colors.textSecondary)
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth=(maxWidth-12.dp)/2
            androidx.compose.foundation.lazy.LazyRow(state=cardState,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                items(shown.size,key={index->shown[index].first.id+":"+shown[index].second.id}) { index ->
                    val (report,claim)=shown[index]
                    fun review(verdict:String) {try{store.review(person.id,report.id,claim.id,verdict)}catch(e:Exception){onError(e.message?:"确认未保存")}}
                    RsCard(Modifier.width(cardWidth),padding=PaddingValues(14.dp),color=Rs.colors.accentSoft) {
                        Text(report.kind,style=RsType.caption,color=Rs.colors.accent,maxLines=1)
                        Spacer(Modifier.height(8.dp))
                        Text(readableAnalysisText(claim.text,claim.sources),style=RsType.subhead,maxLines=3,minLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=Rs.colors.text)
                        TextButton(onClick={opened=report.id to claim.id},contentPadding=PaddingValues(vertical=4.dp)){Text("查看依据",style=RsType.caption)}
                        if(claim.verdict.isBlank()) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            app.resound.ui.today.SmallPill("忽略",false,Modifier.weight(1f)){review("已忽略")}
                            app.resound.ui.today.SmallPill("接受",true,Modifier.weight(1f)){review("准确")}
                        } else {
                            Text(if(claim.verdict=="准确")"已接受" else claim.verdict,style=RsType.caption,color=Rs.colors.textSecondary)
                            TextButton(onClick={review("")},contentPadding=PaddingValues(vertical=4.dp)){Text("重新待确认",style=RsType.caption)}
                        }
                    }
                }
            }
        }
    }
    opened?.let { (reportId,claimId) ->
        val claim=person.reports.firstOrNull{it.id==reportId}?.claims?.firstOrNull{it.id==claimId}
        if(claim!=null) AlertDialog(onDismissRequest={opened=null},title={Text("核对依据")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(readableAnalysisText(claim.text,claim.sources),style=RsType.body);Sources(person,claim.sources,state)
        }},confirmButton={TextButton(onClick={opened=null}){Text("关闭")}})
    }
}

@Composable private fun InteractionSummary(state:AnalysisState,personId:String) {
    val stats=remember(state,personId){analysisInteractionStats(state,personId)}
    RsCard {
        Heading("交集记录")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Column{Text(stats.recordedDays.toString(),style=RsType.number,color=Rs.colors.accent);Small("有记录天数")}
            Column{Text(stats.importedSegments.toString(),style=RsType.number,color=Rs.colors.accent);Small("已导入资料")}
            Column{Text(stats.eventCount.toString(),style=RsType.number,color=Rs.colors.accent);Small("关联事件")}
        }
        stats.latestRecordedAt?.let { Small("最近记录："+java.text.SimpleDateFormat("M月d日 HH:mm",java.util.Locale.CHINA).format(java.util.Date(it))) }
        Small("按已保存的记录统计，不代表见面次数。")
    }
}

@Composable private fun AnalysisEventTimeline(state:AnalysisState,store:AnalysisStore,onError:(String)->Unit,onCreate:()->Unit) {
    val rows=remember(state){analysisEventRows(state)}
    var mode by rememberSaveable{mutableIntStateOf(0)}
    var day by rememberSaveable{mutableLongStateOf(java.time.LocalDate.now().toEpochDay())}
    val date=java.time.LocalDate.ofEpochDay(day)
    val zone=java.time.ZoneId.systemDefault()
    fun dateOf(at:Long)=java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val groups=when(mode){
        0 -> listOf(date.format(java.time.format.DateTimeFormatter.ofPattern("M月d日")) to rows.filter{dateOf(it.timelineAt)==date})
        1 -> (analysisEventCategories+rows.map{it.category}).distinct().map { name -> name to rows.filter{it.category==name} }.filter{it.second.isNotEmpty()}
        else -> {
            val people=state.people.associateBy{it.id}
            rows.flatMap { row -> row.personIds.ifEmpty{listOf("")}.map { id -> (people[id]?.name?:"未关联人物") to row } }.groupBy({it.first},{it.second}).toList()
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text("事件",style=RsType.largeTitle,color=Rs.colors.text)
        Pill("＋ 新建",true,onClick=onCreate)
    }
    EventViewSwitch(mode){mode=it}
    if(mode==0) {
        val active=remember(rows){rows.map{dateOf(it.timelineAt)}.toSet()}
        val days=remember(active) { { month:java.time.YearMonth -> kotlinx.coroutines.flow.flowOf(active.filter{java.time.YearMonth.from(it)==month}.toSet()) } }
        app.resound.ui.items.DatePickerStrip(date,days){day=it.toEpochDay()}
    }
    if(groups.isEmpty()) RsCard {
        Heading("还没有事件")
        Small("可以记录一次工作、学习或相处中的具体事情。")
        FilledTonalButton(onClick=onCreate){Text("记录一件事",style=RsType.body)}
    }
    groups.forEach { (label,items) ->
        Heading(label)
        if(items.isEmpty()) RsCard {
            Heading("这一天还没有事件")
            Small("可以新建文字或录音记录，也可以切换到有记录的日期。")
            FilledTonalButton(onClick=onCreate){Text("记录一件事",style=RsType.body)}
            rows.firstOrNull()?.let { latest -> TextButton(onClick={day=dateOf(latest.timelineAt).toEpochDay()}){Text("查看最近记录",style=RsType.body)} }
        }
        else RsCard(padding=PaddingValues(0.dp)) {
            items.forEachIndexed { index,row ->
                var expanded by rememberSaveable(row.event.id,label){mutableStateOf(false)}
                Column(Modifier.fillMaxWidth().clickable{expanded=!expanded}.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Small(java.text.SimpleDateFormat("HH:mm",java.util.Locale.CHINA).format(java.util.Date(row.timelineAt)))
                        Spacer(Modifier.width(12.dp))
                        Text(row.event.title,style=RsType.headline,color=Rs.colors.text,modifier=Modifier.weight(1f))
                        Small(if(expanded)"⌃" else "⌄")
                    }
                    Small((if(row.dateIsRecorded)"记录时间" else "发生时间")+" · "+row.category+" · "+row.event.importance)
                    if(expanded) {
                        Text(row.event.text,style=RsType.body,color=Rs.colors.text)
                        val names=row.personIds.mapNotNull{ id->state.people.firstOrNull{it.id==id}?.name}
                        if(names.isNotEmpty())Small("关联人物："+names.joinToString("、"))
                        EventMetadataControls(row.event,store,onError)
                    } else Text(row.event.text.take(70),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=RsType.body,color=Rs.colors.textSecondary)
                }
                if(index<items.lastIndex) HorizontalDivider(color=Rs.colors.separator,modifier=Modifier.padding(horizontal=18.dp))
            }
        }
    }
}


@Composable private fun EventViewSwitch(selected:Int,onSelect:(Int)->Unit) {
    val c=Rs.colors
    val shape=androidx.compose.foundation.shape.CircleShape
    Row(Modifier.testTag("event-view-switch").background(c.card,shape).padding(3.dp)) {
        listOf("按天","按类","按人").forEachIndexed { index,label ->
            Text(label,style=RsType.subhead.copy(fontWeight=androidx.compose.ui.text.font.FontWeight.Medium),
                color=if(index==selected)c.accent else c.textSecondary,
                modifier=Modifier.testTag("event-view-$index")
                    .background(if(index==selected)c.accentSoft else Color.Transparent,shape)
                    .selectable(selected=index==selected,role=androidx.compose.ui.semantics.Role.Tab,onClick={onSelect(index)})
                    .padding(horizontal=14.dp,vertical=8.dp))
        }
    }
}

@Composable private fun EventMetadataControls(event:AnalysisEvent,store:AnalysisStore,onError:(String)->Unit) {
    var categoryOpen by remember(event.id){mutableStateOf(false)}
    var importanceOpen by remember(event.id){mutableStateOf(false)}
    fun save(category:String?,importance:String) {
        try { store.updateEventMetadata(event.id,category,importance) }
        catch(e:Exception) { onError(e.message?:"事件标记未保存") }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Box {
            TextButton(onClick={categoryOpen=true}){Text("修改分类",style=RsType.body)}
            DropdownMenu(categoryOpen,onDismissRequest={categoryOpen=false}) {
                analysisEventCategories.forEach{ label -> DropdownMenuItem(text={Text(label)},onClick={save(label,event.importance);categoryOpen=false}) }
            }
        }
        Box {
            TextButton(onClick={importanceOpen=true}){Text("标记重要程度",style=RsType.body)}
            DropdownMenu(importanceOpen,onDismissRequest={importanceOpen=false}) {
                analysisEventImportance.forEach{ label -> DropdownMenuItem(text={Text(label)},onClick={save(event.category,label);importanceOpen=false}) }
            }
        }
    }
}


@Composable private fun PersonRecentEvents(state:AnalysisState,personId:String,onTimeline:()->Unit,onCreate:()->Unit) {
    val rows=remember(state,personId){analysisEventRows(state).filter{personId in it.personIds}.take(3)}
    RsCard {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("近期关联事件",style=RsType.headline,color=Rs.colors.text)
            TextButton(onClick=onTimeline){Text("查看时间线")}
        }
        if(rows.isEmpty())Text("还没有关联事件，可以从一次具体相处开始记录。",style=RsType.subhead,color=Rs.colors.textSecondary)
        rows.forEachIndexed { index,row ->
            var open by rememberSaveable(row.event.id){mutableStateOf(false)}
            if(index>0)HorizontalDivider(color=Rs.colors.separator)
            Column(Modifier.fillMaxWidth().clickable{open=!open}.padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(row.event.title,style=RsType.headline,color=Rs.colors.text)
                Text((if(row.dateIsRecorded)"记录于 " else "发生于 ")+java.text.SimpleDateFormat("M月d日 HH:mm",java.util.Locale.CHINA).format(java.util.Date(row.timelineAt)),style=RsType.caption,color=Rs.colors.textSecondary)
                if(open)Text(row.event.text,style=RsType.body,color=Rs.colors.text)
            }
        }
        TextButton(onClick=onCreate){Text("＋ 记录关联事件")}
    }
}


@Composable private fun PersonProfileEditor(person:AnalysisPerson,onDismiss:()->Unit,onSave:(Map<String,String>)->Unit) {
    var answers by remember(person.id){mutableStateOf(person.answers)}
    var error by remember{mutableStateOf<String?>(null)}
    androidx.compose.ui.window.Dialog(onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(16.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),color=Rs.colors.card) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Heading("编辑人物档案")
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    analysisQuestions.forEachIndexed{index,q->
                        Text(q.title,style=RsType.headline,color=Rs.colors.text)
                        if(index==1){
                            var open by remember{mutableStateOf(false)}
                            Box{Pill(answers[q.id].orEmpty().ifBlank{"选择关系"},false,onClick={open=true});DropdownMenu(open,onDismissRequest={open=false}){listOf("家人","朋友","同事","同学","伴侣","客户","其他").forEach{relation->DropdownMenuItem(text={Text(relation)},onClick={answers=answers+(q.id to relation);open=false})}}}
                        } else RoundInput(answers[q.id].orEmpty(),{answers=answers+(q.id to it)},singleLine=index in listOf(0,2,3,6,7),minLines=if(index in listOf(0,2,3,6,7))1 else 3,label={Text(if(index==0)"姓名或昵称（必填）" else "未填写")})
                    }
                }
                error?.let{Text(it,color=Rs.colors.critical)}
                Row{TextButton(onClick=onDismiss){Text("取消")};TextButton(onClick={runCatching{onSave(answers)}.onFailure{error=it.message?:"保存失败，原档案未改动"}}){Text("保存档案")}}
            }
        }
    }
}
