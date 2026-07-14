package com.jerome.boxingcoach

import android.content.Context

/**
 * Picks the embedded neural voice if a Piper model is bundled and loads
 * successfully; otherwise falls back to the phone's system TTS. WorkoutEngine
 * only ever talks to [active] via the SpeechEngine interface — it doesn't need
 * to know which one is live. Settings reads [usingEmbedded] to decide whether
 * to show the system voice picker (irrelevant when the embedded voice is active).
 */
object CoachVoice {
    var systemEngine: TtsManager? = null
        private set
    var embeddedEngine: EmbeddedTts? = null
        private set

    val active: SpeechEngine? get() = embeddedEngine ?: systemEngine
    val usingEmbedded: Boolean get() = embeddedEngine != null

    fun init(context: Context) {
        if (systemEngine == null) systemEngine = TtsManager(context)
        if (embeddedEngine == null) embeddedEngine = EmbeddedTts.tryCreate(context)
    }
}
