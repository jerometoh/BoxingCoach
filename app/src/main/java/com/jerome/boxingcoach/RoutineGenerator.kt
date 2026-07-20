package com.jerome.boxingcoach

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Generates a full routine from params.
 *
 * Each work round (shadow or bag) runs in ONE of three MODES, chosen per round:
 *
 *  - ASSIGNED : 1–2 set inside combos, called live as One! / Two!, plus a conditioning
 *               "down". Burst-able repeats; optional forced-intensity finisher.
 *  - RANGE    : hold the range — jab and circle. Between-work is occasional out-of-range
 *               jabbing patterns; on "Step in!" the boxer fires the round's assigned short
 *               inside power combo. Plus a down.
 *  - FREESTYLE: a broad theme stands (ANY TWO / ANY THREE / TO THE BODY / FAST HANDS /
 *               COUNTERS) and the boxer keeps it flowing; interrupts are open calls off the
 *               theme, an occasional set combo, and downs.
 *
 * The standing instruction (what the boxer does UNPROMPTED) is shown persistently; the live
 * COMMANDS are deliberately different from it. Intros are delivered during the preceding rest
 * so the picture lands before the bell. Combo complexity ramps up across the rounds.
 */
object RoutineGenerator {

    private val downWords = listOf("Down", "Drop")

    /**
     * Map a 1–10 scale onto three anchor values placed where the old
     * Beginner/Intermediate/Advanced (and Low/Medium/High) options sat: scale
     * 2 / 5 / 8. Piecewise-linear through those anchors, keeping the same slopes past
     * them so 1 is gentler than old-Beginner and 10 harder than old-Advanced.
     */
    private fun scale3(v: Int, a2: Float, a5: Float, a8: Float): Float {
        val x = v.coerceIn(1, 10).toFloat()
        return if (x <= 5f) a2 + (a5 - a2) * (x - 2f) / 3f
        else a5 + (a8 - a5) * (x - 5f) / 3f
    }

    fun generate(
        params: RoutineParams,
        stance: Stance,
        countReps: Boolean = true,
        coaching: Boolean = true,
        seed: Long = System.nanoTime()
    ): Routine {
        val rng = Random(seed)
        val sections = mutableListOf<Section>()

        if (params.includeWarmup) sections += warmup(rng, stance, countReps)
        if (params.includeShadow) sections += workSection(
            SectionType.SHADOW, "Shadow boxing (no gloves)",
            params.shadowRounds, params.shadowRoundSec, params, stance, rng, coaching
        )
        if (params.includeBag) sections += workSection(
            SectionType.BAG, "Heavy bag",
            params.bagRounds, params.bagRoundSec, params, stance, rng, coaching
        )
        if (params.includeCore) sections += core(params.coreSec, rng, stance)
        if (params.includeCooldown) sections += cooldown(rng)

        val withBreaks = sections.mapIndexed { i, sec ->
            if (i < sections.size - 1) sec.copy(rounds = sec.rounds + sectionBreak(params.restBetweenSectionsSec))
            else sec
        }
        return Routine(params = params, sections = withBreaks)
    }

    private fun sectionBreak(sec: Int) = Round(
        "Break", sec, emptyList(), "Break — swap gear for the next segment", isRest = true
    )

    /** Regenerate one section of an existing routine, keeping the rest. */
    fun regenerateSection(routine: Routine, sectionIndex: Int, stance: Stance, countReps: Boolean = true, coaching: Boolean = true): Routine {
        val p = routine.params
        val rng = Random(System.nanoTime())
        val old = routine.sections[sectionIndex]
        var fresh = when (old.type) {
            SectionType.WARMUP -> warmup(rng, stance, countReps)
            SectionType.SHADOW -> workSection(SectionType.SHADOW, old.title, p.shadowRounds, p.shadowRoundSec, p, stance, rng, coaching)
            SectionType.BAG -> workSection(SectionType.BAG, old.title, p.bagRounds, p.bagRoundSec, p, stance, rng, coaching)
            SectionType.CORE -> core(p.coreSec, rng, stance)
            SectionType.COOLDOWN -> cooldown(rng)
        }
        if (sectionIndex < routine.sections.size - 1) {
            fresh = fresh.copy(rounds = fresh.rounds + sectionBreak(p.restBetweenSectionsSec))
        }
        val newSections = routine.sections.toMutableList().also { it[sectionIndex] = fresh }
        return routine.copy(sections = newSections)
    }

