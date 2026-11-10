package app.resound.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureLivenessTest {
    private val now = 100_000L
    private val clock: (Long) -> String = { it.toString() }

    @Test fun serviceHeartbeatDoesNotPretendAudioWasReceived() {
        val result = captureLiveText(CaptureStatus(CaptureState.Listening, 1), now, now, clock)
        assertEquals("等待音频", result.stateLabel)
        assertTrue(result.detail.contains("服务心跳 100000"))
        assertTrue(result.detail.contains("尚未收到音频帧"))
    }

    @Test fun stalledAudioKeepsItsRealTimestampInsteadOfAdvancingWithHeartbeat() {
        val status = CaptureStatus(CaptureState.Recording, 1, lastAudioFrameAt = now - 1000)
        val fresh = captureLiveText(status, now, now, clock)
        assertEquals("正在录人声", fresh.stateLabel)
        assertTrue(fresh.detail.contains("音频更新 99000"))
        val stale = captureLiveText(status, now + 30_000, now + 30_000, clock)
        assertEquals("等待音频恢复", stale.stateLabel)
        assertTrue(stale.detail.contains("最近音频 99000"))
        val busy = captureLiveText(status.copy(state = CaptureState.MicBusy, busyDetail = "通话中"), now, now, clock)
        assertEquals("麦克风暂被占用", busy.stateLabel)
        assertTrue(busy.detail.contains("通话中"))
    }

    @Test fun pauseAndBusyRemainAnEnabledSessionAndCustomDurationCannotSilentlyOverflow() {
        assertTrue(captureSessionEnabled(CaptureState.Paused))
        assertTrue(captureSessionEnabled(CaptureState.MicBusy))
        assertFalse(captureSessionEnabled(CaptureState.Stopped))
        assertFalse(captureSessionEnabled(CaptureState.NoPermission))
        assertEquals(20, capturePauseMinutes("20"))
        assertEquals(10080, capturePauseMinutes("10080"))
        assertNull(capturePauseMinutes("0"))
        assertNull(capturePauseMinutes("10081"))
        assertNull(capturePauseMinutes("99999999999999"))
    }
}
