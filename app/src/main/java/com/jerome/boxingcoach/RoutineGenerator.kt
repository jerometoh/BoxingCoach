package com.jerome.boxingcoach

import kotlin.random.Random

/**
 * Generates a full routine from params.
 *
 * Round model (call-and-response): each work round is assigned ONE combo, or
 * TWO for later/harder rounds, announced in full before the round starts (an
 * "intro" cue at t=0). During the round, only short trigger words are called:
 *  - "Go" (or "Go one" / "Go two" when two combos are assigned) — throw the
 *    assigned combo.
 *  - "Down — ..." — a quick conditioning move.
 *  - Spacing fillers ("Feint", "Jab, keep range", "Circle left"...) — used
 *    between attacks so the user isn't standing idle waiting on a command.
 * This keeps live listening simple: you already know the combo, you're just
 * waiting for the trigger, rather than parsing a new combo every few seconds.
 *
 * Escalation: round position + Difficulty controls combo complexity (tier) and
 * whether 1 or 2 combos are assigned. Intensity controls how densely commands
 * are called and how often "Down" appears, plus rest length.
 */
object RoutineGenerator {

    fun generate(params: RoutineParams, stance: Stance, seed: Long = System.nanoTime()): Routine {
        val rng = Random(seed)
        val sections = mutableListOf<Section>()

        if (params.includeWarmup) sections += warmup(rng, stance)
        if (params.includeShadow) sections += workSection(
            SectionType.SHADOW, "Shadow boxing (no gloves)",
            params.shadowRounds, params.shadowRoundSec, params, stance, rng
        )
        if (params.includeBag) sections += workSection(
            SectionType.BAG, "Heavy bag",
            params.bagRounds, params.bagRoundSec, params, stance, rng
        )
        if (params.includeCore) sections += core(params.coreSec, rng, stance)
        if (params.includeCooldown) sections += cooldown(rng)

        return Routine(params = params, sections = sections)
    }

    /** Regenerate one section of an existing routine, keeping the rest. */
    fun regenerateSection(routine: Routine, sectionIndex: Int, stance: Stance): Routine {
        val p = routine.params
        val rng = Random(System.nanoTime())
        val old = routine.sections[sectionIndex]
        val fresh = when (old.type) {
            SectionType.WARMUP -> warmup(rng, stance)
            SectionType.SHADOW -> workSection(SectionType.SHADOW, old.title, p.shadowRounds, p.shadowRoundSec, p, stance, rng)
            SectionType.BAG -> workSection(SectionType.BAG, old.title, p.bagRounds, p.bagRoundSec, p, stance, rng)
            SectionType.CORE -> core(p.coreSec, rng, stance)
            SectionType.COOLDOWN -> cooldown(rng)
        }
        val newSections = routine.sections.toMutableList().also { it[sectionIndex] = fresh }
        return routine.copy(sections = newSections)
    }

    /** Regenerate a single work round within a section. */
    fun regenerateRound(routine: Routine, sectionIndex: Int, roundIndex: Int, stance: Stance): Routine {
        val section = routine.sections[sectionIndex]
        val p = routine.params
        val rng = Random(System.nanoTime())
        if (section.type != SectionType.SHADOW && section.type != SectionType.BAG)
            return regenerateSection(routine, sectionIndex, stance)

        val workRounds = section.rounds.filter { !it.isRest }
        val target = section.rounds[roundIndex]
        if (target.isRest) return routine
        val workIdx = workRounds.indexOf(target)
        val fresh = workRound(section.type, workIdx, workRounds.size, target.durationSec, p, stance, rng)
        val newRounds = section.rounds.toMutableList().also { it[roundIndex] = fresh }
        val newSections = routine.sections.toMutableList().also { it[sectionIndex] = section.copy(rounds = newRounds) }
        return routine.copy(sections = newSections)
    }

    // ------------------------------------------------------------------

    private fun warmup(rng: Random, stance: Stance): Section {
        val moves = ComboLibrary.warmupMoves.shuffled(rng).take(8)
        val per = 300 / moves.size
        val cues = moves.mapIndexed { i, m -> Cue(i * per, ComboLibrary.render(m, stance)) }
        val round = Round("Warm-up", 300, cues, "Dynamic warm-up — ${moves.size} movements, 5 min")
        return Section(SectionType.WARMUP, "Warm-up", listOf(round))
    }