    /** Regenerate a single work round within a section (picks a fresh mode). */
    fun regenerateRound(routine: Routine, sectionIndex: Int, roundIndex: Int, stance: Stance, countReps: Boolean = true, coaching: Boolean = true): Routine {
        val section = routine.sections[sectionIndex]
        val p = routine.params
        val rng = Random(System.nanoTime())
        if (section.type != SectionType.SHADOW && section.type != SectionType.BAG)
            return regenerateSection(routine, sectionIndex, stance, countReps, coaching)

        val workRounds = section.rounds.filter { !it.isRest }
        val target = section.rounds[roundIndex]
        if (target.isRest) return routine
        val workIdx = workRounds.indexOf(target)
        val fresh = workRound(section.type, workIdx, workRounds.size, target.durationSec, p, stance, rng, coaching)
        val newRounds = section.rounds.toMutableList().also { it[roundIndex] = fresh }
        val newSections = routine.sections.toMutableList().also { it[sectionIndex] = section.copy(rounds = newRounds) }
        return routine.copy(sections = newSections)
    }

    // ------------------------------------------------------------------

    private const val WARMUP_EXERCISE_COUNT = 8

    private fun warmup(rng: Random, stance: Stance, countReps: Boolean): Section {
        val picked = ComboLibrary.warmupMoves.shuffled(rng).take(WARMUP_EXERCISE_COUNT)

        val steps = mutableListOf<GuidedStep>()
        val names = mutableListOf<String>()
        steps += GuidedStep(listOf(
            "Let's warm up — ${picked.size} exercises, top to bottom. Move for the time on screen.",
            "Warm-up time. ${picked.size} exercises head to toe. Keep moving till the timer's up.",
            "Warming up: ${picked.size} exercises from the top down. Loosen everything off."
        ).random(rng))

        picked.forEachIndexed { i, mv ->
            val name = ComboLibrary.render(mv.text, stance)
            names += name
            val lead = when {
                i == 0 -> "First up"
                i == picked.size - 1 -> "Last one"
                else -> listOf("Next", "Now", "Moving on").random(rng)
            }
            // Warm-up is fully TIME-BASED: every move becomes a timed segment with an on-screen
            // countdown, whatever its library kind. Rep / per-side moves convert to a sensible
            // duration (~ rep count × cadence); holds keep their hold time.
            steps += when (mv.kind) {
                ComboLibrary.MoveKind.HOLD -> GuidedStep(
                    announce = "$lead: $name. ${mv.holdSec} seconds.",
                    name = name,
                    holdSec = mv.holdSec,
                )
                ComboLibrary.MoveKind.PER_SIDE -> {
                    val dur = (mv.reps * mv.secPerCount).coerceIn(15, 30)
                    GuidedStep(
                        announce = "$lead: $name. $dur seconds each side.",
                        name = name,
                        perSide = true,
                        holdSec = dur,
                    )
                }
                ComboLibrary.MoveKind.REP -> {
                    val dur = (mv.reps * mv.secPerCount).coerceIn(20, 40)
                    GuidedStep(
                        announce = "$lead: $name. $dur seconds.",
                        name = name,
                        holdSec = dur,
                    )
                }
            }
        }
        steps += GuidedStep("Warm-up done. Shake it out — let's get to work.")

        val round = Round(
            label = "Warm-up",
            durationSec = 0,
            cues = emptyList(),
            summary = "Dynamic warm-up — ${picked.size} exercises",
            guidedSteps = steps,
            exerciseNames = names,
        )
        return Section(SectionType.WARMUP, "Warm-up", listOf(round))
    }

    private fun core(totalSec: Int, rng: Random, stance: Stance): Section {
        val count = (totalSec / 50).coerceIn(4, 8)
        val moves = ComboLibrary.coreMoves.shuffled(rng).take(count)
        val holdEach = (totalSec / count).coerceIn(20, 60)

        val steps = mutableListOf<GuidedStep>()
        val names = mutableListOf<String>()
        steps += GuidedStep("Core circuit — ${moves.size} exercises. Brace and breathe.")
        moves.forEachIndexed { i, m ->
            val name = ComboLibrary.render(m, stance)
            names += name
            val lead = if (i == moves.size - 1) "Last one" else if (i == 0) "First" else "Next"
            steps += GuidedStep(
                announce = "$lead: $name. $holdEach seconds. Go.",
                name = name,
                holdSec = holdEach,
            )
        }
        steps += GuidedStep("Core done. Nice work.")

        val round = Round(
            label = "Core",
            durationSec = 0,
            cues = emptyList(),
            summary = "Core circuit — ${moves.size} exercises",
            guidedSteps = steps,
            exerciseNames = names,
        )
        return Section(SectionType.CORE, "Core", listOf(round))
    }

