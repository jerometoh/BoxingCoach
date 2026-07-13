package com.jerome.boxingcoach

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class WorkoutPhase { IDLE, RUNNING, PAUSED, FINISHED }

data class WorkoutState(
    val phase: WorkoutPhase = WorkoutPhase.IDLE,
    val sectionIndex: Int = 0,
    val roundIndex: Int = 0,
    val secondsLeft: Int = 0,
    val currentCue: String = "",
    val currentCueIsCommand: Boolean = false,
    val sectionTitle: String = "",
    val roundLabel: String = "",
    val isRest: Boolean = false,
    val elapsedTotal: Int = 0,
)

/**
 * Singleton engine holding the live workout. The foreground service keeps the
 * process alive; UI observes [state]. Survives activity recreation.
 */
object WorkoutEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(WorkoutState())
    val state: StateFlow<WorkoutState> = _state

    var tts: TtsManager? = null
    var restCoaching: Boolean = true
    var warnSound: Boolean = true
    var endBell: Boolean = true
    private var routine: Routine? = null
    private var tone: ToneGenerator? = null

    fun start(r: Routine) {
        stopInternal()
        routine = r
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
        job = scope.launch { run(r) }
    }

    fun pause() {
        if (_state.value.phase == WorkoutPhase.RUNNING) {
            _state.value = _state.value.copy(phase = WorkoutPhase.PAUSED)
            tts?.stop()
        }
    }

    fun resume() {
        if (_state.value.phase == WorkoutPhase.PAUSED) {
            _state.value = _state.value.copy(phase = WorkoutPhase.RUNNING)
        }
    }

    /** Skip to the next round (or finish). */
    @Volatile private var skipFlag = false
    fun skip() { skipFlag = true }

    fun repeatCue() {
        val c = _state.value.currentCue
        if (c.isNotBlank()) tts?.speak(c)
    }

    fun stop() {
        stopInternal()
        _state.value = WorkoutState(phase = WorkoutPhase.IDLE)
    }

    private fun stopInternal() {
        job?.cancel(); job = null
        tts?.stop()
        tone?.release(); tone = null
    }

    private suspend fun run(r: Routine) {
        var elapsed = 0
        val rng = Random(System.nanoTime())
        for ((si, section) in r.sections.withIndex()) {
            for ((ri, round) in section.rounds.withIndex()) {
                if (endBell) bell()
                val announce = if (round.isRest) {
                    val tip = if (restCoaching) " " + ComboLibrary.restTips.random(rng) else ""
                    "Rest. ${round.durationSec} seconds.$tip"
                } else {
                    "${round.label}. ${niceDuration(round.durationSec)}."
                }
                tts?.speak(announce)

                _state.value = WorkoutState(
                    phase = WorkoutPhase.RUNNING,
                    sectionIndex = si, roundIndex = ri,
                    secondsLeft = round.durationSec,
                    currentCue = announce,
                    currentCueIsCommand = false,
                    sectionTitle = section.title,
                    roundLabel = round.label,
                    isRest = round.isRest,
                    elapsedTotal = elapsed,
                )

                var t = 0
                val cueMap = round.cues.associateBy { it.offsetSec }
                var warnedThisRound = false
                while (t < round.durationSec) {
                    if (skipFlag) { skipFlag = false; break }
                    while (_state.value.phase == WorkoutPhase.PAUSED) delay(200)
                    delay(1000)
                    t++; elapsed++
                    cueMap[t]?.let { cue ->
                        tts?.speak(cue.text)
                        _state.value = _state.value.copy(currentCue = cue.text, currentCueIsCommand = cue.isCommand)
                    }
                    // 10-second warning on work rounds: clap sound + spoken heads-up
                    if (!round.isRest && !warnedThisRound && t == round.durationSec - 10 && round.durationSec >= 30) {
                        warnedThisRound = true
                        if (warnSound) clap()
                        tts?.speak("Ten seconds.")
                    }
                    _state.value = _state.value.copy(secondsLeft = round.durationSec - t, elapsedTotal = elapsed)
                }
            }
        }
        if (endBell) { bell(); bell() }
        tts?.speak("Workout complete. Well done.")
        _state.value = _state.value.copy(phase = WorkoutPhase.FINISHED, currentCue = "Workout complete. Well done.", currentCueIsCommand = false)
    }

    /** Single round-transition bell (start of next round doubles as end of previous). */
    private fun bell() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 450)
    }

    /** Quick triple-beep standing in for a coach's clap at the 10-second mark. */
    private suspend fun clap() {
        repeat(3) {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
            delay(120)
        }
    }

    private fun niceDuration(sec: Int): String = when {
        sec % 60 == 0 -> "${sec / 60} minute${if (sec / 60 > 1) "s" else ""}"
        else -> "$sec seconds"
    }
}
