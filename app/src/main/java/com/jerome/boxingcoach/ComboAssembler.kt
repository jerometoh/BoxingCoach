package com.jerome.boxingcoach

import kotlin.random.Random

/**
 * Generative, weight-aware combo assembler.
 *
 * Every action needs a weight/balance state to be thrown well and leaves the weight
 * somewhere afterwards. The assembler tracks that state and only appends an action the
 * current weight allows; when the next punch doesn't fit, it bridges with a defence/
 * movement (slip / roll / step …) whose result supplies the needed weight — chosen to
 * match the previous punch (slips answer straights, rolls answer hooks) and never
 * redundantly (no slip onto the foot you're already on).
 *
 * Emergent behaviour (validated): combos open on a straight (jab/cross), body shots are
 * accents, same-hand repeats prefer variation (hook→uppercut, head↔body) over identical,
 * pivots only ever disengage at the end.
 *
 * Weight is stored LEAD/NEUTRAL/REAR in lead/rear terms and rendered to left/right by
 * stance, so it mirrors correctly for southpaw.
 */
object ComboAssembler {

    private enum class Bal { L, N, R }            // weight on lead foot / neutral / rear foot
    private enum class PHand { LEAD, REAR }
    private enum class PLevel { HEAD, BODY }
    private enum class PShape { STR, HOOK, UPP }

    private class Punch(
        val say: String, val hand: PHand, val level: PLevel, val num: Int,
        val needs: Set<Bal>, val leaves: Bal, val shape: PShape,
    )
    private class Trans(val say: String, val leaves: Bal, val family: String)

    private val ANY = setOf(Bal.L, Bal.N, Bal.R)

    // A punch is illegal only from the opposite loaded foot (e.g. a lead hook can't come
    // when the weight is already on the rear foot).
    private val PUNCHES = listOf(
        Punch("jab", PHand.LEAD, PLevel.HEAD, 1, ANY, Bal.N, PShape.STR),
        Punch("jab to the body", PHand.LEAD, PLevel.BODY, 1, ANY, Bal.N, PShape.STR),
        Punch("cross", PHand.REAR, PLevel.HEAD, 2, setOf(Bal.R, Bal.N), Bal.L, PShape.STR),
        Punch("cross to the body", PHand.REAR, PLevel.BODY, 2, setOf(Bal.R, Bal.N), Bal.L, PShape.STR),
        Punch("{L} hook", PHand.LEAD, PLevel.HEAD, 3, setOf(Bal.L, Bal.N), Bal.R, PShape.HOOK),
        Punch("{L} hook to the body", PHand.LEAD, PLevel.BODY, 3, setOf(Bal.L, Bal.N), Bal.R, PShape.HOOK),
        Punch("{R} hook", PHand.REAR, PLevel.HEAD, 4, setOf(Bal.R, Bal.N), Bal.L, PShape.HOOK),
        Punch("{R} hook to the body", PHand.REAR, PLevel.BODY, 4, setOf(Bal.R, Bal.N), Bal.L, PShape.HOOK),
        Punch("{L} uppercut", PHand.LEAD, PLevel.HEAD, 5, setOf(Bal.L, Bal.N), Bal.R, PShape.UPP),
        Punch("{R} uppercut", PHand.REAR, PLevel.HEAD, 6, setOf(Bal.R, Bal.N), Bal.L, PShape.UPP),
        Punch("{R} uppercut to the body", PHand.REAR, PLevel.BODY, 6, setOf(Bal.R, Bal.N), Bal.L, PShape.UPP),
    )

    private val TRANS = listOf(
        Trans("slip {L}", Bal.L, "slip"), Trans("slip {R}", Bal.R, "slip"),
        Trans("roll {L}", Bal.L, "roll"), Trans("roll {R}", Bal.R, "roll"),
        Trans("duck", Bal.N, "duck"), Trans("pull back", Bal.R, "pull"),
        Trans("step {L}", Bal.N, "step"), Trans("step {R}", Bal.N, "step"), Trans("step in", Bal.N, "step"),
    )
    // Exits disengage at the end of a combo — pivots only ever appear here.
    private val EXITS = listOf("step back", "pivot {L}", "pivot {R}", "skip out")

    private class Opts(
        val target: Double = 4.0,
        val handOnly: PHand? = null,
        val levelOnly: PLevel? = null,
        val exactPunches: Int? = null,
        val startDefensive: Boolean = false,
        val allowLinks: Boolean = true,
        val allowExit: Boolean = true,
    )

    data class Combo(val text: String, val actionCount: Int, val score: Double)

    // ---- public builders (RoutineGenerator calls these) ----
    fun free(target: Double, stance: Stance, rng: Random) =
        build(Opts(target = target), stance, rng)
    fun leadOnly(target: Double, stance: Stance, rng: Random) =
        build(Opts(target = target, handOnly = PHand.LEAD), stance, rng)
    fun bodyOnly(target: Double, stance: Stance, rng: Random) =
        build(Opts(target = target, levelOnly = PLevel.BODY), stance, rng)
    fun counters(target: Double, stance: Stance, rng: Random) =
        build(Opts(target = target, startDefensive = true), stance, rng)
    fun exact(nPunches: Int, stance: Stance, rng: Random) =
        build(Opts(exactPunches = nPunches, allowLinks = false, allowExit = false), stance, rng)

