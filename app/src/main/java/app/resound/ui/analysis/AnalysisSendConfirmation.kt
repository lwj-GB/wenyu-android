package app.resound.ui.analysis

import android.content.Context
import android.content.SharedPreferences
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
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

private const val CONFIRMATION_KEY = "analysis-send-confirmation"

private fun saveSendReminder(prefs: SharedPreferences, enabled: Boolean): Boolean {
    val previous = prefs.getBoolean(CONFIRMATION_KEY, true)
    if (prefs.edit().putBoolean(CONFIRMATION_KEY, enabled).commit()) return true
    // SharedPreferences updates memory even when the disk commit fails.
    prefs.edit().putBoolean(CONFIRMATION_KEY, previous).commit()
    return false
}

@Composable
private fun rememberSendPreferences(): SharedPreferences {
    val context = LocalContext.current
    return remember(context) { context.getSharedPreferences("ui", Context.MODE_PRIVATE) }
}

@Composable
private fun rememberSendConfirmationPreference(): Pair<SharedPreferences, Boolean> {
    val prefs = rememberSendPreferences()
    var enabled by remember(prefs) { mutableStateOf(prefs.getBoolean(CONFIRMATION_KEY, true)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CONFIRMATION_KEY || key == null) enabled = prefs.getBoolean(CONFIRMATION_KEY, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        enabled = prefs.getBoolean(CONFIRMATION_KEY, true)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return prefs to enabled
}

/** Only the send-scope reminder can be skipped; the caller still validates its selected materials. */
@Composable
internal fun AnalysisSendConfirmation(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val prefs = rememberSendPreferences()
    val skipAtEntry = remember(prefs) { !prefs.getBoolean(CONFIRMATION_KEY, true) }
    val currentConfirm by rememberUpdatedState(onConfirm)
    var skipNext by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(skipAtEntry, enabled) {
        if (skipAtEntry && enabled && !submitted) {
            submitted = true
            currentConfirm()
        }
    }
    if (skipAtEntry) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(skipNext, { skipNext = it }, enabled = !submitted,
                        modifier = Modifier.testTag("analysis-send-do-not-remind"))
                    Text("不再提示", style = RsType.subhead)
                }
                Row(Modifier.fillMaxWidth().testTag("analysis-send-confirmation-actions"), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !submitted) { Text("取消") }
                    TextButton(enabled = enabled && !submitted, onClick = {
                        if (!enabled || submitted) return@TextButton
                        submitted = true
                        if (skipNext && !saveSendReminder(prefs, false)) {
                            submitted = false
                            error = "设置未保存，请重试"
                        } else {
                            currentConfirm()
                        }
                    }) { Text(confirmLabel) }
                }
            }
        },
    )
}

@Composable
internal fun AnalysisSendConfirmationSetting() {
    val (prefs, enabled) = rememberSendConfirmationPreference()
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("分析发送提醒", style = RsType.body, color = Rs.colors.text)
                Text(if (enabled) "发送人物材料前确认范围" else "已关闭，可在这里恢复提醒", style = RsType.subhead, color = Rs.colors.textSecondary)
            }
            Switch(enabled, onCheckedChange = { value ->
                error = if (saveSendReminder(prefs, value)) null else "设置未保存，请重试"
            }, modifier = Modifier.testTag("analysis-send-confirmation-setting"))
        }
        error?.let { Text(it, style = RsType.subhead, color = Rs.colors.critical) }
    }
}
