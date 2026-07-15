package com.jerome.boxingcoach

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
            "Let's warm up — ${picked.size} exercises, top to bottom. Follow my count.",
            "Warm-up time. ${picked.size} exercises head to toe. Move with the count.",
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
            steps += when (mv.kind) {
                ComboLibrary.MoveKind.REP -> GuidedStep(
                    announce = "$lead: $name.",
                    reps = if (countReps) mv.reps else 0,
                    secPerCount = mv.secPerCount,
                )
                ComboLibrary.MoveKind.PER_SIDE -> GuidedStep(
                    announce = "$lead: $name. ${mv.reps} each side.",
                    reps = if (countReps) mv.reps else 0,
                    secPerCount = mv.secPerCount,
                    perSide = true,
                    holdSec = if (countReps) 0 else mv.reps * mv.secPerCount, // silent timed hold per side when not counting
                )
                ComboLibrary.MoveKind.HOLD -> GuidedStep(
                    announce = "$lead: $name.",
                    holdSec = mv.holdSec,
                )
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
            steps += GuidedStep(announce = "$lead: $name.", holdSec = holdEach)
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
                    holdSec = mv.holdSec, perSide = true)
            } else {
                GuidedStep(announce = "$lead: $name.", holdSec = mv.holdSec)
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
        val rest = when (p.intensity) {
            Intensity.LOW -> p.restSec + 15
            Intensity.MEDIUM -> p.restSec
            Intensity.HIGH -> (p.restSec - 15).coerceAtLeast(30)
        }
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

        // ---- Tier (combo complexity ceiling) climbs with round position & difficulty ----
        val ceiling = when (p.difficulty) {
            Difficulty.BEGINNER -> 2
            Difficulty.INTERMEDIATE -> 3
            Difficulty.ADVANCED -> 4
        }
        val base = when (p.difficulty) {
            Difficulty.BEGINNER -> 1f
            Difficulty.INTERMEDIATE -> 1f
            Difficulty.ADVANCED -> 2f
        }
        val tier = (base + progress * (ceiling - base)).toInt().coerceIn(1, ceiling)

        // ---- How many combos assigned this round ----
        val twoComboChance = when (p.difficulty) {
            Difficulty.BEGINNER -> 0f
            Difficulty.INTERMEDIATE -> if (progress > 0.6f) 0.35f else 0f
            Difficulty.ADVANCED -> if (progress > 0.3f) 0.55f else 0.15f
        }
        val comboCount = if (rng.nextFloat() < twoComboChance) 2 else 1

        // ---- Pick the assigned combo(s) and this round's fixed conditioning move ----
        fun pickCombo(): String {
            return if (tier >= 3 && p.difficulty == Difficulty.ADVANCED && rng.nextFloat() < 0.35f) {
                ComboLibrary.comboWithMovement.random(rng)
            } else {
                val pool = ComboLibrary.combos.filter { it.tier <= tier && it.tier >= (tier - 1).coerceAtLeast(1) }
                pool.random(rng).text
            }
        }
        // Pick `comboCount` distinct combos; re-roll duplicates instead of letting
        // distinct() silently collapse a two-combo round back to one.
        val assigned = mutableListOf<String>()
        var guard = 0
        while (assigned.size < comboCount && guard < 20) {
            val c = pickCombo()
            if (c !in assigned) assigned += c
            guard++
        }
        if (assigned.isEmpty()) assigned += pickCombo()
        val renderedCombos = assigned.map { ComboLibrary.render(it, stance) }
        val downMove = ComboLibrary.render(ComboLibrary.downMoves.random(rng), stance)

        // ---- This round's trigger vocabulary ----
        val goWord = attackWords.random(rng)
        val downWord = downWords.random(rng)

        val label = "${sectionNoun(type)} — Round ${index + 1} of $total"

        // ---- Intro (delivered by the engine during preceding rest, or at round start) ----
        val introText = if (renderedCombos.size == 1) {
            "One combo: ${renderedCombos[0]}. On \"$goWord\", throw it. " +
                "Feint and jab to hold range in between. On \"$downWord\": $downMove."
        } else {
            "Two combos. Combo one: ${renderedCombos[0]}. Combo two: ${renderedCombos[1]}. " +
                "Again — one is ${renderedCombos[0]}, two is ${renderedCombos[1]}. " +
                "On \"$goWord one\" or \"$goWord two\", throw that combo. " +
                "Feint and jab in between. On \"$downWord\": $downMove."
        }
        val legend = if (renderedCombos.size == 1) {
            "$goWord → ${renderedCombos[0]}   ·   $downWord → $downMove"
        } else {
            "$goWord 1 → ${renderedCombos[0]}   ·   $goWord 2 → ${renderedCombos[1]}   ·   $downWord → $downMove"
        }
        val cues = mutableListOf(Cue(0, introText, isIntro = true))

        // ---- Command stream ----
        val gapRange = when (p.intensity) {
            Intensity.LOW -> 10..14
            Intensity.MEDIUM -> 8..12
            Intensity.HIGH -> 6..10
        }
        val downRatio = when (p.intensity) {
            Intensity.LOW -> 0.08f
            Intensity.MEDIUM -> if (progress > 0.4f) 0.16f else 0.10f
            Intensity.HIGH -> if (progress > 0.2f) 0.26f else 0.16f
        }
        val introReserve = 8 // seconds before the first command
        // Chance that a given slot is a coaching tip rather than a command. Tips are
        // spoken between combos (not on "Go"), replacing the old rest-period tips.
        val coachRatio = if (coaching) 0.10f else 0f
        var t = introReserve
        var goToggle = false
        while (t < durationSec - 12) {
            val roll = rng.nextFloat()
            var extraGap = 0
            val (text, isCmd) = when {
                roll < coachRatio -> {
                    extraGap = 3 // give the longer line room before the next command
                    ComboLibrary.render(ComboLibrary.restTips.random(rng), stance) to false
                }
                roll < coachRatio + downRatio -> downWord to true
                roll < coachRatio + downRatio + 0.32f ->
                    ComboLibrary.render(ComboLibrary.spacingCues.random(rng), stance) to true
                else -> {
                    if (renderedCombos.size == 2) {
                        goToggle = !goToggle
                        (if (goToggle) "$goWord one" else "$goWord two") to true
                    } else goWord to true
                }
            }
            cues += Cue(t, text, isCommand = isCmd)
            t += rng.nextInt(gapRange.first, gapRange.last + 1) + extraGap
        }

        val summary = "Tier $tier · " + renderedCombos.joinToString(" / ") + " · $downWord: $downMove"
        return Round(label, durationSec, cues, summary, legend = legend)
    }

    private fun sectionNoun(type: SectionType) = when (type) {
        SectionType.SHADOW -> "Shadow boxing"
        SectionType.BAG -> "Heavy bag"
        else -> "Round"
    }
}