    // ---- core ----
    private fun scoreOf(punches: List<Punch>, defMove: Int): Double {
        var s = punches.size + 1.5 * defMove
        for (i in 1 until punches.size) {
            if (punches[i].level != punches[i - 1].level) s += 1.0   // head↔body change
            if (punches[i].hand == punches[i - 1].hand) s += 0.5      // same-hand pair
        }
        return s
    }

    private fun pickPunch(prev: Punch?, handOnly: PHand?, levelOnly: PLevel?, rng: Random): Punch {
        val out = ArrayList<Pair<Punch, Double>>()
        for (p in PUNCHES) {
            if (handOnly != null && p.hand != handOnly) continue
            if (levelOnly != null && p.level != levelOnly) continue
            var w = 1.0
            if (prev == null) {
                // open on a straight (jab first, then cross); a hook/uppercut lead-off is rare
                w = if (p.shape == PShape.STR) (if (p.num == 1) 6.0 else 2.5) else 0.15
                if (p.level == PLevel.BODY && levelOnly == null) w *= 0.5
            } else {
                if (p.num == prev.num && p.level == prev.level && p.num != 1) w *= 0.12 // identical repeat: rare
                w *= if (p.hand == prev.hand) {
                    if (p.shape == prev.shape && p.level == prev.level) 0.4 else 0.85     // same hand: prefer variation
                } else 1.2                                                                // alternating slightly favoured
                if (p.level == PLevel.BODY && levelOnly == null) {
                    w *= 0.35                                    // body is an accent in mixed mode
                    if (prev.level == PLevel.BODY) w *= 0.4      // avoid long body chains
                }
            }
            out.add(p to w)
        }
        val tot = out.sumOf { it.second }
        var r = rng.nextDouble(tot)
        for (o in out) { if (r < o.second) return o.first; r -= o.second }
        return out[0].first
    }

    private fun bridge(cur: Bal, needs: Set<Bal>, prevShape: PShape?, rng: Random): Pair<String, Bal> {
        val cand = ArrayList<Triple<String, Bal, Double>>()
        for (t in TRANS) {
            val redundant = (t.family == "slip" || t.family == "roll" || t.family == "pull") && t.leaves == cur
            if (t.leaves in needs && !redundant) {
                val w = when (t.family) {
                    "roll" -> if (prevShape == PShape.HOOK) 2.5 else 0.5   // rolls answer hooks
                    "slip" -> if (prevShape == PShape.STR || prevShape == PShape.UPP) 2.5 else 0.6 // slips answer straights
                    "step" -> 1.0                                          // step = re-angle / reset
                    "duck" -> 0.5
                    "pull" -> 0.4
                    else -> 1.0
                }
                cand.add(Triple(t.say, t.leaves, w))
            }
        }
        if (cand.isEmpty()) return "step in" to Bal.N
        val tot = cand.sumOf { it.third }
        var r = rng.nextDouble(tot)
        for (c in cand) { if (r < c.third) return c.first to c.second; r -= c.third }
        return cand[0].first to cand[0].second
    }

    private fun build(opts: Opts, stance: Stance, rng: Random): Combo {
        val acts = ArrayList<Pair<String, Boolean>>()   // (say, isPunch)
        val punchSeq = ArrayList<Punch>()
        var cur = Bal.N
        var prev: Punch? = null

        if (opts.startDefensive) {
            val opener = listOf(TRANS[1], TRANS[3], TRANS[0]) // slip {R} / roll {R} / slip {L}
            val o = opener.random(rng)
            acts.add(o.say to false); cur = o.leaves
        }

        var nPunch = 0
        var guard = 0
        while (guard < 40) {
            guard++
            if (opts.exactPunches != null && nPunch >= opts.exactPunches) break
            if (opts.exactPunches == null &&
                scoreOf(punchSeq, acts.count { !it.second }) >= opts.target && nPunch >= 2) break

            var p = pickPunch(prev, opts.handOnly, opts.levelOnly, rng)
            if (cur !in p.needs) {
                if (opts.allowLinks) {
                    val (bsay, bl) = bridge(cur, p.needs, prev?.shape, rng)
                    acts.add(bsay to false); cur = bl
                } else {
                    // no links (sharp 2/3-punch): pick a punch that fits the current weight
                    val fits = PUNCHES.filter {
                        cur in it.needs &&
                            (opts.handOnly == null || it.hand == opts.handOnly) &&
                            (opts.levelOnly == null || it.level == opts.levelOnly)
                    }
                    if (fits.isNotEmpty()) p = fits.random(rng)
                }
            }
            acts.add(p.say to true); cur = p.leaves; prev = p; punchSeq.add(p); nPunch++
        }

        // Optional disengage on longer combos.
        if (opts.exactPunches == null && opts.allowExit && nPunch >= 3 && rng.nextDouble() < 0.45) {
            acts.add(EXITS.random(rng) to false)
        }

        val text = acts.joinToString(", ") { ComboLibrary.render(it.first, stance) }
            .replaceFirstChar { it.uppercase() }
        return Combo(text, acts.size, scoreOf(punchSeq, acts.count { !it.second }))
    }
}
