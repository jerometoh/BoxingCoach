package com.jerome.boxingcoach

/** Common surface WorkoutEngine talks to. Currently implemented by TtsManager
 *  (system TTS). Kept as an interface so an alternative engine could slot in
 *  behind CoachVoice without touching WorkoutEngine. */
interface SpeechEngine {
    var voiceMode: VoiceMode
    /** Speak a line. [rateScale] multiplies the base speaking rate for this
     *  utterance only (1f = normal; <1f slower, used to make round intros clearer).
     *  Engines that can't vary rate per-utterance may ignore it. */
    fun speak(text: String, rateScale: Float = 1f)
    fun cut()
    /** True while a line is still being spoken or is queued to be spoken. Lets the
     *  workout engine hold a round's commands until its intro has finished. */
    fun isSpeaking(): Boolean = false
}
