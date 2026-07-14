package com.jerome.boxingcoach

/** Common surface WorkoutEngine talks to. Currently implemented by TtsManager
 *  (system TTS). Kept as an interface so an alternative engine could slot in
 *  behind CoachVoice without touching WorkoutEngine. */
interface SpeechEngine {
    var voiceMode: VoiceMode
    fun speak(text: String)
    fun cut()
}
