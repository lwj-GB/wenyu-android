package app.resound.ui.analysisrecording

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import app.resound.ui.verify.BreathingWave
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.resound.AppGraph
import app.resound.capture.CaptureState
import app.resound.transcribe.*
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Audio remains separate from the personal capture queue. No automatic model upload. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AnalysisRecordingControl(ownerId: String, ownerName: String? = null,
    onAnalyze:(()->Unit)?=null, importCompanion:(@Composable ()->Unit)?=null, transcriptText:String?=null, onOpenProfile:(()->Unit)?=null,
    onMaterial: (String, String, String) -> Unit) {
    key(ownerId) { OwnerRecordingControl(ownerId, ownerName, onAnalyze, importCompanion, transcriptText, onOpenProfile, onMaterial) }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun OwnerRecordingControl(ownerId: String, ownerName: String?, onAnalyze:(()->Unit)?, importCompanion:(@Composable ()->Unit)?, transcriptText:String?, onOpenProfile:(()->Unit)?, onMaterial: (String, String, String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val directory = remember { File(context.filesDir, "analysis-audio").apply { mkdirs() } }
    val prefs = remember { context.getSharedPreferences("analysis-recording-drafts", 0) }
    var file by remember(ownerId) { mutableStateOf(prefs.getString(ownerId, null)?.let(::File)?.takeIf { it.isFile }) }
    var recorder by remember(ownerId) { mutableStateOf<MediaRecorder?>(null) }
    var paused by remember(ownerId) { mutableStateOf(false) }
    var saved by remember(ownerId) { mutableStateOf(file != null) }
    var busy by remember(ownerId) { mutableStateOf(false) }
    var message by remember(ownerId) { mutableStateOf("") }
    var transcription by remember(ownerId) { mutableStateOf(SingleAudioTranscriptionState(text=transcriptText)) }
    LaunchedEffect(transcriptText) { if(!transcription.running && transcriptText!=null)transcription=transcription.copy(text=transcriptText) }
    var importing by remember(ownerId) { mutableStateOf(false) }
    var operationStarted by remember(ownerId) { mutableStateOf<Long?>(null) }
    var operationElapsed by remember(ownerId) { mutableLongStateOf(0L) }
    LaunchedEffect(operationStarted) {
        val started=operationStarted?:return@LaunchedEffect
        while(isActive){operationElapsed=(android.os.SystemClock.elapsedRealtime()-started).coerceAtLeast(0);delay(200)}
    }

    var level by remember(ownerId) { mutableFloatStateOf(0f) }
    LaunchedEffect(recorder,paused) {
        level=0f
        val current=recorder
        if(current!=null && !paused) while(isActive) {
            level=runCatching{current.maxAmplitude.coerceIn(0,32767)/32767f}.getOrDefault(0f)
            delay(60)
        }
    }
    fun persist(target: File) {
        synchronized(app.resound.analysis.AnalysisStore.fileLock) { check(prefs.edit().putString(ownerId, target.absolutePath).commit()) { "保存失败" } }
    }
    fun finish() {
        val active = recorder ?: return
        try {
            active.stop()
            file?.let(::persist)
            saved = true
            message = "原音已保存，可转写并加入当前档案"
        } catch (_: Exception) { message = "录音未能保存，请重新录制"; saved = false }
        finally { active.release(); recorder = null; paused = false }
    }
    fun start() {
        if (AppGraph.isReady && AppGraph.capture.status.value.state !in setOf(CaptureState.Stopped, CaptureState.NoPermission)) {
            message = "个人模式正在聆听。请先在个人模式关闭聆听，再开始这次录音。"
            return
        }
        var fresh: MediaRecorder? = null
        try {
            val target = File(directory, "${UUID.randomUUID()}.m4a")
            @Suppress("DEPRECATION")
            val next = MediaRecorder()
            fresh = next
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioSamplingRate(16000)
            next.setAudioEncodingBitRate(64000)
            next.setOutputFile(target.absolutePath)
            next.prepare(); next.start()
            file = target; recorder = next; saved = false; paused = false;transcription=SingleAudioTranscriptionState()
            message = "正在录音，短按暂停，长按保存原音"
        } catch (_: Exception) { fresh?.release(); message = "录音启动失败，请检查麦克风权限或占用" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else message = "需要麦克风权限才能录音"
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; importing=true;operationStarted=android.os.SystemClock.elapsedRealtime();operationElapsed=0
            try {
                val format = when (context.contentResolver.getType(uri)) {
                    "audio/wav", "audio/x-wav" -> "wav"
                    "audio/ogg", "application/ogg" -> "ogg"
                    "audio/mpeg", "audio/mp3" -> "mp3"
                    "audio/mp4", "audio/x-m4a", "video/mp4" -> "m4a"
                    else -> error("请选择M4A、MP3、WAV或OGG音频")
                }
                val target = importAnalysisAudio(directory, format,
                    openInput = { context.contentResolver.openInputStream(uri) },
                    validate = { candidate ->
                        val metadata = MediaMetadataRetriever()
                        try { metadata.setDataSource(candidate.absolutePath); require((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0) { "无法识别音频" } }
                        finally { metadata.release() }
                    }, commit = ::persist)
                file = target; saved = true; transcription=SingleAudioTranscriptionState(); message = "音频已保存，尚未转写"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "导入失败，请选择有效且不超过100MiB的M4A、MP3、WAV或OGG音频" }
            finally { busy = false;importing=false;operationStarted=null }
        }
    }
    val captureStatus = if (AppGraph.isReady) AppGraph.capture.status.collectAsState().value else null
    LaunchedEffect(captureStatus?.state) {
        if (recorder != null && captureStatus?.state !in setOf(null, CaptureState.Stopped, CaptureState.NoPermission)) {
            finish()
            message = "个人聆听已启动，这次独立录音已停止并保留原音"
        }
    }
    DisposableEffect(ownerId, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) recorder?.let { active ->
                runCatching { active.pause(); paused = true; message = "离开前台，录音已暂停" }
                    .onFailure { finish() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            recorder?.let { active -> runCatching { active.stop(); file?.let(::persist) }; active.release(); recorder = null }
        }
    }
    val settings = if (AppGraph.isTranscribeReady) AppGraph.transcribeSettings as? AndroidTranscribeSettings else null
    fun transcribe(source: TranscribeSource) {
        val target = file ?: return
        val config = settings ?: return
        val callback = onMaterial
        scope.launch {
            busy = true; transcription=transcription.start();operationStarted=android.os.SystemClock.elapsedRealtime();operationElapsed=0;message = ""
            try {
                val text = withContext(Dispatchers.IO) {
                    val metadata = MediaMetadataRetriever()
                    val duration = try { metadata.setDataSource(target.absolutePath); metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0 } finally { metadata.release() }
                    val result = ConfiguredAsrProvider(context, config).recognize(source, AsrAudio(target, target.extension, duration, separateSpeakers = false), if (source == TranscribeSource.Local) "" else config.readKey(source)) {}
                    result.sentences.joinToString("\n") { "[${it.startMillis / 1000}s] ${it.text}" }
                }
                currentCoroutineContext().ensureActive()
                require(text.isNotBlank())
                callback("录音转写", text, target.absolutePath)
                val duration=(android.os.SystemClock.elapsedRealtime()-(operationStarted?:android.os.SystemClock.elapsedRealtime())).coerceAtLeast(0)
                transcription=transcription.complete(text,duration)
                val cleared=synchronized(app.resound.analysis.AnalysisStore.fileLock){prefs.edit().remove(ownerId).commit()}
                file = null; saved = false; message = if(cleared) "" else "转写已保存；待转写标记未清理，下次打开时请核对"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { transcription=transcription.copy(running=false);message = if (e is AsrException) e.detail else "转写或加入失败，原音保留，可重试" }
            finally { busy = false;transcription=transcription.copy(running=false);operationStarted=null }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RecordingWaveHeader(ownerName,level,recorder!=null,!paused&&recorder!=null,saved,onOpenProfile)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (recorder == null) "● 开始录音" else if (paused) "▶ 继续 · 长按保存" else "Ⅱ 暂停 · 长按保存",
                color = Rs.colors.accent, style = RsType.body,
                modifier = Modifier.weight(1f).background(Rs.colors.accent.copy(alpha=0.12f), RoundedCornerShape(24.dp)).combinedClickable(enabled = !busy, onLongClick = { finish() }, onClick = {
                    val active = recorder
                    if (active == null) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    } else runCatching { if (paused) active.resume() else active.pause(); paused = !paused }
                        .onFailure { message = "暂停或恢复失败，请长按保存原音" }
                }).padding(horizontal = 12.dp, vertical = 10.dp))
            FilledTonalButton(modifier = Modifier.testTag("analysis-local-transcribe"),
                enabled = !busy && saved && file != null && recorder == null && settings != null,
                onClick = { transcribe(TranscribeSource.Local) }) { Text("本地转写", style = RsType.body) }
        }
        Text(if (saved && file != null) "本地转写只在手机处理，不上传原音" else "先录音并长按保存，或导入音频后本地转写", style = RsType.caption, color = Rs.colors.textSecondary)
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(enabled = !busy && recorder == null, onClick = { picker.launch(arrayOf("audio/mp4", "audio/x-m4a", "audio/wav", "audio/x-wav", "audio/ogg", "audio/mpeg", "audio/mp3")) }) { Text("导入音频", style=RsType.body) }
            importCompanion?.invoke()
        }
        if(importing) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
            Text("正在导入音频 · 已用${operationElapsed/1000}秒",style=RsType.caption)
        }
        if (saved && file != null) {
            Text(if(transcription.running) "正在处理1份音频" else "1份音频待转写",style=RsType.subhead,color=Rs.colors.textSecondary)
            if (settings != null && settings.source.value != TranscribeSource.Local) {
                Text("使用当前服务转写会发送这份音频到已配置的转写服务", style = RsType.caption, color = Rs.colors.textSecondary)
                TextButton(enabled = !busy && recorder == null, onClick = { transcribe(settings.source.value) }) { Text("使用当前服务转写") }
            }
        }
        TranscriptionResult(transcription,operationElapsed,onAnalyze)
        if (message.isNotEmpty()) Text(message, style = RsType.caption, color = Rs.colors.textSecondary)
    }
}

