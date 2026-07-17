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

    private val attackWords = listOf("Go", "Hit", "Shoot")
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

    private fun workRound(
        type: SectionType, index: Int, total: Int, durationSec: Int,
        p: RoutineParams, stance: Stance, rng: Random, coaching: Boolean = true
    ): Round {
        val progress = if (total <= 1) 1f else index.toFloat() / (total - 1)

        // ---- Standing instruction (theme) for this round ----
        val theme = pickTheme(index, total, p.complexity, rng)
        val punchless = theme.punchless
        val allowDown = theme.allowDown && !punchless

        // ---- Tier (combo complexity ceiling) climbs with round position & complexity.
        // Only used by the FREE theme, which draws from the tiered library. ----
        val maxTier = ComboLibrary.combos.maxOf { it.tier }
        val ceiling = scale3(p.complexity, 2f, 3f, 4f).roundToInt().coerceIn(1, maxTier)
        val base = scale3(p.complexity, 1f, 1f, 2f).coerceIn(1f, ceiling.toFloat())
        val tier = (base + progress * (ceiling - base)).toInt().coerceIn(1, ceiling)
        val moveComboChance = scale3(p.complexity, 0f, 0f, 0.35f).coerceIn(0f, 0.6f)

        // Tiered pick for the FREE theme; returns (combo text, action count).
        fun pickComboTiered(): Pair<String, Int> {
            return if (tier >= 3 && rng.nextFloat() < moveComboChance) {
                val s = ComboLibrary.comboWithMovement.random(rng)
                s to (s.count { it == ',' } + 1)
            } else {
                val pool = ComboLibrary.combos.filter { it.tier <= tier && it.tier >= (tier - 1).coerceAtLeast(1) }
                val c = pool.random(rng)
                c.text to c.punches
            }
        }

        // ---- How many combos this round (theme sets the ceiling; complexity the chance) ----
        val twoFloor = scale3(p.complexity, 0f, 0f, 0.15f).coerceAtLeast(0f)
        val twoPeak = scale3(p.complexity, 0f, 0.40f, 0.60f).coerceIn(0f, 0.75f)
        val twoComboChance = (twoFloor + (twoPeak - twoFloor) * progress).coerceIn(0f, 0.75f)
        val comboCount = if (theme.maxCombos >= 2 && rng.nextFloat() < twoComboChance) 2 else 1

        // ---- Assign the round's combo(s): from the theme's vocab, or the tiered library ----
        val assignedPairs = mutableListOf<Pair<String, Int>>()
        if (!punchless) {
            var guard = 0
            while (assignedPairs.size < comboCount && guard < 30) {
                val c = if (theme.vocab != null) theme.vocab.random(rng) else pickComboTiered()
                if (assignedPairs.none { it.first == c.first }) assignedPairs += c
                guard++
            }
            if (assignedPairs.isEmpty())
                assignedPairs += if (theme.vocab != null) theme.vocab.random(rng) else pickComboTiered()
        }
        val renderedCombos = assignedPairs.map { ComboLibrary.render(it.first, stance) }
        val lens = assignedPairs.map { it.second }
        val downMove = if (allowDown) ComboLibrary.render(ComboLibrary.downMoves.random(rng), stance) else ""

        // ---- This round's trigger vocabulary ----
        val goWord = attackWords.random(rng)
        val downWord = downWords.random(rng)
        val label = "${sectionNoun(type)} — Round ${index + 1} of $total"

        // ---- Intro (delivered during the preceding rest, or at round start) + legend ----
        val introText: String
        val legend: String
        if (punchless) {
            introText = "${theme.say} I'll call each movement — flow from one to the next. No punches this round."
            legend = "${theme.label}   ·   follow the called movements"
        } else {
            val triggerLine = if (renderedCombos.size == 2)
                "Combo one: ${renderedCombos[0]}. Combo two: ${renderedCombos[1]}. On \"one\" or \"two\", throw that combo."
            else
                "On \"$goWord\", throw: ${renderedCombos[0]}."
            val downLine = if (downMove.isNotEmpty()) " On \"$downWord\": $downMove." else ""
            introText = (if (theme.say.isNotEmpty()) theme.say + " " else "") +
                triggerLine + " Feint and jab to hold range in between." + downLine

            val head = if (theme.label.isNotEmpty()) "${theme.label}   ·   " else ""
            val trig = if (renderedCombos.size == 2)
                "One → ${renderedCombos[0]}   ·   Two → ${renderedCombos[1]}"
            else "$goWord → ${renderedCombos[0]}"
            val downLeg = if (downMove.isNotEmpty()) "   ·   $downWord → $downMove" else ""
            legend = head + trig + downLeg
        }
        val cues = mutableListOf(Cue(0, introText, isIntro = true))

        // ---- Command stream ----
        // Uses the FULL round (no 8s lead-in / 12s silent tail). Gaps scale with combo
        // length — a long combo takes longer to throw, so it gets more space before the
        // next call. Some rounds finish on a forced-intensity burst (built first, so the
        // body knows where to stop).
        val coachRatio = if (coaching) 0.10f else 0f
        val downFloor = scale3(p.intensity, 0.08f, 0.10f, 0.16f)
        val downPeak = scale3(p.intensity, 0.08f, 0.16f, 0.26f)
        val downRatio = (downFloor + (downPeak - downFloor) * progress).coerceIn(0f, 0.4f)
        val eDown = if (allowDown) downRatio else 0f
        val attackBase = scale3(p.intensity, 6f, 5f, 4f)
        fun attackGap(len: Int): Int =
            (attackBase + len * 1.1f).roundToInt().coerceAtLeast(3) + rng.nextInt(-1, 2)
        fun fillerGap(): Int =
            scale3(p.intensity, 5f, 4f, 3f).roundToInt().coerceAtLeast(2) + rng.nextInt(0, 2)

        val startAt = 2

        // Optional end-of-round finisher (NOT pre-announced in the intro).
        fun buildFinisher(): Pair<List<Cue>, Int> {
            if (punchless || durationSec < 60) return emptyList<Cue>() to 0
            val chance = (scale3(p.intensity, 0.40f, 0.55f, 0.70f) + progress * 0.12f).coerceIn(0f, 0.8f)
            if (rng.nextFloat() >= chance) return emptyList<Cue>() to 0
            val d = durationSec
            val out = mutableListOf<Cue>()
            if (rng.nextBoolean()) {
                // (1) non-stop power shots into a 3-2-1
                val reserve = 12
                if (d - startAt < reserve + 20) return emptyList<Cue>() to 0
                out += Cue(d - 11, "Last ten seconds — non-stop power shots!", isCommand = false)
                out += Cue(d - 6, "Keep going, don't stop!", isCommand = false)
                out += Cue(d - 3, "3", isCommand = true)
                out += Cue(d - 2, "2", isCommand = true)
                out += Cue(d - 1, "1", isCommand = true)
                return out to reserve
            } else {
                // (2) N in a row — Go! Go! Go! — spaced by the round's combo length
                val n = rng.nextInt(2, 6) // 2..5
                val primaryLen = lens.firstOrNull() ?: 2
                val spacing = (1.5f + primaryLen * 0.9f).roundToInt().coerceIn(2, 6)
                val reserve = (2 + n * spacing + 1).coerceAtLeast(10)
                if (d - startAt < reserve + 20) return emptyList<Cue>() to 0
                var ft = d - reserve
                out += Cue(ft, "$n in a row — get ready!", isCommand = false); ft += 2
                repeat(n) { out += Cue(ft.coerceAtMost(d - 1), "Go!", isCommand = true); ft += spacing }
                return out to reserve
            }
        }
        val (finisherCues, finisherReserve) = buildFinisher()
        val hasFinisher = finisherCues.isNotEmpty()
        val bodyEnd = durationSec - (if (hasFinisher) finisherReserve else 4)

        var t = startAt
        var goToggle = false
        while (t < bodyEnd) {
            val roll = rng.nextFloat()
            var isCmd = true
            var comboIdx = 0
            val text: String
            val gap: Int
            if (punchless) {
                if (roll < coachRatio) {
                    text = ComboLibrary.render(ComboLibrary.restTips.random(rng), stance); isCmd = false; gap = fillerGap() + 3
                } else {
                    text = ComboLibrary.render(theme.vocab!!.random(rng).first, stance); gap = fillerGap()
                }
            } else {
                when {
                    roll < coachRatio -> {
                        text = ComboLibrary.render(ComboLibrary.restTips.random(rng), stance); isCmd = false; gap = fillerGap() + 3
                    }
                    allowDown && roll < coachRatio + eDown -> {
                        text = downWord; gap = attackGap(2) + 2
                    }
                    roll < coachRatio + eDown + 0.28f -> {
                        text = ComboLibrary.render(ComboLibrary.spacingCues.random(rng), stance); gap = fillerGap()
                    }
                    else -> {
                        if (renderedCombos.size == 2) {
                            goToggle = !goToggle
                            comboIdx = if (goToggle) 1 else 2
                            text = if (goToggle) "One!" else "Two!"
                            gap = attackGap(lens[comboIdx - 1])
                        } else {
                            text = goWord; gap = attackGap(lens.firstOrNull() ?: 2)
                        }
                    }
                }
            }
            cues += Cue(t, text, isCommand = isCmd, comboIndex = comboIdx)
            t += gap.coerceAtLeast(2)
        }
        cues += finisherCues

        val summaryHead = if (theme.label.isNotEmpty()) theme.label else "Tier $tier"
        val summaryBody = if (punchless) "movement & defence" else renderedCombos.joinToString(" / ")
        val summary = "$summaryHead · $summaryBody" +
            (if (downMove.isNotEmpty()) " · $downWord: $downMove" else "") +
            (if (hasFinisher) " · finisher" else "")
        return Round(label, durationSec, cues, summary, legend = legend, combos = renderedCombos, hasFinisher = hasFinisher)
    }

    // ---- Standing instructions (round themes) ----
    // A theme governs the whole round. Simple themes allow up to 2 combos + a down-move;
    // harder ones keep the live calls minimal. `vocab` is (call text, action count);
    // null vocab = draw from the tiered library (the FREE theme). Built against the
    // current flat combo library; will move onto the structured combo model later.
    private class Theme(
        val say: String,
        val label: String,
        val maxCombos: Int,
        val allowDown: Boolean,
        val punchless: Boolean = false,
        val vocab: List<Pair<String, Int>>? = null,
    )

    private val twoPunchVocab by lazy { ComboLibrary.combos.filter { it.punches == 2 }.map { it.text to it.punches } }
    private val threePunchVocab by lazy { ComboLibrary.combos.filter { it.punches == 3 }.map { it.text to it.punches } }
    private val leadOnlyVocab = listOf(
        "Jab" to 1, "{L} hook" to 1, "{L} uppercut" to 1, "Double jab" to 2,
        "Jab, {L} hook" to 2, "Jab, {L} uppercut" to 2, "{L} hook to the body, {L} hook to the head" to 2,
    )
    private val bodyOnlyVocab = listOf(
        "Jab to the body" to 1, "Cross to the body" to 1, "{L} hook to the body" to 1,
        "Cross to the body, {L} hook to the body" to 2, "Jab to the body, cross to the body" to 2,
        "{R} uppercut to the body" to 1, "{L} hook to the body, {R} uppercut to the body" to 2,
    )
    private val counterVocab = listOf(
        "Slip {R}, cross" to 2, "Jab, slip {R}, cross" to 3, "One-two, roll under, {L} hook" to 3,
        "Slip {L}, {L} hook to the body, {L} hook to the head" to 3, "Roll under, cross, {L} hook" to 3,
    )
    private val moveOnlyVocab = listOf(
        "Slip {L}" to 1, "Slip {R}" to 1, "Roll under" to 1, "Duck under" to 1, "Step back" to 1,
        "Step {L}" to 1, "Step {R}" to 1, "Pivot {L}" to 1, "Pivot {R}" to 1, "Angle out {L}" to 1,
        "Angle out {R}" to 1, "Circle {L}" to 1, "Circle {R}" to 1, "Block and reset" to 1,
        "Parry the jab" to 1, "Slip {L}, slip {R}" to 2, "Bob and weave" to 1,
    )

    /** Pick a standing instruction. Most rounds get one; harder themes are gated behind
     *  higher complexity and (for movement/counter rounds) kept off the opening round. */
    private fun pickTheme(index: Int, total: Int, complexity: Int, rng: Random): Theme {
        val hard = complexity >= 4
        val notFirst = index > 0
        val pool = mutableListOf<Pair<Theme, Int>>()
        pool += Theme("", "", 2, true) to 3 // FREE — tiered library, current behaviour
        pool += Theme("Any two-punch combos this round — sharp and clean.", "2-PUNCH", 2, true, vocab = twoPunchVocab) to 3
        pool += Theme("Single jabs only. Vary the speed, level and rhythm.", "JAB ONLY", 1, false, vocab = listOf("Jab" to 1)) to 2
        pool += Theme("Double jab, then cross — same combo every rep. Sharpen it.", "DOUBLE JAB–CROSS", 1, true, vocab = listOf("Double jab, cross" to 3)) to 2
        pool += Theme("Lead hand only — jabs, hooks and uppercuts off the lead.", "LEAD HAND ONLY", 2, true, vocab = leadOnlyVocab) to 2
        pool += Theme("Body shots only. Bend the knees and dig in.", "BODY ONLY", 2, true, vocab = bodyOnlyVocab) to 2
        if (hard) {
            pool += Theme("Any three-punch combos — flow them together.", "3-PUNCH", 2, true, vocab = threePunchVocab) to 3
            if (notFirst) pool += Theme("Counter work — slip or roll first, then fire back.", "COUNTERS", 1, false, vocab = counterVocab) to 2
            if (notFirst) pool += Theme("No punches — movement and defence only. Slips, rolls, pivots, footwork.", "MOVEMENT ONLY", 1, false, punchless = true, vocab = moveOnlyVocab) to 2
        }
        val totalW = pool.sumOf { it.second }
        var r = rng.nextInt(totalW)
        for ((th, w) in pool) { if (r < w) return th; r -= w }
        return pool.first().first
    }

    private fun sectionNoun(type: SectionType) = when (type) {
        SectionType.SHADOW -> "Shadow boxing"
        SectionType.BAG -> "Heavy bag"
        else -> "Round"
    }
}
