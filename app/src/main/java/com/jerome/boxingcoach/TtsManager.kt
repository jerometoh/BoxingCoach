package com.jerome.boxingcoach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
 * TTS uses USAGE_MEDIA audio attributes so output follows the music route
 * (Bluetooth earbuds/speaker) rather than defaulting to the phone speaker.
 */
class TtsManager(context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ready = false
    private val pending = AtomicInteger(0)
    var voiceMode: VoiceMode = VoiceMode.DUCK_MUSIC

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { /* transient only; nothing to do */ }
        .build()

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.UK
            tts.setAudioAttributes(attrs)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = maybeReleaseFocus()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = maybeReleaseFocus()
            })
        }
    }

    fun speak(text: String) {
        if (!ready || voiceMode == VoiceMode.TEXT_ONLY) return
        if (voiceMode == VoiceMode.DUCK_MUSIC) {
            audioManager.requestAudioFocus(focusRequest)
        }
        pending.incrementAndGet()
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "cue-${System.nanoTime()}")
    }

    fun stop() {
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
        stop()
        tts.shutdown()
    }
}
