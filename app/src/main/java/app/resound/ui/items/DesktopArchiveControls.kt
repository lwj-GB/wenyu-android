package app.resound.ui.items

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.archive.DesktopArchiveSelection
import app.resound.digest.MemoryRepository
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun DesktopArchiveChoice(itemId: Long) {
    val context = LocalContext.current
    val selection = remember(context) { DesktopArchiveSelection(context) }
    var selected by remember(itemId) { mutableStateOf(selection.isSelected(itemId)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(selected, { on ->
            runCatching { selection.setSelected(itemId, on) }.onSuccess { selected = on }
                .onFailure { Toast.makeText(context, "归档选择未保存，请重试", Toast.LENGTH_SHORT).show() }
        })
        Column(Modifier.weight(1f)) {
            Text("归档到电脑", style = RsType.body, color = Rs.colors.text)
            Text(if (selected) "下次导出时附上这条待办" else "这条待办仅保留在手机", style = RsType.caption, color = Rs.colors.textSecondary)
        }
    }
}

@Composable
internal fun DesktopArchiveButton(memory: MemoryRepository, date: LocalDate = LocalDate.now(), label: String = "导出到电脑", includeJournal: Boolean = false) {
    var opened by remember(date, includeJournal) { mutableStateOf(false) }
    Pill(label, filled = false) { opened = true }
    if (opened) DesktopArchiveSheet(memory, date, includeJournal) { opened = false }
}

@Composable
private fun DesktopArchiveSheet(memory: MemoryRepository, date: LocalDate, includeJournal: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { app.resound.archive.DesktopReceiverSettings(context) }
    val sender = remember { app.resound.archive.DesktopArchiveSender() }
    var address by remember { mutableStateOf(settings.address) }
    var code by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<app.resound.archive.DesktopArchiveSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun prepare() {
        busy = true; message = null; success = false
        operation = scope.launch {
            try {
                check(AppGraph.isTranscribeReady) { "转写尚未就绪，请稍后再试" }
                snapshot = app.resound.archive.prepareDesktopArchive(context, memory, AppGraph.transcripts, date, includeJournal)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "归档快照未生成，请稍后重试；手机资料保留" }
            finally { busy = false }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { prepare() }
    androidx.compose.runtime.DisposableEffect(sender) { onDispose { operation?.cancel(); sender.close() } }
    fun close() { operation?.cancel(); code = ""; onClose() }
    app.resound.ui.verify.RsSheet("归档到电脑 · $date", onDismiss = ::close) {
        Text(if (includeJournal)
            "发送当天原话、补充说明、AI整理的日记与笔记、已授权读取的使用/通知/运动统计，以及逐条勾选的待办。"
            else "发送当天原话、补充说明和逐条勾选的待办。",
            style = RsType.subhead, color = Rs.colors.textSecondary)
        Text("日记和原话仍可能提及未勾选事项；不发送原始录音。", style = RsType.caption, color = Rs.colors.textSecondary)
        snapshot?.let { Text("本次快照：${it.lineCount} 条原话 · ${it.todoCount} 条所选待办" + if (it.hasJournal) " · 含日记统计" else "",
            style = RsType.subhead, color = Rs.colors.text) }
        Text("先在电脑的“手机”页开启接收，再填写地址与配对码。手机和电脑需在同一Wi-Fi；USB连接可使用调试转发地址。", style = RsType.caption, color = Rs.colors.textSecondary)
        app.resound.ui.settings.SettingsInput(address, { if (!busy) { address = it; success = false; message = null } },
            "http://192.168.1.2:端口", Modifier.fillMaxWidth())
        app.resound.ui.settings.SettingsInput(code, { if (!busy) { code = it; success = false; message = null } },
            "电脑显示的8位配对码", Modifier.fillMaxWidth(), secret = true)
        Text("配对码仅用于本次接收，不保存。局域网HTTP未加密，请使用可信网络。", style = RsType.caption, color = Rs.colors.textTertiary)
        message?.let { Text(it, style = RsType.subhead, color = if (success) Rs.colors.accent else Rs.colors.critical) }
        app.resound.ui.verify.ActionButton(if (busy) (if (snapshot == null) "生成快照中…" else "正在等待电脑确认…") else if (success) "再次核对归档" else "发送到电脑",
            app.resound.ui.verify.ActionKind.Primary, enabled = !busy && snapshot != null) {
            val fixed = snapshot ?: return@ActionButton
            operation = scope.launch {
                busy = true; message = null; success = false
                try {
                    val target = app.resound.archive.desktopReceiverAddress(address)
                    require(code.trim().matches(Regex("[0-9]{8}"))) { "请输入电脑显示的8位配对码" }
                    settings.saveAddress(target)
                    val receipt = sender.send(target, code, fixed)
                    success = true
                    message = if (receipt.alreadyExists) "电脑已存在相同归档，无需重复保存。可在电脑“手机”页查看。"
                        else "电脑已确认保存，可在电脑“手机”页查看这一天。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "发送未完成；手机资料保留，可重试" }
                finally { busy = false }
            }
        }
        if (snapshot == null && !busy) app.resound.ui.verify.ActionButton("重新生成快照", app.resound.ui.verify.ActionKind.Secondary, onClick = ::prepare)
        app.resound.ui.verify.ActionButton("其他方式分享", app.resound.ui.verify.ActionKind.Secondary, enabled = !busy && snapshot != null) {
            val fixed = snapshot ?: return@ActionButton
            operation = scope.launch {
                busy = true
                try {
                    val uri = app.resound.archive.shareDesktopArchive(context, fixed)
                    val send = Intent(Intent.ACTION_SEND).setType("application/json")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .apply { clipData = ClipData.newRawUri("手机归档", uri) }
                    context.startActivity(Intent.createChooser(send, "发送手机归档"))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "分享未打开；手机资料保留，可重试" }
                finally { busy = false }
            }
        }
        app.resound.ui.verify.ActionButton(if (busy) "取消本次操作" else "关闭", app.resound.ui.verify.ActionKind.Secondary, onClick = ::close)
    }
}