    private fun cooldown(rng: Random): Section {
        val moves = ComboLibrary.cooldownMoves.shuffled(rng).take(6)
        val steps = mutableListOf<GuidedStep>()
        val names = mutableListOf<String>()
        steps += GuidedStep(listOf(
            "Let's cool down. ${moves.size} stretches — ease into each one and breathe.",
            "Cooling down: ${moves.size} stretches. Slow and gentle, don't force anything.",
            "Cool-down time — ${moves.size} stretches. Long, easy breaths throughout."
        ).random(rng))

        moves.forEachIndexed { i, mv ->
            val name = ComboLibrary.render(mv.text, Stance.ORTHODOX) // no L/R tokens in cool-down text
            names += name
            val lead = if (i == moves.size - 1) "Last one" else if (i == 0) "First" else "Next"
            steps += if (mv.kind == ComboLibrary.MoveKind.PER_SIDE) {
                GuidedStep(announce = "$lead: $name. Hold one side, then switch.",
                    name = name, holdSec = mv.holdSec, perSide = true)
            } else {
                GuidedStep(announce = "$lead: $name.", name = name, holdSec = mv.holdSec)
            }
        }
        steps += GuidedStep("That's your cool-down. Good work today.")

        val round = Round(
            label = "Cool-down",
            durationSec = 0,
            cues = emptyList(),
            summary = "Static stretches and breathing — ${moves.size} stretches",
            guidedSteps = steps,
            exerciseNames = names,
        )
        return Section(SectionType.COOLDOWN, "Cool-down", listOf(round))
    }

    private fun workSection(
        type: SectionType, title: String,
        roundCount: Int, roundSec: Int,
        p: RoutineParams, stance: Stance, rng: Random, coaching: Boolean = true
    ): Section {
        val rest = (p.restSec + scale3(p.intensity, 15f, 0f, -15f)).roundToInt().coerceAtLeast(30)
        val modes = modeSequence(roundCount, p.complexity, rng)
        val rounds = mutableListOf<Round>()
        for (i in 0 until roundCount) {
            rounds += workRound(type, i, roundCount, roundSec, p, stance, rng, coaching, modes.getOrNull(i))
            if (i < roundCount - 1) rounds += Round(
                "Rest", rest, emptyList(), "Rest — ${rest}s", isRest = true
            )
        }
        return Section(type, title, rounds)
    }

    private fun comboSay(idx: Int) = when (idx) { 1 -> "One!"; 2 -> "Two!"; 3 -> "Three!"; else -> "Go!" }
    private fun comboLabel(idx: Int) = when (idx) { 1 -> "ONE"; 2 -> "TWO"; 3 -> "THREE"; else -> "GO" }

    private fun sectionNoun(type: SectionType) = when (type) {
        SectionType.SHADOW -> "Shadow boxing"
        SectionType.BAG -> "Heavy bag"
        else -> "Round"
    }

    // ---- Modes ----------------------------------------------------------------
    private enum class Mode { ASSIGNED, RANGE, FREESTYLE }

    /** A whole-section sequence of modes: ASSIGNED weighted highest, RANGE next, FREESTYLE
     *  gated off the opening round and rising with complexity + how deep we are; no two
     *  adjacent rounds share a mode. */
    private fun modeSequence(total: Int, complexity: Int, rng: Random): List<Mode> {
        val seq = ArrayList<Mode>()
        for (i in 0 until total) {
            val progress = if (total <= 1) 1f else i.toFloat() / (total - 1)
            val w = ArrayList<Pair<Mode, Double>>()
            w += Mode.ASSIGNED to 3.0
            w += Mode.RANGE to 2.0
            if (i > 0) {
                val fw = (scale3(complexity, 0.6f, 1.4f, 2.4f) * (0.5f + progress)).toDouble()
                if (fw > 0.0) w += Mode.FREESTYLE to fw
            }
            val prev = seq.lastOrNull()
            val filtered = w.filter { it.first != prev }.ifEmpty { w }
            val tot = filtered.sumOf { it.second }
            var r = rng.nextDouble(tot)
            var chosen = filtered.first().first
            for ((m, ww) in filtered) { if (r < ww) { chosen = m; break }; r -= ww }
            seq += chosen
        }
        return seq
    }

