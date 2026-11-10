package app.resound.ui.analysisrecording

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.transcribe.SegmentTranscript
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId

/** Stable personal segment identity, never inferred from a person's name. */
data class SelectedPersonalTranscript(val segmentId: Long, val startAt: Long, val endAt: Long, val text: String, val sourceKind: String = "personal-transcript",
    val title:String?=null, val summary:String?=null, val scene:String?=null, val details:List<String> = emptyList(), val categories:List<String> = emptyList()) {
    val sourceKey: String get() = "$sourceKind:$segmentId"
}

internal fun selectedPersonalTranscripts(segments: List<SegmentTranscript>, selected: Set<Long>): List<SelectedPersonalTranscript> =
    segments.filter { it.segmentId in selected && it.lines.any { line -> line.text.isNotBlank() } }
        .distinctBy { it.segmentId }.sortedBy { it.startAt }.map { segment ->
            SelectedPersonalTranscript(segment.segmentId, segment.startAt, segment.endAt,
                segment.lines.filter { it.text.isNotBlank() }.joinToString("\n") { line ->
                    val time = Instant.ofEpochMilli(line.startAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
                    "[$time] ${line.speakerName?.plus("：").orEmpty()}${line.text}"
                })
        }

internal fun personalMemoryChoices(events: List<app.resound.digest.MemoryEvent>): List<SelectedPersonalTranscript> =
    events.distinctBy { it.id }.sortedBy { it.startAt }.map { event ->
        SelectedPersonalTranscript(event.id,event.startAt,event.endAt,
            "个人记忆整理（AI整理，需核对）\n${event.scene} · ${event.title}\n${event.summary}\n" +
            event.points.joinToString("\n") { "• $it" } + "\n" + event.items.joinToString("\n") { "${it.category}：${it.content}" }, "personal-memory", event.title, event.summary, event.scene, event.points + event.items.map { "${it.category}：${it.content}" }, event.items.map { it.category }.distinct())
    }

internal fun selectedPersonalMemories(events: List<app.resound.digest.MemoryEvent>, selected: Set<Long>) =
    personalMemoryChoices(events).filter { it.segmentId in selected }

internal fun toggleAllMemories(available: List<SelectedPersonalTranscript>, selected: Set<Long>): Set<Long> {
    val ids=available.map { it.segmentId }.toSet()
    return if(ids.isNotEmpty() && selected.containsAll(ids)) emptySet() else ids
}

@Composable
fun PersonalTranscriptImportControl(ownerId: String, ownerName: String, onImport: (List<SelectedPersonalTranscript>) -> Unit) {
    key(ownerId) { PersonalTranscriptImport(ownerName, onImport) }
}

@Composable
private fun PersonalTranscriptImport(ownerName: String, onImport: (List<SelectedPersonalTranscript>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var selected by remember(date) { mutableStateOf(setOf<Long>()) }
    var preview by remember(date) { mutableStateOf<List<SelectedPersonalTranscript>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var complete by remember { mutableStateOf<String?>(null) }
    FilledTonalButton(enabled = AppGraph.isMemoryReady, onClick = { open = true; error = null; complete = null }) { Text("从个人记忆更新", style = RsType.body) }
    complete?.let { Text(it, style = RsType.caption, color = Rs.colors.textSecondary) }
    if (!open || !AppGraph.isMemoryReady) return
    val events by remember(date) { AppGraph.memory.events(date) }.collectAsState(initial = emptyList())
    val available = personalMemoryChoices(events)
    LaunchedEffect(available) { selected = selected.intersect(available.map { it.segmentId }.toSet()) }
    val snapshot = preview
    AlertDialog(onDismissRequest = { open = false; preview = null }, title = { Text(if (snapshot == null) "选择个人记忆" else "导入到${ownerName}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (snapshot == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { date = date.minusDays(1) }) { Text("前一天") }
                        Text(date.toString(), modifier = Modifier.padding(top = 12.dp))
                        TextButton(enabled = date < LocalDate.now(), onClick = { date = date.plusDays(1) }) { Text("后一天") }
                    }
                    if (available.isEmpty()) Text("这一天还没有整理好的记忆，请先在个人模式整理。")
                    if (available.isNotEmpty()) TextButton(onClick = { selected = toggleAllMemories(available, selected) }) { Text(if (selected.size == available.size) "取消全选" else "全选当天记忆") }
                    available.forEach { segment ->
                        Row {
                            Checkbox(segment.segmentId in selected, onCheckedChange = { checked ->
                                selected = if (checked) selected + segment.segmentId else selected - segment.segmentId
                            })
                            Column(Modifier.weight(1f)) {
                                PersonalMemoryChoiceBody(segment,false)
                            }
                        }
                    }
                } else {
                    Text("已选${snapshot.size}件事 · ${snapshot.sumOf { it.text.length }}字",style=RsType.subhead,color=Rs.colors.textSecondary)
                    snapshot.forEach { PersonalMemoryChoiceBody(it,true); HorizontalDivider(color=Rs.colors.separator) }
                }
                error?.let { Text(it, color = Rs.colors.critical) }
            }
        }, confirmButton = {
            TextButton(enabled = snapshot != null || selected.isNotEmpty(), onClick = {
                if (snapshot == null) preview = selectedPersonalMemories(events, selected)
                else try {
                    onImport(snapshot)
                    open = false; preview = null; selected = emptySet(); complete = "所选记忆已导入"
                } catch (_: Exception) { error = "导入失败，请重试" }
            }) { Text(if (snapshot == null) "预览范围" else "确认导入") }
        }, dismissButton = { TextButton(onClick = { if (snapshot != null) preview = null else open = false }) { Text(if (snapshot != null) "返回选择" else "取消") } })
}

@Composable
internal fun PersonalMemoryChoiceBody(item:SelectedPersonalTranscript,preview:Boolean) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(item.title?:"个人原话",style=RsType.headline,color=Rs.colors.text)
        val time=Instant.ofEpochMilli(item.startAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        Text((listOf(time)+listOfNotNull(item.scene)+item.categories).distinct().joinToString(" · "),style=RsType.caption,color=Rs.colors.textSecondary)
        Text(item.summary?:item.text,style=RsType.subhead,color=Rs.colors.textSecondary,maxLines=if(preview)Int.MAX_VALUE else 3)
        if(preview)item.details.forEach { Text(it,style=RsType.body,color=Rs.colors.text) }
    }
}
