package app.resound.capture

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class CaptureLiveText(val stateLabel: String, val detail: String)

/** 只解释已经收到的样本与心跳；绝不用通知刷新时刻冒充音频时刻。 */
internal fun captureLiveText(status: CaptureStatus?, heartbeatAt: Long?, now: Long,
    clock: (Long) -> String = { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("H:mm:ss")) },
): CaptureLiveText {
    val frame = status?.lastAudioFrameAt?.takeIf { it in 1L..now }
    val beat = heartbeatAt?.takeIf { it in 1L..now }
    val heartbeat = beat?.let { "服务心跳 ${clock(it)}" } ?: "等待服务心跳"
    return when (status?.state) {
        CaptureState.Listening, CaptureState.Recording -> when {
            frame == null -> CaptureLiveText("等待音频", "$heartbeat · 尚未收到音频帧")
            now - frame > 10_000L -> CaptureLiveText("等待音频恢复", "最近音频 ${clock(frame)} · $heartbeat")
            else -> CaptureLiveText(if (status.state == CaptureState.Recording) "正在录人声" else "正在收音",
                "音频更新 ${clock(frame)} · " + if (status.state == CaptureState.Recording) "人声保存中" else "安静时不保存")
        }
        CaptureState.MicBusy -> CaptureLiveText("麦克风暂被占用", (status.busyDetail ?: "等待其他应用释放后自动恢复") + " · $heartbeat")
        CaptureState.Paused -> CaptureLiveText("已暂停", "暂停期间不收音 · $heartbeat")
        CaptureState.NoPermission -> CaptureLiveText("缺少权限", "请打开应用，允许麦克风和通知")
        CaptureState.Stopped -> CaptureLiveText("尚未收音", "打开应用开始录音")
        null -> CaptureLiveText("正在启动", "等待真实音频帧")
    }
}

/** 已暂停或让出麦克风时仍是一轮已开启的采集，主按钮应停止而不是再开一轮。 */
internal fun captureSessionEnabled(state: CaptureState) = state != CaptureState.Stopped && state != CaptureState.NoPermission
internal fun capturePauseMinutes(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..10080 }
