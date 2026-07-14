package com.jerome.boxingcoach

import android.content.Context

/**
 * Picks the embedded neural voice if a Piper model is bundled and loads
 * successfully; otherwise uses the phone's system TTS. WorkoutEngine only ever
 * talks to [active]/WorkoutEngine.tts via the SpeechEngine interface — it
 * doesn't need to know which one is live.
 *
 * IMPORTANT: loading the embedded model (ONNX Runtime init on a ~15-60MB neural
 * net) can take real time — long enough to freeze the UI if done on the main
 * thread, which is exactly what happened in the version before this comment.
 * init() now returns immediately: the system engine is ready right away so the
 * app opens instantly, and the embedded model loads on a background thread,
 * hot-swapping into WorkoutEngine.tts only once it's actually ready.
 */
object CoachVoice {
    var systemEngine: TtsManager? = null
        private set
    @Volatile var embeddedEngine: EmbeddedTts? = null
        private set
    @Volatile var embeddedLoadFailed: Boolean = false
        private set

    val active: SpeechEngine? get() = embeddedEngine ?: systemEngine
    val usingEmbedded: Boolean get() = embeddedEngine != null

    /** Non-blocking. Safe to call repeatedly — no-ops if already loaded/loading/disabled. */
    fun init(context: Context, attemptEmbedded: Boolean) {
        if (systemEngine == null) systemEngine = TtsManager(context)
        WorkoutEngine.tts = active // system engine immediately, so the app is usable right away

        if (!attemptEmbedded) return
        if (embeddedEngine == null && !embeddedLoadFailed) {
            val appContext = context.applicationContext
            Thread({
                val loaded = EmbeddedTts.tryCreate(appContext)
                if (loaded != null) {
                    embeddedEngine = loaded
                    WorkoutEngine.tts = loaded // hot-swap once ready; no-op if a workout never asked for tts
                } else {
                    embeddedLoadFailed = true
                }
            }, "EmbeddedTtsLoader").apply { isDaemon = true }.start()
        }
    }
}
