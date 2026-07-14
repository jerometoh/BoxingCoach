package com.jerome.boxingcoach

import android.content.Context

/**
 * Voice provider. Currently system TTS only.
 *
 * History: an embedded neural voice (sherpa-onnx / Piper) was tried here but
 * crashed at native-library load on the target device — reproducibly, across
 * two different model sizes — and couldn't be diagnosed without a device stack
 * trace we couldn't capture. It was removed rather than left as a crash risk.
 * The [usingEmbedded]/[embeddedLoadFailed] flags remain (always false) so the
 * rest of the app compiles unchanged; a future embedded engine could slot back
 * in behind this same facade.
 */
object CoachVoice {
    var systemEngine: TtsManager? = null
        private set

    val active: SpeechEngine? get() = systemEngine
    val usingEmbedded: Boolean = false
    val embeddedLoadFailed: Boolean = false

    /** Non-blocking. Safe to call repeatedly. attemptEmbedded is accepted for
     *  call-site compatibility but currently ignored (no embedded engine). */
    fun init(context: Context, attemptEmbedded: Boolean = false) {
        if (systemEngine == null) systemEngine = TtsManager(context)
        WorkoutEngine.tts = active
    }
}
