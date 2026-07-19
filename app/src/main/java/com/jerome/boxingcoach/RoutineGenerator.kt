package com.jerome.boxingcoach

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Generates a full routine from params.
 *
 * Round model (call-and-response): each work round is assigned ONE combo (or TWO
 * for later/harder rounds) plus ONE fixed conditioning move, all announced in the
 * round intro. The intro is delivered during the preceding rest (or at round start
 * for the first round of a section) so the user has the mental picture before the
 * bell. During the round, only short trigger words are called:
 *  - attack word ("Go", "Hit", "Shoot" — varies per round; "<word> one/two" when
 *    two combos are assigned)
 *  - conditioning word ("Down", "Drop" — varies per round) for the round's fixed
 *    conditioning move
 *  - spacing fillers ("Feint", "Jab, keep range", "Circle left"...) in between.
 * Each round carries a `legend` string mapping its trigger words to their meaning,
 * shown on the workout screen.
 */
object RoutineGenerator {

    private val downWords = listOf("Down", "Drop")

    /**
     * Map a 1–10 scale onto three anchor values placed where the old
     * Beginner/Intermediate/Advanced (and Low/Medium/High) options sat: scale
     * 2 / 5 / 8. Piecewise-linear through those anchors, and it keeps the same
     * slopes past them so 1 is genuinely gentler than old-Beginner and 10 harder
     * than old-Advanced, rather than clamping flat at the ends.
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
        // Keep the trailing gear-change break if this isn't the last section.
        if (sectionIndex < routine.sections.size - 1) {
            fresh = fresh.copy(rounds = fresh.rounds + sectionBreak(p.restBetweenSectionsSec))
        }
        val newSections = routine.sections.toMutableList().also { it[sectionIndex] = fresh }
        return routine.copy(sections = newSections)
    }

    /** Regenerate a single work round within a section. */
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

    // How many warm-up exercises to include (chosen from the shuffled library).
    private const val WARMUP_EXERCISE_COUNT = 8

