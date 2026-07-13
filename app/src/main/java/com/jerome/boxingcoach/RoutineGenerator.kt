package com.jerome.boxingcoach

import kotlin.random.Random

/**
 * Generates a full routine from params.
 *
 * Escalation model:
 *  - Each work round gets a complexity "tier" 1..4 derived from its position in the
 *    section AND the Difficulty setting. Round 1 always starts simple; later rounds
 *    climb faster/higher at higher difficulty.
 *  - Intensity controls cue density (shorter gaps), conditioning insertions, and
 *    rest length trimming.
 *  - Difficulty controls combo tier ceiling, open-combo ratio, movement layering,
 *    and single-focus round probability.
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
        // Only meaningful for work sections; others regenerate whole section.
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
        val ceiling = when (p.difficulty) {
            Difficulty.BEGINNER -> 3
            Difficulty.INTERMEDIATE -> 4
            Difficulty.ADVANCED -> 4
        }
        // Tier climbs with round position; advanced climbs faster and starts higher.
        val base = when (p.difficulty) {
            Difficulty.BEGINNER -> 1f
            Difficulty.INTERMEDIATE -> 1f
            Difficulty.ADVANCED -> 1.5f
        }
        val tier = (base + progress * (ceiling - base)).toInt().coerceIn(1, ceiling)

        val label = "${sectionNoun(type)} — Round ${index + 1} of $total"

        // Single-focus round? Never the first round; more likely later and at higher difficulty.
        val focusChance = when (p.difficulty) {
            Difficulty.BEGINNER -> 0.08f
            Difficulty.INTERMEDIATE -> 0.15f
            Difficulty.ADVANCED -> 0.22f
        } * if (index == 0) 0f else 1f
        if (rng.nextFloat() < focusChance) {
            val theme = ComboLibrary.render(ComboLibrary.focusThemes.random(rng), stance)
            val cues = mutableListOf(Cue(0, theme))
            // Sparse mid-round reminders + a switch-up for hook-focus style themes
            var t = durationSec / 3
            cues += Cue(t, "Stay on it. Same focus.")
            t = durationSec * 2 / 3
            cues += Cue(t, pick(rng, "Pick up the pace for the last minute.", "Add head movement between reps.", "Double up now — two at a time."))
            return Round(label, durationSec, cues, "FOCUS ROUND: ${theme.removePrefix("This whole round: ")}")
        }

        // Normal round: build a cue timeline with randomized gaps.
        val gapRange = when (p.intensity) {
            Intensity.LOW -> 12..16
            Intensity.MEDIUM -> 9..14
            Intensity.HIGH -> 7..11
        }
        val openRatio = when (p.difficulty) {
            Difficulty.BEGINNER -> 0.10f
            Difficulty.INTERMEDIATE -> 0.25f
            Difficulty.ADVANCED -> 0.40f
        }
        val movementRatio = when (p.difficulty) {
            Difficulty.BEGINNER -> 0.15f
            Difficulty.INTERMEDIATE -> 0.25f
            Difficulty.ADVANCED -> 0.30f
        }
        val conditioningRatio = when (p.intensity) {
            Intensity.LOW -> 0f
            Intensity.MEDIUM -> if (progress > 0.5f) 0.10f else 0f
            Intensity.HIGH -> if (progress > 0.3f) 0.20f else 0.08f
        }

        val cues = mutableListOf<Cue>()
        var t = 3 // first cue almost immediately
        val summaryBits = mutableSetOf<String>()
        while (t < durationSec - 8) {
            val roll = rng.nextFloat()
            val text = when {
                roll < conditioningRatio && type == SectionType.BAG -> {
                    summaryBits += "conditioning"
                    ComboLibrary.conditioning.random(rng)
                }
                roll < conditioningRatio + movementRatio -> {
                    summaryBits += "movement"
                    if (tier >= 3 && rng.nextBoolean())
                        ComboLibrary.comboWithMovement.random(rng)
                    else ComboLibrary.movements.random(rng)
                }
                roll < conditioningRatio + movementRatio + openRatio && tier >= 2 -> {
                    summaryBits += "open combos"
                    val pool = ComboLibrary.openCombos.filter { it.second <= tier + 1 }
                    pool.random(rng).first
                }
                else -> {
                    summaryBits += "tier-$tier combos"
                    val pool = ComboLibrary.combos.filter { it.tier <= tier && it.tier >= (tier - 1).coerceAtLeast(1) }
                    pool.random(rng).text
                }
            }
            cues += Cue(t, ComboLibrary.render(text, stance))
            t += rng.nextInt(gapRange.first, gapRange.last + 1)
        }
        // Final push at high intensity
        if (p.intensity == Intensity.HIGH && durationSec >= 60) {
            cues += Cue(durationSec - 15, "Last fifteen seconds — empty the tank!")
        }
        val summary = "Tier $tier · " + summaryBits.joinToString(", ")
        return Round(label, durationSec, cues, summary)
    }

    private fun sectionNoun(type: SectionType) = when (type) {
        SectionType.SHADOW -> "Shadow boxing"
        SectionType.BAG -> "Heavy bag"
        else -> "Round"
    }

    private fun pick(rng: Random, vararg options: String) = options[rng.nextInt(options.size)]
}
