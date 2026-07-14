package com.jerome.boxingcoach

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
    val legend: String = "",
    val isRest: Boolean = false,
    val elapsedTotal: Int = 0,
)

/**
 * Singleton engine holding the live workout. The foreground service keeps the
 * process alive; UI observes [state]. Survives activity recreation.
 *
 * Cue delivery notes:
 *  - Round intros are spoken DURING the preceding rest (a few seconds in), so the
 *    user has the picture before the bell. First round of a section (no preceding
 *    rest) gets its intro at round start instead.
 *  - Skip and stop cut speech immediately (TTS flush) rather than letting queued
 *    lines play out.
 *  - Time callouts: halfway / 1 min / 30 s / clapper+voice at 10 s / 3-2-1 at
 *    round end, phrased with variety so it doesn't get robotic.
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

    fun start(r: Routine) {
        stopInternal()
        routine = r
        job = scope.launch { run(r) }
    }

    fun pause() {
        if (_state.value.phase == WorkoutPhase.RUNNING) {
            _state.value = _state.value.copy(phase = WorkoutPhase.PAUSED)
            tts?.cut()
        }
    }

    fun resume() {
        if (_state.value.phase == WorkoutPhase.PAUSED) {
            _state.value = _state.value.copy(phase = WorkoutPhase.RUNNING)
        }
    }

    /** Skip to the next round — cuts any speech in progress immediately. */
    @Volatile private var skipFlag = false
    fun skip() {
        tts?.cut()
        skipFlag = true
    }

    fun repeatCue() {
        val c = _state.value.currentCue
        if (c.isNotBlank()) tts?.speak(c)
    }

    /** Full stop — cuts speech immediately. Used by End button and app close. */
    fun stop() {
        stopInternal()
        _state.value = WorkoutState(phase = WorkoutPhase.IDLE)
    }

    private fun stopInternal() {
        job?.cancel(); job = null
        tts?.cut()
    }

    // ---- time callout phrasing pools (picked randomly for variety) ----
    private val halfwayLines = listOf(
        "Halfway there.", "Halfway. Keep the output up.", "That's half. Stay sharp."
    )
    private val oneMinLines = listOf(
        "One minute left.", "Final minute — make it count.", "Sixty seconds. Push."
    )
    private val thirtyLines = listOf(
        "Thirty seconds.", "Thirty left — finish strong.", "Half a minute. Dig in."
    )

    private suspend fun run(r: Routine) {
        var elapsed = 0
        val rng = Random(System.nanoTime())

        // Flatten to (sectionIdx, roundIdx, section, round) so we can look ahead
        // for the next work round's intro while sitting in a rest period.
        data class Slot(val si: Int, val ri: Int, val section: Section, val round: Round)
        val slots = mutableListOf<Slot>()
        for ((si, section) in r.sections.withIndex())
            for ((ri, round) in section.rounds.withIndex())
                slots += Slot(si, ri, section, round)

        val introDelivered = HashSet<Int>() // slot indices whose intro was pre-spoken

        for ((slotIdx, slot) in slots.withIndex()) {
            val (si, ri, section, round) = slot
            if (endBell) SoundFx.ringBell()

            val announce: String
            if (round.isRest) {
                announce = if (round.label == "Break") {
                    "Break. Swap your gear. ${niceDuration(round.durationSec)}."
                } else {
                    val tip = if (restCoaching) " " + ComboLibrary.restTips.random(rng) else ""
                    "Rest. ${round.durationSec} seconds.$tip"
                }
            } else {
                val preDelivered = slotIdx in introDelivered
                val intro = round.cues.firstOrNull { it.isIntro }
                announce = if (intro != null && !preDelivered) {
                    "${round.label}. ${niceDuration(round.durationSec)}. ${intro.text}"
                } else {
                    "${round.label}. ${niceDuration(round.durationSec)}."
                }
            }
            tts?.speak(announce)

            _state.value = WorkoutState(
                phase = WorkoutPhase.RUNNING,
                sectionIndex = si, roundIndex = ri,
                secondsLeft = round.durationSec,
                currentCue = if (round.isRest) announce else (round.cues.firstOrNull { it.isIntro }?.text ?: announce),
                currentCueIsCommand = false,
                sectionTitle = section.title,
                roundLabel = round.label,
                legend = round.legend,
                isRest = round.isRest,
                elapsedTotal = elapsed,
            )

            // During rest: schedule the NEXT work round's intro partway through.
            var restIntroAt = -1
            var nextSlotIdx = -1
            if (round.isRest) {
                val next = slots.getOrNull(slotIdx + 1)
                if (next != null && !next.round.isRest && next.round.cues.any { it.isIntro }) {
                    nextSlotIdx = slotIdx + 1
                    restIntroAt = (round.durationSec / 3).coerceAtLeast(5)
                }
            }

            val d = round.durationSec
            var t = 0
            val cueMap = round.cues.filter { !it.isIntro }.associateBy { it.offsetSec }
            while (t < d) {
                if (skipFlag) { skipFlag = false; break }
                while (_state.value.phase == WorkoutPhase.PAUSED) delay(200)
                delay(1000)
                t++; elapsed++
                val left = d - t

                cueMap[t]?.let { cue ->
                    tts?.speak(cue.text)
                    _state.value = _state.value.copy(currentCue = cue.text, currentCueIsCommand = cue.isCommand)
                }

                // Rest-time intro of the next round
                if (round.isRest && t == restIntroAt && nextSlotIdx >= 0) {
                    val next = slots[nextSlotIdx]
                    val intro = next.round.cues.first { it.isIntro }
                    val text = "Next: ${next.round.label}. ${intro.text}"
                    tts?.speak(text)
                    _state.value = _state.value.copy(currentCue = text, currentCueIsCommand = false)
                    introDelivered += nextSlotIdx
                }

                // ---- time callouts (work rounds only) ----
                if (!round.isRest) {
                    when {
                        d >= 120 && t == d / 2 -> tts?.speak(halfwayLines.random(rng))
                        d >= 150 && left == 60 -> tts?.speak(oneMinLines.random(rng))
                        d >= 90 && left == 30 -> tts?.speak(thirtyLines.random(rng))
                        d >= 30 && left == 10 -> {
                            if (warnSound) SoundFx.clapper()
                            tts?.speak("Ten seconds remaining.")
                        }
                        left in 1..3 -> tts?.speak("$left")
                    }
                } else if (d >= 30 && left == 10) {
                    tts?.speak("Ten seconds. Get ready.")
                }

                _state.value = _state.value.copy(secondsLeft = left, elapsedTotal = elapsed)
            }
        }
        if (endBell) SoundFx.singleBell()
        tts?.speak("Workout complete. Well done.")
        _state.value = _state.value.copy(phase = WorkoutPhase.FINISHED, currentCue = "Workout complete. Well done.", currentCueIsCommand = false)
    }

    private fun niceDuration(sec: Int): String = when {
        sec % 60 == 0 -> "${sec / 60} minute${if (sec / 60 > 1) "s" else ""}"
        else -> "$sec seconds"
    }
}
