package com.jerome.boxingcoach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
        if (voiceMode == VoiceMode.DUCK_MUSIC) {
            audioManager.requestAudioFocus(focusRequest)
        }
        // Rate is snapshotted by the engine at speak() time, so setting it right
        // before queuing applies to this utterance only.
        tts.setSpeechRate(BASE_RATE * rateScale)
        pending.incrementAndGet()
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "cue-${System.nanoTime()}")
    }

    override fun isSpeaking(): Boolean = pending.get() > 0 || tts.isSpeaking

    /** Immediately cut any speech in progress and drop everything queued. */
    override fun cut() {
        tts.stop()
        pending.set(0)
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun maybeReleaseFocus() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            if (voiceMode == VoiceMode.DUCK_MUSIC) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
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
