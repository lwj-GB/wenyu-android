package app.resound.ui.today

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.resound.capture.CaptureController
import app.resound.capture.CaptureNotifications
import app.resound.capture.CaptureState
import app.resound.capture.capturePauseMinutes
import app.resound.capture.captureSessionEnabled
import app.resound.ui.items.Choice
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 日期栏上的直接控制；只调用已有采集控制器，不复制暂停计时或录音状态。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CaptureQuickControls(capture: CaptureController) {
    val state by remember(capture) { capture.status.map { it.state }.distinctUntilChanged() }
        .collectAsState(initial = capture.status.value.state)
    val context = LocalContext.current
    val enabled = captureSessionEnabled(state)
    val paused = state == CaptureState.Paused
    var pauseOpen by remember { mutableStateOf(false) }
    var permissionIssue by remember { mutableStateOf(false) }
    var pendingResume by rememberSaveable { mutableStateOf(false) }
    val permissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingResume) capture.resume() else capture.start()
        permissionIssue = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !CaptureNotifications.permitted(context)
    }
    fun begin(resume: Boolean) {
        pendingResume = resume
        val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        when {
            missing.isNotEmpty() -> launcher.launch(missing.toTypedArray())
            !CaptureNotifications.permitted(context) -> permissionIssue = true
            resume -> capture.resume()
            else -> capture.start()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        QuickCaptureButton(if (enabled) "长按1.5秒关闭聆听" else "开始聆听", if (enabled) CaptureGlyph.Stop else CaptureGlyph.Play,
            primary = true, selected = enabled,
            onLongClick = if (enabled) ({ if (captureSessionEnabled(capture.status.value.state)) capture.stop() }) else null) {
            if (enabled) Toast.makeText(context, "长按1.5秒关闭聆听；旁边可选择暂停时长", Toast.LENGTH_SHORT).show()
            else begin(false)
        }
        QuickCaptureButton("选择暂停时长", CaptureGlyph.Pause,
            enabled = enabled) { pauseOpen = true }
    }
    if (pauseOpen) {
        var minutes by remember { mutableStateOf("30") }
        var custom by remember { mutableStateOf(false) }
        val duration = capturePauseMinutes(minutes)
        RsSheet("暂停录音", "到期自动恢复；也可随时点恢复按钮。", onDismiss = { pauseOpen = false }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(20, 30, 60, 120).forEach { value ->
                    Choice("$value 分钟", !custom && minutes == value.toString()) { minutes = value.toString(); custom = false }
                }
            }
            Choice("自定义时长", custom) { custom = true }
            if (custom) {
                SheetInput(minutes, "暂停分钟数") { minutes = it.filter(Char::isDigit).take(5) }
                Text("可填 1～10080 分钟（最长 7 天）", style = RsType.caption,
                    color = if (duration == null) Rs.colors.warning else Rs.colors.textSecondary)
            }
            Spacer(Modifier.height(4.dp))
            ActionButton(duration?.let { "暂停 $it 分钟" } ?: "填写有效时长", ActionKind.Primary, enabled = duration != null && enabled) {
                duration?.let { if (captureSessionEnabled(capture.status.value.state)) capture.pause(it) }
                pauseOpen = false
            }
            if (paused) ActionButton("现在恢复", ActionKind.Secondary) { begin(true); pauseOpen = false }
            ActionButton("取消", ActionKind.Secondary) { pauseOpen = false }
        }
    }
    if (permissionIssue) RsSheet("录音尚未开始", "需要允许麦克风和通知，才能收音并在通知中心显示真实状态。", onDismiss = { permissionIssue = false }) {
        ActionButton("打开权限设置", ActionKind.Primary) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            permissionIssue = false
        }
        ActionButton("取消", ActionKind.Secondary) { permissionIssue = false }
    }
}

private enum class CaptureGlyph { Play, Pause, Stop }

@Composable
private fun QuickCaptureButton(label: String, glyph: CaptureGlyph, primary: Boolean = false, selected: Boolean = false,
    enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = Rs.colors
    val ink = if (!enabled) colors.textTertiary else if (primary && selected) Color.White else colors.accent
    val systemConfiguration = LocalViewConfiguration.current
    val configuration = remember(systemConfiguration) {
        object : ViewConfiguration by systemConfiguration {
            override val longPressTimeoutMillis = 1500L
        }
    }
    // 只改变这个关闭按钮的长按阈值；保留原生取消、拖出和释放后的点击抑制。
    CompositionLocalProvider(LocalViewConfiguration provides configuration) {
        Box(Modifier.size(if (primary) 48.dp else 40.dp).clip(CircleShape)
            .background(if (primary && selected) colors.accent else colors.accentSoft)
            .semantics { contentDescription = label; role = Role.Button; if (!enabled) disabled() }
            .then(if (onLongClick != null) Modifier.combinedClickable(
                enabled = enabled, role = Role.Button, onClick = onClick,
                onLongClickLabel = "关闭聆听", onLongClick = onLongClick,
            ) else Modifier.springClick { if (enabled) onClick() }), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(if (primary) 25.dp else 18.dp)) {
                when (glyph) {
                    CaptureGlyph.Play -> drawPath(Path().apply {
                        moveTo(size.width * 0.25f, size.height * 0.1f)
                        lineTo(size.width * 0.9f, size.height * 0.5f)
                        lineTo(size.width * 0.25f, size.height * 0.9f)
                        close()
                    }, ink)
                    CaptureGlyph.Stop -> drawRoundRect(ink, Offset(size.width * 0.15f, size.height * 0.15f),
                        Size(size.width * 0.7f, size.height * 0.7f), CornerRadius(3.dp.toPx()))
                    CaptureGlyph.Pause -> for (x in listOf(0.2f, 0.6f)) drawRoundRect(ink,
                        Offset(size.width * x, size.height * 0.1f), Size(size.width * 0.2f, size.height * 0.8f), CornerRadius(1.dp.toPx()))
                }
            }
        }
    }
}
