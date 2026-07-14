package com.jerome.boxingcoach

/** Common surface WorkoutEngine talks to — implemented by EmbeddedTts (preferred,
 *  bundled neural voice, no phone setup) and TtsManager (system TTS fallback). */
interface SpeechEngine {
    var voiceMode: VoiceMode
    fun speak(text: String)
    fun cut()
}
