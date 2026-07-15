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
        seed: Long = System.nanoTime()
    ): Routine {
        val rng = Random(seed)
        val sections = mutableListOf<Section>()

        if (params.includeWarmup) sections += warmup(rng, stance, countReps)
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
    fun regenerateSection(routine: Routine, sectionIndex: Int, stance: Stance, countReps: Boolean = true): Routine {
        val p = routine.params
        val rng = Random(System.nanoTime())
        val old = routine.sections[sectionIndex]
        var fresh = when (old.type) {
            SectionType.WARMUP -> warmup(rng, stance, countReps)
            SectionType.SHADOW -> workSection(SectionType.SHADOW, old.title, p.shadowRounds, p.shadowRoundSec, p, stance, rng)
            SectionType.BAG -> workSection(SectionType.BAG, old.title, p.bagRounds, p.bagRoundSec, p, stance, rng)
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
    fun regenerateRound(routine: Routine, sectionIndex: Int, roundIndex: Int, stance: Stance, countReps: Boolean = true): Routine {
        val section = routine.sections[sectionIndex]
        val p = routine.params
        val rng = Random(System.nanoTime())
        if (section.type != SectionType.SHADOW && section.type != SectionType.BAG)
            return regenerateSection(routine, sectionIndex, stance, countReps)

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

    // Warm-up pacing constants (seconds).
    private const val WARMUP_TARGET_SEC = 250   // ~80% of the natural ~312s; starting point, tune here
    private const val WARMUP_ANNOUNCE = 3        // time to say the exercise name before counting
    private const val WARMUP_BREATH = 2          // gap after an exercise before the next
    private const val WARMUP_SWITCH = 1          // "Switch" beat between sides

    /** Real length a move occupies, given whether we're counting reps aloud. */
    private fun warmupBlockSec(mv: ComboLibrary.WarmupMove, countReps: Boolean): Int = when (mv.kind) {
        ComboLibrary.MoveKind.HOLD ->
            WARMUP_ANNOUNCE + mv.holdSec + WARMUP_BREATH
        ComboLibrary.MoveKind.PER_SIDE ->
            if (countReps) WARMUP_ANNOUNCE + mv.reps * mv.secPerCount + WARMUP_SWITCH + mv.reps * mv.secPerCount + WARMUP_BREATH
            else WARMUP_ANNOUNCE + mv.reps * mv.secPerCount + WARMUP_SWITCH + WARMUP_BREATH
        ComboLibrary.MoveKind.REP ->
            if (countReps) WARMUP_ANNOUNCE + mv.reps * mv.secPerCount + WARMUP_BREATH
            else WARMUP_ANNOUNCE + mv.reps * mv.secPerCount + WARMUP_BREATH
    }

    /**
     * Rep-driven warm-up. Each exercise is announced, then counted aloud at its own
     * contextual cadence (secPerCount) so there's a spoken beat throughout instead of
     * long silences. Per-side moves count one side, say "Switch", then the other.
     * Holds are announced and counted DOWN to finish. Counts land on whole seconds
     * because the engine fires one cue per second.
     *
     * We take as many shuffled moves as fit WARMUP_TARGET_SEC and stop — so the set
     * varies session to session and the total stays near target.
     */
    private fun warmup(rng: Random, stance: Stance, countReps: Boolean): Section {
        val shuffled = ComboLibrary.warmupMoves.shuffled(rng)

        // Greedily take moves until the next one would overrun the target.
        val picked = mutableListOf<ComboLibrary.WarmupMove>()
        var budget = 0
        for (mv in shuffled) {
            val blk = warmupBlockSec(mv, countReps)
            if (picked.isNotEmpty() && budget + blk > WARMUP_TARGET_SEC) continue
            picked += mv; budget += blk
        }

        val cues = mutableListOf<Cue>()
        val introReserve = 5
        cues += Cue(0, listOf(
            "Let's warm up — ${picked.size} exercises, top to bottom. Easy pace, follow my count.",
            "Warm-up time. ${picked.size} exercises head to toe — move with the count.",
            "Warming up: ${picked.size} exercises from the top down. Loosen everything off."
        ).random(rng))

        var t = introReserve
        picked.forEachIndexed { i, mv ->
            val name = ComboLibrary.render(mv.text, stance)
            val lead = when {
                i == 0 -> "First up"
                i == picked.size - 1 -> "Last one"
                else -> listOf("Next", "Now", "Moving on").random(rng)
            }

            when (mv.kind) {
                ComboLibrary.MoveKind.REP -> {
                    cues += Cue(t, "$lead: $name." + if (countReps) " Ready…" else "")
                    t += WARMUP_ANNOUNCE
                    if (countReps) {
                        for (r in 1..mv.reps) {
                            cues += Cue(t, if (r == mv.reps) "and ${mv.reps}." else "$r…")
                            t += mv.secPerCount
                        }
                    } else {
                        t += mv.reps * mv.secPerCount
                    }
                    t += WARMUP_BREATH
                }

                ComboLibrary.MoveKind.PER_SIDE -> {
                    cues += Cue(t, "$lead: $name. ${mv.reps} each side." +
                        if (countReps) " Ready…" else " Switch halfway.")
                    t += WARMUP_ANNOUNCE
                    if (countReps) {
                        for (r in 1..mv.reps) { cues += Cue(t, "$r…"); t += mv.secPerCount }
                        cues += Cue(t, "Switch."); t += WARMUP_SWITCH
                        for (r in 1..mv.reps) {
                            cues += Cue(t, if (r == mv.reps) "and ${mv.reps}." else "$r…")
                            t += mv.secPerCount
                        }
                    } else {
                        val half = mv.reps * mv.secPerCount
                        t += half
                        cues += Cue(t, "Switch sides."); t += WARMUP_SWITCH + half
                    }
                    t += WARMUP_BREATH
                }

                ComboLibrary.MoveKind.HOLD -> {
                    cues += Cue(t, "$lead: $name.")
                    t += WARMUP_ANNOUNCE
                    val cd = if (mv.holdSec >= 8) 5 else 3   // 5-4-3-2-1 or 3-2-1
                    val holdBeforeCd = (mv.holdSec - cd).coerceAtLeast(0)
                    t += holdBeforeCd
                    for (n in cd downTo 1) { cues += Cue(t, "$n…"); t += 1 }
                    t += WARMUP_BREATH
                }
            }
        }
        val total = t + 2
        cues += Cue(total - 2, "Warm-up done. Shake it out — let's get to work.")

        val label = "Warm-up — ${picked.size} exercises"
        val round = Round("Warm-up", total, cues,
            "Dynamic warm-up — ${picked.size} exercises, ${total / 60} min ${total % 60}s")
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

        // ---- Pick the assigned combo(s) and this round's fixed conditioning move ----
        fun pickCombo(): String {
            return if (tier >= 3 && p.difficulty == Difficulty.ADVANCED && rng.nextFloat() < 0.35f) {
                ComboLibrary.comboWithMovement.random(rng)
            } else {
                val pool = ComboLibrary.combos.filter { it.tier <= tier && it.tier >= (tier - 1).coerceAtLeast(1) }
                pool.random(rng).text
            }
        }
        val assigned = (1..comboCount).map { pickCombo() }.distinct().ifEmpty { listOf(pickCombo()) }
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
            "Two combos. One: ${renderedCombos[0]}. Two: ${renderedCombos[1]}. " +
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
        var t = introReserve
        var goToggle = false
        while (t < durationSec - 12) {
            val roll = rng.nextFloat()
            val text = when {
                roll < downRatio -> downWord
                roll < downRatio + 0.35f -> ComboLibrary.render(ComboLibrary.spacingCues.random(rng), stance)
                else -> {
                    if (renderedCombos.size == 2) {
                        goToggle = !goToggle
                        if (goToggle) "$goWord one" else "$goWord two"
                    } else goWord
                }
            }
            cues += Cue(t, text, isCommand = true)
            t += rng.nextInt(gapRange.first, gapRange.last + 1)
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
