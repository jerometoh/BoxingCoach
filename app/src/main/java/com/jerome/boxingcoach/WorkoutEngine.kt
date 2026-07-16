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
    val nextRoundLabel: String = "",   // during rest: upcoming work round's label (preview)
    val nextLegend: String = "",       // during rest: upcoming round's trigger legend (preview)
    val isRest: Boolean = false,
    val elapsedTotal: Int = 0,
    val guided: Boolean = false,       // true during warm-up/core/cool-down (no countdown timer)
    val stepIndex: Int = 0,            // guided: current exercise (0-based)
    val stepTotal: Int = 0,            // guided: total exercises in this round
    val comboCue: Int = 0,             // multi-combo rounds: 1/2 => show big ONE!/TWO!; 0 = none
    val comboCueText: String = "",     // the combo string for comboCue (shown under the big number)
    val exerciseName: String = "",     // guided: clean exercise name (shown big; not the narration)
    val exerciseDetail: String = "",   // guided: "45 seconds" / "10 reps each side" etc.
    val timed: Boolean = false,        // guided: current step is a timed hold => show countdown timer
    val sidePrompt: String = "",       // guided per-side: "LEFT" / "RIGHT" (else blank)
    val repCount: Int = 0,             // guided rep exercises: reps done so far
    val repTotal: Int = 0,             // guided rep exercises: reps this set (0 => not a rep exercise)
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

    var tts: SpeechEngine? = null
    var restCoaching: Boolean = true   // retained for compat; in-round tips now baked at generation
    var warnSound: Boolean = true
    var endBell: Boolean = true
    private var routine: Routine? = null

    fun start(r: Routine) {
        stopInternal()
        routine = r
        // Pre-generate & cache the expressive-voice audio for every cue in this
        // routine up front (runs in the background during warm-up), so live
        // playback is local and lag-free. No-op unless ElevenLabs is configured.
        runCatching {
            val texts = LinkedHashSet<String>()
            for (section in r.sections) for (round in section.rounds) {
                if (round.isRest) continue
                for (cue in round.cues) texts += cue.text
                // Guided sections speak step announcements (not cues) plus counts.
                round.guidedSteps?.forEach { texts += it.announce }
            }
            // Number/side vocabulary used by guided counting + fixed callouts. Caching
            // these keeps ElevenLabs counting instant (no per-number network call).
            for (n in 1..20) texts += "$n"
            for (n in 1..20) texts += "and $n."
            texts += listOf(
                "Left side.", "Right side.", "Switch.", "Switch sides.",
                "Ten seconds remaining.", "Halfway there.", "One minute left.",
                "Thirty seconds.", "3", "2", "1", "Rest.", "Workout complete. Well done."
            )
            texts += holdEncouragement
            CoachVoice.elevenLabs?.prewarm(texts)
        }
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

    // Intros are spoken slower than commands for clarity (multiplier on base rate).
    private const val INTRO_RATE = 0.85f

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
    // Encouragement spoken partway through a timed core/hold exercise.
    private val holdEncouragement = listOf(
        "Keep going.", "Stay with it.", "Hold that pace.", "You've got this.",
        "Don't stop now.", "Breathe and push."
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

            // ---- Guided sections (warm-up / core / cool-down): step-through, no timer ----
            if (round.isGuided) {
                elapsed = runGuided(si, ri, section, round, elapsed, rng)
                continue
            }

            val announce: String
            var announceRate = 1f
            if (round.isRest) {
                announce = if (round.label == "Break") {
                    "Break. Swap your gear. ${niceDuration(round.durationSec)}."
                } else {
                    "Rest. ${round.durationSec} seconds."
                }
            } else {
                val preDelivered = slotIdx in introDelivered
                val intro = round.cues.firstOrNull { it.isIntro }
                if (intro != null && !preDelivered) {
                    announce = "${round.label}. ${niceDuration(round.durationSec)}. ${intro.text}"
                    announceRate = INTRO_RATE   // slow, for clarity
                } else {
                    announce = "${round.label}. ${niceDuration(round.durationSec)}."
                }
            }
            // Look ahead to the next work round: its label + legend feed the rest-screen
            // "NEXT ROUND" preview (shown for the whole rest so the combos become
            // familiar), and — if it has an intro — the DELAYED spoken intro.
            var restIntroAt = -1
            var nextSlotIdx = -1
            var nextLabel = ""
            var nextLegendText = ""
            if (round.isRest) {
                val next = slots.getOrNull(slotIdx + 1)
                if (next != null && !next.round.isRest) {
                    nextLabel = next.round.label
                    nextLegendText = next.round.legend
                    if (next.round.cues.any { it.isIntro }) {
                        nextSlotIdx = slotIdx + 1
                        // Let the user actually REST FIRST: deliver the spoken intro in the
                        // LATER part of the rest, reserving ~20s so even a two-combo intro
                        // finishes before the bell — but never before the halfway point on
                        // short rests.
                        val reserve = 20
                        restIntroAt = (round.durationSec - reserve)
                            .coerceAtLeast(round.durationSec / 2)
                            .coerceAtLeast(1)
                    }
                }
            }

            tts?.speak(announce, announceRate)

            _state.value = WorkoutState(
                phase = WorkoutPhase.RUNNING,
                sectionIndex = si, roundIndex = ri,
                secondsLeft = round.durationSec,
                currentCue = "",   // intro is spoken, not shown; screen stays clean until the first command
                currentCueIsCommand = false,
                sectionTitle = section.title,
                roundLabel = round.label,
                legend = round.legend,
                nextRoundLabel = nextLabel,
                nextLegend = nextLegendText,
                isRest = round.isRest,
                elapsedTotal = elapsed,
            )

            // Before a work round's clock starts, let any intro finish speaking so
            // the first "Go" never lands on top of an unfinished instruction. The
            // round's duration is preserved — this wait sits before t=0, not inside it.
            if (!round.isRest) {
                var guard = 0
                while (tts?.isSpeaking() == true && guard < 12000) {
                    if (skipFlag) break
                    while (_state.value.phase == WorkoutPhase.PAUSED) delay(200)
                    delay(100); guard += 100
                }
                // Bell rings at the START of work — after "…ready, and—", not before
                // the instructions. Signals the round is live.
                if (endBell) SoundFx.ringBell()
            }

            val d = round.durationSec
            var t = 0
            val cueMap = round.cues.filter { !it.isIntro }.associateBy { it.offsetSec }
            // Holds an encouragement callout that fell on a command second, so it can be
            // spoken on the next command-free second instead of overlapping the command.
            var pendingCallout: String? = null
            while (t < d) {
                if (skipFlag) { skipFlag = false; break }
                while (_state.value.phase == WorkoutPhase.PAUSED) delay(200)
                delay(1000)
                t++; elapsed++
                val left = d - t

                cueMap[t]?.let { cue ->
                    tts?.speak(cue.text)
                    _state.value = _state.value.copy(
                        currentCue = cue.text,
                        currentCueIsCommand = cue.isCommand,
                        comboCue = cue.comboIndex,
                        comboCueText = round.combos.getOrNull(cue.comboIndex - 1) ?: "",
                    )
                }

                // Rest-time intro of the next round (delivered slowly for clarity)
                if (round.isRest && t == restIntroAt && nextSlotIdx >= 0) {
                    val next = slots[nextSlotIdx]
                    val intro = next.round.cues.first { it.isIntro }
                    val text = "Next: ${next.round.label}. ${intro.text}"
                    tts?.speak(text, INTRO_RATE)
                    _state.value = _state.value.copy(currentCue = "", currentCueIsCommand = false)
                    introDelivered += nextSlotIdx
                }

                // ---- time callouts (work rounds only) ----
                // Encouragement-style callouts (halfway / 1 min / 30 s) must NOT land on
                // top of a spoken command. If a command is due this same second, the
                // callout is deferred and spoken on the next command-free second (which,
                // given commands are seconds apart, is almost always the very next tick —
                // i.e. right after the command).
                val hasCmdThisTick = cueMap.containsKey(t)
                if (!round.isRest) {
                    val callout: String? = when {
                        d >= 120 && t == d / 2 -> halfwayLines.random(rng)
                        d >= 150 && left == 60 -> oneMinLines.random(rng)
                        d >= 90 && left == 30 -> thirtyLines.random(rng)
                        else -> null
                    }
                    when {
                        callout != null && hasCmdThisTick -> pendingCallout = callout
                        callout != null -> tts?.speak(callout)
                        pendingCallout != null && !hasCmdThisTick && left > 3 -> {
                            tts?.speak(pendingCallout); pendingCallout = null
                        }
                    }
                    // Time-critical callouts sit in the command-free tail, so they fire on
                    // the exact second.
                    when {
                        d >= 30 && left == 10 -> {
                            if (warnSound) SoundFx.clapper()
                            tts?.speak("Ten seconds remaining.")
                        }
                        left in 1..3 -> tts?.speak("$left")
                    }
                } else if (d >= 30 && left == 10 && nextSlotIdx < 0) {
                    // Only on a rest with no upcoming intro. When an intro is delivered
                    // late in the rest, we skip this so "get ready" can't overlap it.
                    tts?.speak("Ten seconds. Get ready.")
                }

                _state.value = _state.value.copy(secondsLeft = left, elapsedTotal = elapsed)
            }
            // Bell at the END of a work round (not rests / breaks).
            if (!round.isRest && endBell) SoundFx.singleBell()
        }
        tts?.speak("Workout complete. Well done.")
        _state.value = _state.value.copy(phase = WorkoutPhase.FINISHED, currentCue = "Workout complete. Well done.", currentCueIsCommand = false)
    }

    /**
     * Execute a guided section (warm-up / core / cool-down). Unlike combat rounds
     * this is NOT clock-driven: for each step we speak the announcement, WAIT for the
     * voice to finish, then count reps at the step's cadence (each number awaited) or
     * hold for holdSec with a spoken countdown. Because counting only begins after the
     * announcement finishes, "1, 2" never stack behind it. No round bell, no time
     * callouts. Length is whatever it takes.
     */
    private suspend fun runGuided(si: Int, ri: Int, section: Section, round: Round, startElapsed: Int, rng: Random): Int {
        var elapsed = startElapsed
        val steps = round.guidedSteps ?: return elapsed
        // Exercise steps carry reps/hold; pure spoken lines (intro/outro) have neither.
        val exerciseTotal = steps.count { it.reps > 0 || it.holdSec > 0 }
        var exerciseIdx = 0

        _state.value = _state.value.copy(
            phase = WorkoutPhase.RUNNING,
            sectionIndex = si, roundIndex = ri,
            sectionTitle = section.title, roundLabel = round.label,
            isRest = false, legend = "", guided = true,
            stepIndex = 0, stepTotal = exerciseTotal, secondsLeft = 0,
            elapsedTotal = elapsed, comboCue = 0, comboCueText = "",
            exerciseName = "", exerciseDetail = "", timed = false,
            sidePrompt = "", repCount = 0, repTotal = 0, currentCue = "",
        )

        for ((idx, step) in steps.withIndex()) {
            if (skipFlag) { skipFlag = false; break }
            while (_state.value.phase == WorkoutPhase.PAUSED) delay(200)

            val isExercise = step.reps > 0 || step.holdSec > 0
            if (!isExercise) {
                // Pure spoken line (intro / outro / transition): spoken, but the screen
                // shows a clean label rather than the sentence.
                val label = if (idx == steps.lastIndex) "DONE" else "GET READY"
                _state.value = _state.value.copy(
                    exerciseName = label, exerciseDetail = "", timed = false,
                    sidePrompt = "", repCount = 0, repTotal = 0, secondsLeft = 0,
                    currentCue = "", guided = true,
                )
                tts?.speak(step.announce, INTRO_RATE)
                awaitSpeech()
                continue
            }

            exerciseIdx++
            val detail = when {
                step.holdSec > 0 && step.perSide -> "${step.holdSec} seconds each side"
                step.holdSec > 0 -> "${step.holdSec} seconds"
                step.perSide -> "${step.reps} reps each side"
                else -> "${step.reps} reps"
            }
            _state.value = _state.value.copy(
                exerciseName = step.name.ifBlank { "Exercise" },
                exerciseDetail = detail,
                timed = step.holdSec > 0,
                sidePrompt = "", repCount = 0,
                repTotal = if (step.holdSec > 0) 0 else step.reps,
                secondsLeft = step.holdSec,
                currentCue = "", stepIndex = exerciseIdx, guided = true,
            )
            tts?.speak(step.announce, INTRO_RATE)
            awaitSpeech()

            when {
                // Counted reps (optionally per side with a "Switch").
                step.reps > 0 -> {
                    if (step.perSide) {
                        elapsed = countReps(step, "LEFT", elapsed)
                        if (skipFlag) continue
                        tts?.speak("Switch."); awaitSpeech()
                        elapsed = countReps(step, "RIGHT", elapsed)
                    } else {
                        elapsed = countReps(step, null, elapsed)
                    }
                }
                // Timed hold (per side => hold, switch, hold) with a live countdown timer.
                step.holdSec > 0 -> {
                    if (step.perSide) {
                        _state.value = _state.value.copy(sidePrompt = "LEFT")
                        elapsed = hold(step.holdSec, elapsed, rng)
                        if (skipFlag) continue
                        tts?.speak("Switch sides."); awaitSpeech()
                        _state.value = _state.value.copy(sidePrompt = "RIGHT")
                        elapsed = hold(step.holdSec, elapsed, rng)
                    } else {
                        elapsed = hold(step.holdSec, elapsed, rng)
                    }
                }
            }
        }
        // Leaving the section: clear guided-only visual fields.
        _state.value = _state.value.copy(
            sidePrompt = "", repTotal = 0, timed = false, exerciseDetail = "",
        )
        return elapsed
    }

    /** Speak-and-pace a set of counts, updating the on-screen rep counter. */
    private suspend fun countReps(step: GuidedStep, side: String?, startElapsed: Int): Int {
        var elapsed = startElapsed
        _state.value = _state.value.copy(sidePrompt = side ?: "", repTotal = step.reps, repCount = 0)
        if (side != null) {
            tts?.speak(if (side == "LEFT") "Left side." else "Right side."); awaitSpeech()
        }
        val gapMs = (step.secPerCount.coerceAtLeast(1) * 1000L)
        for (r in 1..step.reps) {
            if (skipFlag) break
            while (_state.value.phase == WorkoutPhase.PAUSED) delay(200)
            tts?.speak(if (r == step.reps) "and ${step.reps}." else "$r")
            _state.value = _state.value.copy(repCount = r)
            delay(gapMs)
            elapsed += step.secPerCount.coerceAtLeast(1)
            _state.value = _state.value.copy(elapsedTotal = elapsed)
        }
        return elapsed
    }

    /** Timed hold: drives the on-screen countdown every second, drops in one
     *  encouragement line around halfway (longer holds), and speaks the final
     *  few seconds aloud. Returns updated elapsed. */
    private suspend fun hold(sec: Int, startElapsed: Int, rng: Random): Int {
        var elapsed = startElapsed
        val cd = if (sec >= 8) 5 else 3
        val halfway = sec / 2
        _state.value = _state.value.copy(secondsLeft = sec, timed = true)
        for (remaining in sec downTo 1) {
            if (skipFlag) return elapsed
            while (_state.value.phase == WorkoutPhase.PAUSED) delay(200)
            when {
                remaining <= cd -> tts?.speak("$remaining")
                sec >= 20 && remaining == halfway -> tts?.speak(holdEncouragement.random(rng))
            }
            delay(1000); elapsed++
            _state.value = _state.value.copy(secondsLeft = remaining - 1, elapsedTotal = elapsed)
        }
        return elapsed
    }

    /** Block until the speech engine has finished the current utterance(s), so the
     *  next count/hold doesn't overlap it. Bounded so a silent/again-null engine
     *  (TEXT_ONLY, or speech failure) can't hang the workout. */
    private suspend fun awaitSpeech(maxMs: Int = 9000) {
        var waited = 0
        // Give TTS a beat to register the utterance as in-flight before polling.
        delay(120); waited += 120
        while (tts?.isSpeaking() == true && waited < maxMs) {
            if (skipFlag) return
            while (_state.value.phase == WorkoutPhase.PAUSED) delay(200)
            delay(100); waited += 100
        }
    }

    private fun niceDuration(sec: Int): String = when {
        sec % 60 == 0 -> "${sec / 60} minute${if (sec / 60 > 1) "s" else ""}"
        else -> "$sec seconds"
    }
}
