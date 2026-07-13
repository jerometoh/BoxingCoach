package com.jerome.boxingcoach

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Experimental hands-free control: continuously listens for
 * "pause", "resume"/"go", "skip"/"next", "repeat".
 *
 * Honest limitation: on-device speech recognition competes with loud music and
 * bag noise, so accuracy will vary. Off by default in Settings. Large on-screen
 * pause/skip buttons remain the reliable fallback.
 */
class VoiceCommands(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var active = false

    fun start() {
        if (active || !SpeechRecognizer.isRecognitionAvailable(context)) return
        active = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val words = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ")?.lowercase() ?: ""
                    handle(words)
                    restart()
                }
                override fun onError(error: Int) = restart()
                override fun onEndOfSpeech() {}
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        listen()
    }

    private fun handle(words: String) {
        when {
            "pause" in words || "stop" in words -> WorkoutEngine.pause()
            "resume" in words || "go" in words || "continue" in words -> WorkoutEngine.resume()
            "skip" in words || "next" in words -> WorkoutEngine.skip()
            "repeat" in words || "again" in words -> WorkoutEngine.repeatCue()
        }
    }

    private fun listen() {
        if (!active) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { recognizer?.startListening(intent) }
    }

    private fun restart() {
        if (active) listen()
    }

    fun stop() {
        active = false
        runCatching {
            recognizer?.stopListening()
            recognizer?.destroy()
        }
        recognizer = null
    }
}
