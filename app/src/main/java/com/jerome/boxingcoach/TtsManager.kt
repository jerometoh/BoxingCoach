package com.jerome.boxingcoach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks cues over whatever music is playing.
 *
 * DUCK_MUSIC: requests transient MAY_DUCK audio focus before speaking so Android
 * lowers Spotify / YouTube Music volume, then abandons focus when done.
 * OVERLAY: speaks on the music stream without requesting focus.
 * TEXT_ONLY: never speaks.
 *
 * Voice: the user can pick any installed English voice in Settings (stored by
 * voice name); empty selection auto-picks the highest-quality offline voice.
 * cut() flushes any speech in progress immediately (used by skip/stop).
 */
class TtsManager(context: Context) : SpeechEngine {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ready = false
    private val pending = AtomicInteger(0)
    override var voiceMode: VoiceMode = VoiceMode.DUCK_MUSIC

    // Music-duck focus is held across closely-spaced cues (bursts, back-to-back commands)
    // and released a beat after the last one, rather than per-utterance. This stops the
    // music volume thrashing AND stops the audio path re-warming on every command — the
    // re-warm is what clipped short onsets ("Go!" heard as "Oh!").
    @Volatile private var focusHeld = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val releaseRunnable = Runnable { doAbandonFocus() }
    // How long to keep the duck after the last utterance finishes.
    private val focusGraceMs = 1500L

    private var requestedVoiceName: String = ""

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { /* transient only; nothing to do */ }
        .build()

    // Known package names for sherpa-onnx-based TTS engines. If one is installed,
    // we bind our TextToSpeech to it directly so its neural voices are listed even
    // if the user hasn't made it the system default engine.
    //  - com.k2fsa.sherpa.onnx.tts.engine : official k2-fsa engine APK (one voice at a time)
    //  - org.woheller69.ttsengine        : "SherpaTTS" on F-Droid (multiple switchable voices)
    private val sherpaPackages = listOf(
        "org.woheller69.ttsengine",
        "com.k2fsa.sherpa.onnx.tts.engine",
        "com.k2fsa.sherpa.onnx",
    )

    private fun installedSherpaEngine(context: Context): String? {
        val pm = context.packageManager
        return sherpaPackages.firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
    }

    private val tts: TextToSpeech = run {
        val engine = installedSherpaEngine(context)
        val listener = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts.language = Locale.UK
                applyVoiceSelection()
                tts.setSpeechRate(BASE_RATE)
                tts.setPitch(0.92f)
                tts.setAudioAttributes(attrs)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = maybeReleaseFocus()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = maybeReleaseFocus()
                })
            }
        }
        if (engine != null) TextToSpeech(context, listener, engine)
        else TextToSpeech(context, listener)
    }

    /** All installed English voices, best quality first. Deliberately permissive:
     *  third-party engines (e.g. sherpa-onnx / Piper) report locales as "eng",
     *  "en_GB", "en-GB" etc. and sometimes set isNetworkConnectionRequired oddly,
     *  so we match any locale whose language starts with "en" and don't filter on
     *  the network flag. */
    fun availableVoices(): List<Voice> {
        if (!ready) return emptyList()
        return runCatching {
            tts.voices
                ?.filter {
                    val lang = it.locale.language.lowercase()
                    lang.startsWith("en")
                }
                ?.sortedByDescending { it.quality }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Set the voice by name ("" = auto best) and speak a short sample if requested. */
    fun setVoice(name: String, preview: Boolean = false) {
        requestedVoiceName = name
        applyVoiceSelection()
        if (preview) {
            cut() // stop any in-progress or queued preview so this one replaces it
            speak("Round two. One combo: jab, cross, left hook. On go, throw it.")
        }
    }

    private fun applyVoiceSelection() {
        if (!ready) return
        runCatching {
            val voices = availableVoices()
            val chosen = voices.firstOrNull { it.name == requestedVoiceName }
                ?: voices.firstOrNull()
            if (chosen != null) tts.voice = chosen
        }
    }

    override fun speak(text: String, rateScale: Float) {
        if (!ready || voiceMode == VoiceMode.TEXT_ONLY) return
        val duck = voiceMode == VoiceMode.DUCK_MUSIC
        var newlyDucked = false
        if (duck) {
            mainHandler.removeCallbacks(releaseRunnable)   // cancel any pending un-duck
            if (!focusHeld) {
                audioManager.requestAudioFocus(focusRequest)
                focusHeld = true
                newlyDucked = true
            }
        }
        // Rate is snapshotted by the engine at speak() time, so setting it right
        // before queuing applies to this utterance only.
        tts.setSpeechRate(BASE_RATE * rateScale)
        // Onset headroom: queue a brief silence before the real phoneme so the audio path —
        // and, on a fresh duck, the music-volume fade — doesn't swallow the start of a short
        // command ("Down!" -> "own"). Longer on a fresh duck (fade + stream warm), short
        // otherwise so bursts stay tight; the small always-on pad is what fixes clipping that
        // still happened after the grace period released the duck between commands.
        val warmMs = if (newlyDucked) 300L else 90L
        pending.incrementAndGet()
        tts.playSilentUtterance(warmMs, TextToSpeech.QUEUE_ADD, "warm-${System.nanoTime()}")
        pending.incrementAndGet()
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "cue-${System.nanoTime()}")
    }

    override fun isSpeaking(): Boolean = pending.get() > 0 || tts.isSpeaking

    /** Immediately cut any speech in progress and drop everything queued. */
    override fun cut() {
        mainHandler.removeCallbacks(releaseRunnable)
        tts.stop()
        pending.set(0)
        audioManager.abandonAudioFocusRequest(focusRequest)
        focusHeld = false
    }

    private fun maybeReleaseFocus() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            if (voiceMode == VoiceMode.DUCK_MUSIC) {
                // Keep the duck for a short grace so back-to-back commands don't thrash the
                // music volume or re-clip the next onset; un-duck if still idle after it.
                mainHandler.removeCallbacks(releaseRunnable)
                mainHandler.postDelayed(releaseRunnable, focusGraceMs)
            }
        }
    }

    private fun doAbandonFocus() {
        if (pending.get() <= 0) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            focusHeld = false
        }
    }

    fun shutdown() {
        cut()
        tts.shutdown()
    }

    companion object {
        /** Base speaking rate; per-utterance rateScale multiplies this. */
        const val BASE_RATE = 1.12f
    }
}