    /** Single-round mode pick (no adjacency knowledge) — used when regenerating one round. */
    private fun pickMode(index: Int, total: Int, complexity: Int, rng: Random): Mode {
        val progress = if (total <= 1) 1f else index.toFloat() / (total - 1)
        val w = ArrayList<Pair<Mode, Double>>()
        w += Mode.ASSIGNED to 3.0
        w += Mode.RANGE to 2.0
        if (index > 0) {
            val fw = (scale3(complexity, 0.6f, 1.4f, 2.4f) * (0.5f + progress)).toDouble()
            if (fw > 0.0) w += Mode.FREESTYLE to fw
        }
        val tot = w.sumOf { it.second }
        var r = rng.nextDouble(tot)
        for ((m, ww) in w) { if (r < ww) return m; r -= ww }
        return Mode.ASSIGNED
    }

    // ---- FREESTYLE themes ----
    private class FreeTheme(val label: String, val hint: String, val say: String, val calls: List<String>)

    private fun pickTheme(complexity: Int, index: Int, rng: Random): FreeTheme {
        val pool = mutableListOf<FreeTheme>()
        pool += FreeTheme("ANY TWO", "any two punches, keep flowing",
            "Freestyle round — any two punches, keep them flowing.",
            listOf("Any two!", "Two upstairs!", "Sharp two!", "One-two!"))
        pool += FreeTheme("ANY THREE", "any three-punch combinations",
            "Freestyle round — any three punches, let them go.",
            listOf("Any three!", "Three punches!", "Three, then move!"))
        pool += FreeTheme("TO THE BODY", "invest downstairs, then upstairs",
            "Freestyle round — invest in the body, then come upstairs.",
            listOf("To the body!", "Double to the body!", "Body then head!"))
        pool += FreeTheme("FAST HANDS", "high volume, snap them out",
            "Freestyle round — fast hands, high volume.",
            listOf("Fast hands!", "Let them go!", "Flurry, then reset!"))
        if (complexity >= 4 && index > 0) pool += FreeTheme("COUNTERS", "slip or roll, then fire back",
            "Freestyle round — counter this round: slip or roll, then fire straight back.",
            listOf("Slip and counter!", "Roll and rip!", "Catch and return!"))
        return pool.random(rng)
    }

    // ---- Shared spacing helpers ----
    private fun throwTime(len: Int): Int = (1.2f + len * 0.55f).roundToInt().coerceIn(2, 5)
    private fun breathGap(p: RoutineParams, rng: Random): Int =
        scale3(p.intensity, 9f, 7f, 5f).roundToInt().coerceAtLeast(3) + rng.nextInt(-1, 2)

