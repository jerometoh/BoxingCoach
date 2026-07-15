package com.jerome.boxingcoach

import android.content.Context

/**
 * Voice provider facade. WorkoutEngine only ever talks to WorkoutEngine.tts
 * (set to [active]); it doesn't need to know which engine is live.
 *
 * Engine priority:
 *  - ElevenLabs (expressive cloud coach voice) when the user has entered an API
 *    key. It internally falls back to system TTS per-cue if offline / the API
 *    errors, so a workout is never silent.
 *  - System TTS (TtsManager) otherwise — which itself surfaces any installed
 *    neural engine's voices (sherpa-onnx / SherpaTTS) via the voice picker.
 *
 * History: an embedded neural voice (sherpa-onnx / Piper) bundled in the APK
 * was tried but crashed at native load on the Pixel 9 Pro; removed. The external
 * sherpa TTS *engine* app works fine and is reachable through TtsManager.
 */
object CoachVoice {
    var systemEngine: TtsManager? = null
        private set
    var elevenLabs: ElevenLabsEngine? = null
        private set

    val active: SpeechEngine? get() = elevenLabs?.takeIf { it.isConfigured() } ?: systemEngine
    val usingEleven: Boolean get() = elevenLabs?.isConfigured() == true

    // Kept for call-site compatibility with older code paths.
    val usingEmbedded: Boolean = false
    val embeddedLoadFailed: Boolean = false

    fun init(context: Context, attemptEmbedded: Boolean = false) {
        if (systemEngine == null) systemEngine = TtsManager(context)
        if (elevenLabs == null) elevenLabs = ElevenLabsEngine(context, fallback = systemEngine!!)
        WorkoutEngine.tts = active
    }

    /** Apply ElevenLabs settings (key/voice/enabled) and re-point the active engine. */
    fun configureEleven(apiKey: String, voiceId: String, enabled: Boolean = true) {
        elevenLabs?.apiKey = apiKey
        elevenLabs?.enabled = enabled
        if (voiceId.isNotBlank()) elevenLabs?.voiceId = voiceId
        WorkoutEngine.tts = active
    }
}
