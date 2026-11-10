package app.resound.ui.transcript

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.transcribe.ConversationContextStore
import app.resound.transcribe.SegmentTranscript
import app.resound.ui.redo.RedoSheet
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.Pill
import app.resound.ui.verify.springClick
import androidx.compose.foundation.layout.padding
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.formatClock

/** 每次明确选中一段录音，不把一句“今天和谁聊”自动套用到全天。 */
@Composable
internal fun ConversationContextControl(segment: SegmentTranscript, eventIds: List<Long>, compact: Boolean = false) {
    val context = LocalContext.current
    val store = remember(context) { ConversationContextStore(context) }
    var note by remember(segment.segmentId) { mutableStateOf(store.get(segment.segmentId)) }
    var editing by remember { mutableStateOf(false) }
    var redo by remember { mutableStateOf(false) }
    Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
    note?.let {
        Text("用户补充：${listOf(it.participants, it.background).filter(String::isNotBlank).joinToString("；")}",
            style = RsType.caption, color = Rs.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (compact) Text(if (note == null) "补充对话信息" else "修改对话信息", style = RsType.caption,
        color = Rs.colors.accent, modifier = Modifier.springClick { editing = true }.padding(vertical = 6.dp))
    else Pill(if (note == null) "补充对话信息" else "修改对话信息", filled = false) { editing = true }
    if (editing) {
        var participants by remember { mutableStateOf(note?.participants.orEmpty()) }
        var background by remember { mutableStateOf(note?.background.orEmpty()) }
        fun save(): Boolean = runCatching { store.save(segment.segmentId, participants, background) }
            .onSuccess { note = store.get(segment.segmentId); editing = false }
            .onFailure { Toast.makeText(context, it.message ?: "补充信息未保存，请重试", Toast.LENGTH_SHORT).show() }.isSuccess
        RsSheet("补充对话信息", "仅用于这段录音 ${formatClock(segment.startAt)}—${formatClock(segment.endAt)}。可打字，也可使用手机输入法的语音输入。", onDismiss = { editing = false }) {
            Text("和谁交谈", style = RsType.caption, color = Rs.colors.textSecondary)
            SheetInput(participants, "例如：我和小林，同事讨论项目") { if (it.length <= 200) participants = it }
            Text("背景与补充", style = RsType.caption, color = Rs.colors.textSecondary)
            SheetInput(background, "例如：在讨论下周的安排；某句话是在引用别人的说法", wrap = true) { if (it.length <= 2000) background = it }
            Text("这是你的补充说明，不会修改录音原话或声纹身份。清空两栏并保存可移除补充。",
                style = RsType.caption, color = Rs.colors.textSecondary)
            ActionButton("保存", ActionKind.Primary) { save() }
            if (segment.lines.isNotEmpty() && eventIds.isNotEmpty() && AppGraph.isRedoReady) {
                Text("重新整理会更新这段相关事件及未确认待办；下一步可核对模型和影响范围。",
                    style = RsType.caption, color = Rs.colors.textSecondary)
                ActionButton("保存并查看重新整理", ActionKind.Secondary) {
                    if (save()) redo = true
                }
            } else {
                Text("补充会在这段录音下次正常整理时使用。", style = RsType.caption, color = Rs.colors.textSecondary)
            }
        }
    }
    if (redo) RedoSheet(eventIds = eventIds) { redo = false }
}