    private fun core(totalSec: Int, rng: Random, stance: Stance): Section {
        val count = (totalSec / 50).coerceIn(4, 8)
        val moves = ComboLibrary.coreMoves.shuffled(rng).take(count)
        val per = totalSec / count
        val cues = moves.mapIndexed { i, m -> Cue(i * per, ComboLibrary.render(m, stance) + ", ${per} seconds") }
        val round = Round("Core", totalSec, cues, "Core circuit — $count exercises, ${totalSec / 60} min")
        return Section(SectionType.CORE, "Core", listOf(round))
    }

    private fun cooldown(rng: Random): Section {
        val moves = ComboLibrary.cooldownMoves.shuffled(rng).take(6)
        val total = 240
        val per = total / moves.size
        val cues = moves.mapIndexed { i, m -> Cue(i * per, m) }
        val round = Round("Cool-down", total, cues, "Static stretches and breathing — 4 min")
        return Section(SectionType.COOLDOWN, "Cool-down", listOf(round))
    }

    private fun workSection(
        type: SectionType, title: String,
        roundCount: Int, roundSec: Int,
        p: RoutineParams, stance: Stance, rng: Random
    ): Section {
        val rest = when (p.intensity) {
            Intensity.LOW -> p.restSec + 15
            Intensity.MEDIUM -> p.restSec
            Intensity.HIGH -> (p.restSec - 15).coerceAtLeast(30)
        }
        val rounds = mutableListOf<Round>()
        for (i in 0 until roundCount) {
            rounds += workRound(type, i, roundCount, roundSec, p, stance, rng)
            if (i < roundCount - 1) rounds += Round(
                "Rest", rest, emptyList(), "Rest — ${rest}s", isRest = true
            )
        }
        return Section(type, title, rounds)
    }

    private fun workRound(
        type: SectionType, index: Int, total: Int, durationSec: Int,
        p: RoutineParams, stance: Stance, rng: Random
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

        // ---- Pick the assigned combo(s) ----
        fun pickCombo(): String {
            // Advanced/high-tier rounds occasionally draw a combo+movement pattern instead of a plain combo.
            return if (tier >= 3 && p.difficulty == Difficulty.ADVANCED && rng.nextFloat() < 0.35f) {
                ComboLibrary.comboWithMovement.random(rng)
            } else {
                val pool = ComboLibrary.combos.filter { it.tier <= tier && it.tier >= (tier - 1).coerceAtLeast(1) }
                pool.random(rng).text
            }
        }
        val assigned = (1..comboCount).map { pickCombo() }.distinct().ifEmpty { listOf(pickCombo()) }
        val renderedCombos = assigned.map { ComboLibrary.render(it, stance) }

        val label = "${sectionNoun(type)} — Round ${index + 1} of $total"

        // ---- Intro cue: explain the round before it starts ----
        val introText = if (renderedCombos.size == 1) {
            "This round: focus on ${renderedCombos[0]}. When I say go, throw it. " +
                "Feint and jab to hold range in between. When I say down, hit the floor for a quick move."
        } else {
            "This round, two combos. One: ${renderedCombos[0]}. Two: ${renderedCombos[1]}. " +
                "When I call go one or go two, throw that combo. Feint and jab in between to hold range. " +
                "When I say down, hit the floor for a quick move."
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
        val introReserve = 6 // seconds to let the intro line finish before commands start
        var t = introReserve
        var goToggle = false
        while (t < durationSec - 6) {
            val roll = rng.nextFloat()
            val text = when {
                roll < downRatio -> ComboLibrary.render(ComboLibrary.downCommands.random(rng), stance)
                roll < downRatio + 0.35f -> ComboLibrary.render(ComboLibrary.spacingCues.random(rng), stance)
                else -> {
                    if (renderedCombos.size == 2) {
                        goToggle = !goToggle
                        if (goToggle) "Go one" else "Go two"
                    } else "Go"
                }
            }
            cues += Cue(t, text, isCommand = true)
            t += rng.nextInt(gapRange.first, gapRange.last + 1)
        }
        if (p.intensity == Intensity.HIGH && durationSec >= 60) {
            cues += Cue(durationSec - 15, "Last fifteen seconds — empty the tank!", isCommand = true)
        }

        val summary = "Tier $tier · " + renderedCombos.joinToString(" / ")
        return Round(label, durationSec, cues, summary)
    }

    private fun sectionNoun(type: SectionType) = when (type) {
        SectionType.SHADOW -> "Shadow boxing"
        SectionType.BAG -> "Heavy bag"
        else -> "Round"
    }
}