    private fun workRound(
        type: SectionType, index: Int, total: Int, durationSec: Int,
        p: RoutineParams, stance: Stance, rng: Random, coaching: Boolean = true,
        mode0: Mode? = null,
    ): Round {
        val progress = if (total <= 1) 1f else index.toFloat() / (total - 1)
        val mode = mode0 ?: pickMode(index, total, p.complexity, rng)

        // Combo complexity climbs with round position & the complexity setting.
        val ceiling = scale3(p.complexity, 2f, 3f, 5f).roundToInt().coerceIn(1, 5)
        val base = scale3(p.complexity, 1f, 1f, 2f).coerceIn(1f, ceiling.toFloat())
        val tier = (base + progress * (ceiling - base)).toInt().coerceIn(1, ceiling)
        val targetScore = 1.5 + tier * 1.9

        val label = "${sectionNoun(type)} \u2014 Round ${index + 1} of $total"
        val downWord = downWords.random(rng)
        val downMove = ComboLibrary.render(ComboLibrary.downMoves.random(rng), stance)

        // Burst helper (mutates the given cue list); capped at bodyEnd (set below).
        fun burstCount(maxN: Int): Int {
            val chance = (scale3(p.intensity, 0.15f, 0.30f, 0.45f) + progress * 0.15f).coerceIn(0f, 0.6f)
            return if (rng.nextFloat() < chance) rng.nextInt(2, maxN + 1) else 1
        }

        // ---- Per-mode setup: standing, combos, the "combo call", open calls, intro ----
        val standing: String
        val hint: String
        val combos: List<String>
        val lens: List<Int>
        val refs = mutableListOf<CommandRef>()
        val introText: String
        var openCall: (() -> String)? = null
        var openRatio = 0f
        // The "else" branch of the command loop = fire the primary combo. Returns next t.
        val fireCombo: (MutableList<Cue>, Int, Int) -> Int   // (cues, t, bodyEnd) -> next t
        // Optional "N in a row" finisher burst: (text, comboIndex, len). null => tank-only finisher.
        val finisherBurst: Triple<String, Int, Int>?

        when (mode) {
            Mode.ASSIGNED -> {
                val twoFloor = scale3(p.complexity, 0f, 0f, 0.15f).coerceAtLeast(0f)
                val twoPeak = scale3(p.complexity, 0f, 0.40f, 0.60f).coerceIn(0f, 0.75f)
                val twoChance = (twoFloor + (twoPeak - twoFloor) * progress).coerceIn(0f, 0.75f)
                val comboCount = if (rng.nextFloat() < twoChance) 2 else 1

                val assigned = mutableListOf<Pair<String, Int>>()
                var guard = 0
                while (assigned.size < comboCount && guard < 30) {
                    val c = ComboAssembler.inside(targetScore, stance, rng)
                    if (assigned.none { it.first == c.text }) assigned += c.text to c.actionCount
                    guard++
                }
                if (assigned.isEmpty()) { val c = ComboAssembler.inside(targetScore, stance, rng); assigned += c.text to c.actionCount }

                combos = assigned.map { it.first }
                lens = assigned.map { it.second }
                standing = "ON MY CALL"
                hint = "throw the number I call \u2014 stay busy between"
                combos.forEachIndexed { i, c -> refs += CommandRef(comboLabel(i + 1), c) }
                refs += CommandRef(downWord.uppercase(), downMove)

                val comboLine = if (combos.size == 2)
                    "Combo one: ${combos[0]}. Combo two: ${combos[1]}. Throw the number I call."
                else "Your combo: ${combos[0]}. Throw it when I call one."
                introText = "Assigned combos. $comboLine On \"$downWord\": $downMove."

                fireCombo = { cues, t, bodyEnd ->
                    val idx = if (combos.size == 2) rng.nextInt(1, 3) else 1
                    val last = burst(cues, comboSay(idx), idx, burstCount(if (progress > 0.5f) 5 else 4), throwTime(lens[idx - 1]), t, bodyEnd)
                    last + breathGap(p, rng)
                }
                finisherBurst = Triple(comboSay(1), 1, lens[0])
            }

            Mode.RANGE -> {
                val rangeComboTarget = (3.2 + progress * 1.2)   // short inside combo (~2–3 punches)
                val c = ComboAssembler.inside(rangeComboTarget, stance, rng)
                combos = listOf(c.text)
                lens = listOf(c.actionCount)
                standing = "HOLD RANGE"
                hint = "stay long \u2014 jab and circle"
                refs += CommandRef("STEP IN", c.text)
                refs += CommandRef(downWord.uppercase(), downMove)
                introText = "Hold your range this round \u2014 jab and circle, stay long. " +
                    "When I call step in, fire this: ${c.text}. On \"$downWord\": $downMove."

                // Between-work: out-of-range jabbing patterns, called live.
                val rangeWorkTarget = (2.5 + progress * 1.5)
                openCall = { ComboAssembler.outRange(rangeWorkTarget, stance, rng).text }
                openRatio = 0.30f

                // "Step in!" is a single committed entry (not bursted); the combo is on the STEP IN card.
                fireCombo = { cues, t, _ ->
                    cues += Cue(t, "Step in!", isCommand = true)
                    t + throwTime(lens[0]) + breathGap(p, rng)
                }
                finisherBurst = null   // step-ins don't burst; RANGE uses the tank finisher only
            }

            Mode.FREESTYLE -> {
                val theme = pickTheme(p.complexity, index, rng)
                val c = ComboAssembler.inside(targetScore, stance, rng)
                combos = listOf(c.text)
                lens = listOf(c.actionCount)
                standing = theme.label
                hint = theme.hint
                refs += CommandRef(comboLabel(1), c.text)
                refs += CommandRef(downWord.uppercase(), downMove)
                introText = "${theme.say} I'll call it out \u2014 keep it flowing on your own between. " +
                    "Set combo when I call one: ${c.text}. On \"$downWord\": $downMove."

                openCall = { theme.calls.random(rng) }
                openRatio = 0.34f

                fireCombo = { cues, t, bodyEnd ->
                    val last = burst(cues, comboSay(1), 1, burstCount(if (progress > 0.5f) 5 else 4), throwTime(lens[0]), t, bodyEnd)
                    last + breathGap(p, rng)
                }
                finisherBurst = Triple(comboSay(1), 1, lens[0])
            }
        }

        val legend = refs.joinToString("   \u00b7   ") { "${it.label} \u2192 ${it.text}" }
        val cues = mutableListOf(Cue(0, introText, isIntro = true))
        val startAt = 3

        // ---- Optional forced-intensity finisher (NOT pre-announced) ----
        fun buildFinisher(): Pair<List<Cue>, Int> {
            if (durationSec < 60) return emptyList<Cue>() to 0
            val chance = (scale3(p.intensity, 0.35f, 0.50f, 0.65f) + progress * 0.12f).coerceIn(0f, 0.8f)
            if (rng.nextFloat() >= chance) return emptyList<Cue>() to 0
            val d = durationSec
            val out = mutableListOf<Cue>()
            val doTank = finisherBurst == null || rng.nextBoolean()
            if (doTank) {
                val reserve = 12
                if (d - startAt < reserve + 20) return emptyList<Cue>() to 0
                out += Cue(d - 11, "Last ten seconds \u2014 empty the tank!", isCommand = false)
                out += Cue(d - 3, "3", isCommand = true)
                out += Cue(d - 2, "2", isCommand = true)
                out += Cue(d - 1, "1", isCommand = true)
                return out to reserve
            } else {
                val (btext, bidx, blen) = finisherBurst!!
                val n = rng.nextInt(3, 6) // 3..5 in a row
                val sp = throwTime(blen)
                val reserve = (2 + n * sp + 1).coerceAtLeast(10)
                if (d - startAt < reserve + 20) return emptyList<Cue>() to 0
                var ft = d - reserve + 2
                out += Cue((ft - 2).coerceAtLeast(startAt), "$n in a row \u2014 let's go!", isCommand = false)
                repeat(n) { out += Cue(ft.coerceAtMost(d - 1), btext, isCommand = true, comboIndex = bidx); ft += sp }
                return out to reserve
            }
        }
        val (finisherCues, reserve) = buildFinisher()
        val hasFinisher = finisherCues.isNotEmpty()
        val bodyEnd = durationSec - (if (hasFinisher) reserve else 3)

        // ---- Command stream ----
        // Between commands the boxer follows the STANDING instruction on his own, so the gaps
        // are real. Commands are deliberately different: the primary combo call (burst-able),
        // open calls (mode-specific), and a conditioning down.
        val coachRatio = if (coaching) 0.08f else 0f
        val downRatio = scale3(p.intensity, 0.10f, 0.14f, 0.20f)

        var t = startAt
        while (t < bodyEnd) {
            val roll = rng.nextFloat()
            when {
                roll < coachRatio -> {
                    cues += Cue(t, ComboLibrary.render(ComboLibrary.restTips.random(rng), stance), isCommand = false)
                    t += breathGap(p, rng) + 2
                }
                roll < coachRatio + downRatio -> {
                    val last = burst(cues, "$downWord!", 0, burstCount(4), throwTime(2), t, bodyEnd)
                    t = last + breathGap(p, rng)
                }
                openCall != null && roll < coachRatio + downRatio + openRatio -> {
                    cues += Cue(t, openCall!!(), isCommand = true)   // pre-rendered / token-free text
                    t += breathGap(p, rng)
                }
                else -> t = fireCombo(cues, t, bodyEnd)
            }
        }
        cues += finisherCues

        val summary = "$standing \u00b7 ${combos.joinToString(" / ")}" +
            " \u00b7 $downWord: $downMove" +
            (if (hasFinisher) " \u00b7 finisher" else "")

        return Round(
            label, durationSec, cues, summary,
            hasFinisher = hasFinisher, standing = standing, standingHint = hint,
            legend = legend, commandRefs = refs, combos = combos,
        )
    }

    /** Emit up to [count] copies of [text] spaced by [innerGap]; returns the last cue time. */
    private fun burst(
        cues: MutableList<Cue>, text: String, comboIdx: Int,
        count: Int, innerGap: Int, startT: Int, bodyEnd: Int
    ): Int {
        var tt = startT; var last = startT; var i = 0
        while (i < count && tt < bodyEnd) {
            cues += Cue(tt, text, isCommand = true, comboIndex = comboIdx)
            last = tt; tt += innerGap; i++
        }
        return last
    }
}