@Composable
internal fun RecordingWaveHeader(name:String?,level:Float,active:Boolean,recording:Boolean,saved:Boolean,onOpenProfile:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth().testTag("analysis-recording-header"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        name?.let{Text(it,Modifier.widthIn(max=160.dp).then(
            if(onOpenProfile!=null) Modifier.testTag("person-profile-open").clickable(onClickLabel="打开档案",role=androidx.compose.ui.semantics.Role.Button,onClick=onOpenProfile).padding(vertical=6.dp)
            else Modifier),style=RsType.title,color=Rs.colors.text,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
        BreathingWave(if(recording)level else 0f,active,recording,Modifier.weight(1f).height(22.dp),tint=Rs.colors.accent)
        Text(when{recording->"录音中";active->"已暂停";saved->"原音已保存";else->"未录音"},style=RsType.caption,color=Rs.colors.textSecondary)
    }
}

/** One owned audio file, not the personal multi-segment processing queue. */
internal data class SingleAudioTranscriptionState(val running:Boolean=false,val text:String?=null,val completedMillis:Long?=null) {
    fun start()=copy(running=true,text=null,completedMillis=null)
    fun complete(value:String,millis:Long):SingleAudioTranscriptionState {
        require(value.isNotBlank() && millis>=0)
        return copy(running=false,text=value,completedMillis=millis)
    }
}
@Composable
internal fun TranscriptionResult(state:SingleAudioTranscriptionState,elapsedMillis:Long,onAnalyze:(()->Unit)?) {
    if(state.running)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
        Text("正在转写 · 已用${elapsedMillis/1000}秒",style=RsType.subhead)
    }
    state.text?.let { text ->
        Column(Modifier.fillMaxWidth().testTag("analysis-transcript-result"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(if(state.completedMillis!=null)"转写已保存 · 耗时${state.completedMillis/1000}秒" else "已保存的转写",style=RsType.subhead,color=Rs.colors.textSecondary)
            androidx.compose.foundation.text.selection.SelectionContainer { Text(text,style=RsType.body,color=Rs.colors.text) }
            if(onAnalyze!=null)FilledTonalButton(enabled=!state.running,onClick=onAnalyze){Text("现在分析",style=RsType.body)}
        }
    }
}