    /**
     * Guided warm-up. Emits a list of GuidedSteps; the engine speaks each exercise
     * name, waits for the voice to finish, THEN counts at the exercise's own cadence
     * (so "1, 2" never stack behind the announcement). Length is emergent — the round
     * carries no meaningful fixed duration.
     */
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
            // Warm-up is now fully TIME-BASED: every move becomes a timed segment with an
            // on-screen countdown, whatever its library kind. Rep / per-side moves are
            // converted to a sensible duration (~ their rep count × cadence); holds keep
            // their hold time. (countReps no longer applies to the warm-up.)
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
        val rounds = mutableListOf<Round>()
        for (i in 0 until roundCount) {
            rounds += workRound(type, i, roundCount, roundSec, p, stance, rng, coaching)
            if (i < roundCount - 1) rounds += Round(
                "Rest", rest, emptyList(), "Rest — ${rest}s", isRest = true
            )
        }
        return Section(type, title, rounds)
    }

    private fun comboSay(idx: Int) = when (idx) { 1 -> "One!"; 2 -> "Two!"; 3 -> "Three!"; else -> "Go!" }
    private fun comboLabel(idx: Int) = when (idx) { 1 -> "ONE"; 2 -> "TWO"; 3 -> "THREE"; else -> "GO" }

    private fun workRound(
        type: SectionType, index: Int, total: Int, durationSec: Int,
        p: RoutineParams, stance: Stance, rng: Random, coaching: Boolean = true
    ): Round {
        val progress = if (total <= 1) 1f else index.toFloat() / (total - 1)

        // ---- Standing instruction: the unprompted default for this round ----
        val st = pickStanding(index, total, p.complexity, rng)
        val punchless = st.punchless
        val allowDown = st.allowDown && !punchless

        // ---- Combo complexity climbs with round position & the complexity setting ----
        val ceiling = scale3(p.complexity, 2f, 3f, 5f).roundToInt().coerceIn(1, 5)
        val base = scale3(p.complexity, 1f, 1f, 2f).coerceIn(1f, ceiling.toFloat())
        val tier = (base + progress * (ceiling - base)).toInt().coerceIn(1, ceiling)
        val targetScore = 1.5 + tier * 1.9

        // ---- Numbered combos assigned to this round (called live as One! / Two!) ----
        val twoFloor = scale3(p.complexity, 0f, 0f, 0.15f).coerceAtLeast(0f)
        val twoPeak = scale3(p.complexity, 0f, 0.40f, 0.60f).coerceIn(0f, 0.75f)
        val twoChance = (twoFloor + (twoPeak - twoFloor) * progress).coerceIn(0f, 0.75f)
        val comboCount = if (st.maxCombos >= 2 && rng.nextFloat() < twoChance) 2 else 1

        fun oneCombo(): Pair<String, Int> {
            val c = when (st.builder) {
                Builder.FREE -> ComboAssembler.free(targetScore, stance, rng)
                Builder.LEAD -> ComboAssembler.leadOnly(targetScore, stance, rng)
                Builder.BODY -> ComboAssembler.free(targetScore, stance, rng)   // body-only retired
                Builder.COUNTER -> ComboAssembler.counters(targetScore, stance, rng)
                Builder.TWO -> ComboAssembler.exact(2, stance, rng)
                Builder.THREE -> ComboAssembler.exact(3, stance, rng)
                null -> { val v = st.vocab!!.random(rng); return ComboLibrary.render(v.first, stance) to v.second }
            }
            return c.text to c.actionCount
        }

        val assigned = mutableListOf<Pair<String, Int>>()
        if (!punchless) {
            var guard = 0
            while (assigned.size < comboCount && guard < 30) {
                val c = oneCombo()
                if (assigned.none { it.first == c.first }) assigned += c
                guard++
            }
            if (assigned.isEmpty()) assigned += oneCombo()
        }
        val combos = assigned.map { it.first }
        val lens = assigned.map { it.second }
        val downMove = if (allowDown) ComboLibrary.render(ComboLibrary.downMoves.random(rng), stance) else ""
        val downWord = downWords.random(rng)

        val label = "${sectionNoun(type)} \u2014 Round ${index + 1} of $total"

        // ---- Intro (spoken during the preceding rest, or at round start) ----
        val introText: String
        if (punchless) {
            introText = "${st.say} I'll call each movement \u2014 flow between them on your own in between. No punches this round."
        } else {
            val comboLine = if (combos.size == 2)
                "Combo one: ${combos[0]}. Combo two: ${combos[1]}."
            else "Your combo: ${combos[0]}."
            val callLine = if (combos.size == 2) " Throw the number I call." else " Throw it when I call one."
            val downLine = if (downMove.isNotEmpty()) " On \"$downWord\": $downMove." else ""
            val openLine = if (st.allowOpen) " I'll mix in open calls too \u2014 listen out." else ""
            introText = "${st.say} $comboLine$callLine$downLine$openLine"
        }

        // ---- On-screen reference: one card per command (combos render vertically) ----
        val refs = mutableListOf<CommandRef>()
        if (!punchless) {
            combos.forEachIndexed { i, c -> refs += CommandRef(comboLabel(i + 1), c) }
            if (downMove.isNotEmpty()) refs += CommandRef(downWord.uppercase(), downMove)
        }
        val legend = refs.joinToString("   \u00b7   ") { "${it.label} \u2192 ${it.text}" }

        val cues = mutableListOf(Cue(0, introText, isIntro = true))

        // ---- Command stream ----
        // Between commands the boxer follows the STANDING instruction on his own, so the
        // gaps are real (no filler cues). Commands are deliberately different from that
        // default: numbered combos (burst-able \u2014 "one, one, one"), open-ended calls, and
        // a conditioning down (also burst-able). Bursts can land anywhere in the round and
        // vary in length \u2014 not just a fixed five at the end.
        val startAt = 3

        // Spacing WITHIN a burst \u2248 how long that combo takes to throw + a beat, so the
        // calls don't leave an awkward pause between them.
        fun throwTime(len: Int): Int = (1.2f + len * 0.55f).roundToInt().coerceIn(2, 5)
        // Breathing gap BETWEEN events = standing-instruction time; shorter at high intensity.
        fun breathGap(): Int = scale3(p.intensity, 9f, 7f, 5f).roundToInt().coerceAtLeast(3) + rng.nextInt(-1, 2)

        // Burst likelihood + length rise with intensity and how deep into the section we are.
        val burstChance = (scale3(p.intensity, 0.15f, 0.30f, 0.45f) + progress * 0.15f).coerceIn(0f, 0.6f)
        fun burstCount(maxN: Int): Int =
            if (rng.nextFloat() < burstChance) rng.nextInt(2, maxN + 1) else 1

        // Optional end finisher (NOT pre-announced). Sets hasFinisher so the engine keeps
        // quiet over the last stretch and lets this own it.
        fun buildFinisher(): Pair<List<Cue>, Int> {
            if (punchless || durationSec < 60) return emptyList<Cue>() to 0
            val chance = (scale3(p.intensity, 0.35f, 0.50f, 0.65f) + progress * 0.12f).coerceIn(0f, 0.8f)
            if (rng.nextFloat() >= chance) return emptyList<Cue>() to 0
            val d = durationSec
            val out = mutableListOf<Cue>()
            if (rng.nextBoolean()) {
                val reserve = 12
                if (d - startAt < reserve + 20) return emptyList<Cue>() to 0
                out += Cue(d - 11, "Last ten seconds \u2014 empty the tank!", isCommand = false)
                out += Cue(d - 3, "3", isCommand = true)
                out += Cue(d - 2, "2", isCommand = true)
                out += Cue(d - 1, "1", isCommand = true)
                return out to reserve
            } else {
                val n = rng.nextInt(3, 6) // 3..5 in a row
                val len = lens.firstOrNull() ?: 2
                val sp = throwTime(len)
                val reserve = (2 + n * sp + 1).coerceAtLeast(10)
                if (d - startAt < reserve + 20) return emptyList<Cue>() to 0
                var ft = d - reserve + 2
                out += Cue((ft - 2).coerceAtLeast(startAt), "$n in a row \u2014 let's go!", isCommand = false)
                repeat(n) { out += Cue(ft.coerceAtMost(d - 1), comboSay(1), isCommand = true, comboIndex = 1); ft += sp }
                return out to reserve
            }
        }
        val (finisherCues, reserve) = buildFinisher()
        val hasFinisher = finisherCues.isNotEmpty()
        val bodyEnd = durationSec - (if (hasFinisher) reserve else 3)

        // Emit up to [count] copies of [text] spaced by [innerGap]; returns the last cue time.
        fun burst(text: String, comboIdx: Int, count: Int, innerGap: Int, startT: Int): Int {
            var tt = startT; var last = startT; var i = 0
            while (i < count && tt < bodyEnd) {
                cues += Cue(tt, text, isCommand = true, comboIndex = comboIdx)
                last = tt; tt += innerGap; i++
            }
            return last
        }

        val coachRatio = if (coaching) 0.08f else 0f
        val downRatio = if (allowDown) scale3(p.intensity, 0.10f, 0.14f, 0.20f) else 0f
        val openRatio = if (st.allowOpen) 0.20f else 0f

        var t = startAt
        while (t < bodyEnd) {
            if (punchless) {
                cues += Cue(t, ComboLibrary.render(st.vocab!!.random(rng).first, stance), isCommand = true)
                t += breathGap()
                continue
            }
            val roll = rng.nextFloat()
            when {
                roll < coachRatio -> {
                    cues += Cue(t, ComboLibrary.render(ComboLibrary.restTips.random(rng), stance), isCommand = false)
                    t += breathGap() + 2
                }
                roll < coachRatio + downRatio -> {
                    val last = burst("$downWord!", 0, burstCount(4), throwTime(2), t)
                    t = last + breathGap()
                }
                roll < coachRatio + downRatio + openRatio -> {
                    cues += Cue(t, ComboLibrary.render(ComboLibrary.openCommands.random(rng), stance), isCommand = true)
                    t += breathGap()
                }
                else -> {
                    val idx = if (combos.size == 2) rng.nextInt(1, 3) else 1
                    val last = burst(comboSay(idx), idx, burstCount(if (progress > 0.5f) 5 else 4), throwTime(lens[idx - 1]), t)
                    t = last + breathGap()
                }
            }
        }
        cues += finisherCues

        val summaryHead = st.label.ifEmpty { "Tier $tier" }
        val summaryBody = if (punchless) "movement & defence" else combos.joinToString(" / ")
        val summary = "$summaryHead \u00b7 $summaryBody" +
            (if (downMove.isNotEmpty()) " \u00b7 $downWord: $downMove" else "") +
            (if (hasFinisher) " \u00b7 finisher" else "")

        return Round(
            label, durationSec, cues, summary,
            hasFinisher = hasFinisher, standing = st.label, standingHint = st.hint,
            legend = legend, commandRefs = refs, combos = combos,
        )
    }

    // ---- Standing instructions ----
    // The standing instruction is what the boxer does UNPROMPTED, at his own pace, whenever
    // no command is being called \u2014 a simple, sustainable default (shown persistently on the
    // workout screen). The live COMMANDS are separate and deliberately different from it.
    private enum class Builder { FREE, LEAD, BODY, COUNTER, TWO, THREE }

    private class Standing(
        val label: String,          // on-screen headline, e.g. "SINGLE JABS"
        val hint: String,           // one-line detail under it
        val say: String,            // spoken form for the intro
        val builder: Builder?,      // how numbered combos are generated (null => fixed vocab / punchless)
        val vocab: List<Pair<String, Int>>? = null,
        val punchless: Boolean = false,
        val allowDown: Boolean = true,
        val allowOpen: Boolean = true,
        val maxCombos: Int = 2,
    )

    private val moveOnlyVocab = listOf(
        "Slip {L}" to 1, "Slip {R}" to 1, "Roll {L}" to 1, "Roll {R}" to 1, "Duck under" to 1,
        "Step back" to 1, "Step {L}" to 1, "Step {R}" to 1, "Pivot {L}" to 1, "Pivot {R}" to 1,
        "Circle {L}" to 1, "Circle {R}" to 1, "Block {L}" to 1, "Block {R}" to 1,
        "Parry the jab" to 1, "Bob and weave" to 1,
    )

    /** Pick this round's standing instruction. Harder standings are gated behind higher
     *  complexity, and the defensive/movement ones are kept off the opening round. */
    private fun pickStanding(index: Int, total: Int, complexity: Int, rng: Random): Standing {
        val hard = complexity >= 4
        val notFirst = index > 0
        val pool = mutableListOf<Pair<Standing, Int>>()
        pool += Standing("MIX IT UP", "your combinations \u2014 keep them varied",
            "Standing instruction: keep light combinations going, your own choice.", Builder.FREE) to 3
        pool += Standing("SINGLE JABS", "vary the height, speed and count",
            "Standing instruction: single jabs \u2014 vary the height, speed and count on your own.", Builder.FREE) to 3
        pool += Standing("STICK & MOVE", "jab and circle, hold the range",
            "Standing instruction: stick and move \u2014 jab and circle, hold your range.", Builder.FREE) to 2
        pool += Standing("ONE-TWOS", "sharp, straight one-twos",
            "Standing instruction: steady one-twos \u2014 sharp and straight.", Builder.TWO) to 2
        pool += Standing("DOUBLE JAB\u2013CROSS", "double jab into the cross",
            "Standing instruction: double jab into the cross, over and over.",
            null, vocab = listOf("Double jab, cross" to 3), maxCombos = 1) to 2
        pool += Standing("LEAD HAND", "lead-hand shots only",
            "Standing instruction: work the lead hand \u2014 jab and lead hook.",
            Builder.LEAD, allowOpen = false) to 2
        if (hard) {
            pool += Standing("FLOW THREES", "three-punch combinations",
                "Standing instruction: flowing three-punch combinations.", Builder.THREE) to 2
            if (notFirst) pool += Standing("SLIP & COUNTER", "slip or roll, then fire back",
                "Standing instruction: stay on defence \u2014 slip or roll, then fire straight back.",
                Builder.COUNTER, allowDown = false, allowOpen = false, maxCombos = 1) to 2
            if (notFirst) pool += Standing("KEEP MOVING", "slips, rolls, pivots, footwork",
                "Standing instruction: pure movement \u2014 slips, rolls, pivots and footwork.",
                null, vocab = moveOnlyVocab, punchless = true, allowDown = false, allowOpen = false) to 2
        }
        val totalW = pool.sumOf { it.second }
        var r = rng.nextInt(totalW)
        for ((sd, w) in pool) { if (r < w) return sd; r -= w }
        return pool.first().first
    }

    private fun sectionNoun(type: SectionType) = when (type) {
        SectionType.SHADOW -> "Shadow boxing"
        SectionType.BAG -> "Heavy bag"
        else -> "Round"
    }
}
