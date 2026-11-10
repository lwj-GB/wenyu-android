package app.resound.audio

import app.resound.capture.CaptureTuning

/** 远讲/低增益时仅增强检测输入；保存的原始 PCM 不放大、不限幅。 */
data class VadSensitivity(val rmsGate: Float, val speechThreshold: Float, val minSpeechSamples: Int, val boostDetection: Boolean) {
    fun detectionGain(rms: Float): Float = if (!boostDetection || rms <= 0f) 1f else (0.02f / rms).coerceIn(1f, 8f)

    companion object {
        val DEFAULT = VadSensitivity(CaptureTuning.RMS_GATE, CaptureTuning.SPEECH_THRESHOLD, CaptureTuning.MIN_SPEECH_SAMPLES, false)
        // 手机也保留简短应答，弱声只在VAD输入中增强；不改变原音或声纹身份规则。
        val PHONE = VadSensitivity(0.0005f, CaptureTuning.SPEECH_THRESHOLD, CaptureTuning.SAMPLE_RATE * 400 / 1000, true)
        val EXTERNAL = VadSensitivity(0.0005f, 0.4f, CaptureTuning.SAMPLE_RATE * 400 / 1000, true)
        fun forInput(kind: InputKind) = if (kind == InputKind.Phone) PHONE else EXTERNAL
    }
}
