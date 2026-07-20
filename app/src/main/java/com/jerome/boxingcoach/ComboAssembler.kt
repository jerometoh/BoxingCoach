package com.jerome.boxingcoach

import kotlin.random.Random

/**
 * Generative, weight-aware, RANGE-aware combo assembler.
 *
 * WEIGHT: every action needs a balance state to be thrown well and leaves the weight
 * somewhere afterwards. The assembler tracks that state and only appends a punch the
 * current weight allows; when the next punch doesn't fit, it bridges with an IN-RANGE
 * reset (slip / roll / duck / pull) whose result supplies the needed weight — chosen to
 * match the previous punch (slips answer straights, rolls answer hooks) and never
 * redundantly. Steps are NOT bridges: "step in" is a range change owned by the caller
 * (RANGE mode), and steps/pivots only ever appear as terminal EXITS here.
 *
 * RANGE: two ranges. LONG (distance) = jab, jab to the body, cross to the body, feint.
 * INSIDE (mid + close) = cross to the head, lead/rear hooks (head & body), lead/rear
 * uppercuts (rear also to the body). Note cross-to-BODY is a LONG shot, cross-to-HEAD is
 * INSIDE. Builders:
 *   - outRange : distance jabbing patterns (LONG atoms only, ≤2 jabs in a row, may end on a jab).
 *   - inside   : opens on a LONG entry (jab / jab+hook / feint / feint+hook / cross-body),
 *                works INSIDE, finishes on a power shot (never a bare jab), optional exit.
 *   - free     : loose, any range; still finishes on power (no bare-jab endings).
 *
 * Weight is stored LEAD/NEUTRAL/REAR in lead/rear terms and rendered to left/right by
 * stance, so it mirrors correctly for southpaw.
 */
object ComboAssembler {

    private enum class Bal { L, N, R }            // weight on lead foot / neutral / rear foot
    private enum class PHand { LEAD, REAR }
    private enum class PLevel { HEAD, BODY }
    private enum class PShape { STR, HOOK, UPP }
    private enum class PRange { LONG, INSIDE }

    private class Punch(
        val say: String, val hand: PHand, val level: PLevel, val num: Int,
        val needs: Set<Bal>, val leaves: Bal, val shape: PShape, val range: PRange,
    )
    private class Trans(val say: String, val leaves: Bal, val family: String)

    private fun Punch.isStraightBody() = shape == PShape.STR && level == PLevel.BODY
    private fun Punch.isJab() = shape == PShape.STR && hand == PHand.LEAD  // jab head/body

    private val ANY = setOf(Bal.L, Bal.N, Bal.R)

    // A punch is illegal only from the opposite loaded foot (e.g. a lead hook can't come
    // when the weight is already on the rear foot). range = LONG (distance) / INSIDE.
    private val PUNCHES = listOf(
        Punch("jab", PHand.LEAD, PLevel.HEAD, 1, ANY, Bal.N, PShape.STR, PRange.LONG),
        Punch("jab to the body", PHand.LEAD, PLevel.BODY, 1, ANY, Bal.N, PShape.STR, PRange.LONG),
        Punch("cross to the body", PHand.REAR, PLevel.BODY, 2, setOf(Bal.R, Bal.N), Bal.L, PShape.STR, PRange.LONG),
        Punch("cross", PHand.REAR, PLevel.HEAD, 2, setOf(Bal.R, Bal.N), Bal.L, PShape.STR, PRange.INSIDE),
        Punch("{L} hook", PHand.LEAD, PLevel.HEAD, 3, setOf(Bal.L, Bal.N), Bal.R, PShape.HOOK, PRange.INSIDE),
        Punch("{L} hook to the body", PHand.LEAD, PLevel.BODY, 3, setOf(Bal.L, Bal.N), Bal.R, PShape.HOOK, PRange.INSIDE),
        Punch("{R} hook", PHand.REAR, PLevel.HEAD, 4, setOf(Bal.R, Bal.N), Bal.L, PShape.HOOK, PRange.INSIDE),
        Punch("{R} hook to the body", PHand.REAR, PLevel.BODY, 4, setOf(Bal.R, Bal.N), Bal.L, PShape.HOOK, PRange.INSIDE),
        Punch("{L} uppercut", PHand.LEAD, PLevel.HEAD, 5, setOf(Bal.L, Bal.N), Bal.R, PShape.UPP, PRange.INSIDE),
        Punch("{R} uppercut", PHand.REAR, PLevel.HEAD, 6, setOf(Bal.R, Bal.N), Bal.L, PShape.UPP, PRange.INSIDE),
        Punch("{R} uppercut to the body", PHand.REAR, PLevel.BODY, 6, setOf(Bal.R, Bal.N), Bal.L, PShape.UPP, PRange.INSIDE),
    )

    // In-combo weight bridges: slip / roll / duck / pull ONLY. NO step (a step is a range
    // change, not a mid-combo reset — see RE-ENTRY / STEP-USE rules).
    private val TRANS = listOf(
        Trans("slip {L}", Bal.L, "slip"), Trans("slip {R}", Bal.R, "slip"),
        Trans("roll {L}", Bal.L, "roll"), Trans("roll {R}", Bal.R, "roll"),
        Trans("duck", Bal.N, "duck"), Trans("pull back", Bal.R, "pull"),
    )
    // Exits disengage at the end of a combo (distance-creating) — pivots only ever appear here.
    private val EXITS = listOf("step back", "pivot {L}", "pivot {R}", "step out")

    private class Opts(
        val target: Double = 4.0,
        val handOnly: PHand? = null,
        val levelOnly: PLevel? = null,
        val rangeOnly: PRange? = null,   // restrict the body punches to one range
        val exactPunches: Int? = null,
        val insideEntry: Boolean = false,// open on a LONG entry, then work inside
        val startDefensive: Boolean = false,
        val jabCap: Int = 99,            // max consecutive jabs (2 for outRange)
        val powerEnding: Boolean = true, // enforce non-jab ending (terminal jab only as a disengage)
        val allowLinks: Boolean = true,
        val allowExit: Boolean = true,
    )

    data class Combo(val text: String, val actionCount: Int, val score: Double)

    // ---- public builders (RoutineGenerator uses inside + outRange; the rest are kept) ----
    fun inside(target: Double, stance: Stance, rng: Random) =
        build(Opts(target = target, insideEntry = true, rangeOnly = PRange.INSIDE), stance, rng)
    fun outRange(target: Double, stance: Stance, rng: Random) =
        build(Opts(target = target, rangeOnly = PRange.LONG, jabCap = 2, powerEnding = false), stance, rng)
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

    private fun poolFor(opts: Opts): List<Punch> = PUNCHES.filter {
        (opts.handOnly == null || it.hand == opts.handOnly) &&
            (opts.levelOnly == null || it.level == opts.levelOnly) &&
            (opts.rangeOnly == null || it.range == opts.rangeOnly)
    }

    private fun pickPunch(
        pool: List<Punch>, prev: Punch?, levelFree: Boolean, jabRun: Int, jabCap: Int, rng: Random
    ): Punch {
        val out = ArrayList<Pair<Punch, Double>>()
        for (p in pool) {
            if (p.isJab() && jabRun >= jabCap) continue                       // consecutive-jab cap
            if (prev != null && prev.isStraightBody() && p.isStraightBody()) continue  // no double straight-body
            var w = 1.0
            if (prev == null) {
                // open on a straight (jab first, then cross); a hook/uppercut lead-off is rare
                w = if (p.shape == PShape.STR) (if (p.num == 1) 6.0 else 2.5) else 0.15
                if (p.level == PLevel.BODY && levelFree) w *= 0.5
            } else {
                if (p.num == prev.num && p.level == prev.level && p.num != 1) w *= 0.12 // identical repeat: rare
                w *= if (p.hand == prev.hand) {
                    if (p.shape == prev.shape && p.level == prev.level) 0.4 else 0.85     // same hand: prefer variation
                } else 1.2                                                                // alternating slightly favoured
                if (p.level == PLevel.BODY && levelFree) {
                    w *= 0.35                                    // body is an accent in mixed mode
                    if (prev.level == PLevel.BODY) w *= 0.4      // avoid long body chains
                }
            }
            out.add(p to w)
        }
        if (out.isEmpty()) {                                    // all filtered by style guards — relax to the pool
            for (p in pool) if (!(p.isJab() && jabRun >= jabCap)) out.add(p to 1.0)
            if (out.isEmpty()) pool.forEach { out.add(it to 1.0) }
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
                    "duck" -> 0.7
                    "pull" -> 0.4
                    else -> 1.0
                }
                cand.add(Triple(t.say, t.leaves, w))
            }
        }
        if (cand.isEmpty()) return "duck" to Bal.N
        val tot = cand.sumOf { it.third }
        var r = rng.nextDouble(tot)
        for (c in cand) { if (r < c.third) return c.first to c.second; r -= c.third }
        return cand[0].first to cand[0].second
    }

    /** Punch lookup by say-token (used to build fixed entry sequences). */
    private fun punch(say: String) = PUNCHES.first { it.say == say }

    private fun build(opts: Opts, stance: Stance, rng: Random): Combo {
        val acts = ArrayList<Pair<String, Boolean>>()   // (say, isPunch)
        val punchSeq = ArrayList<Punch>()
        var cur = Bal.N
        var prev: Punch? = null
        var jabRun = 0

        fun addPunch(p: Punch) {
            acts.add(p.say to true); cur = p.leaves; prev = p; punchSeq.add(p)
            jabRun = if (p.isJab()) jabRun + 1 else 0
        }
        fun addMove(say: String, leaves: Bal) { acts.add(say to false); cur = leaves; jabRun = 0 }

        // ---- entry ----
        if (opts.startDefensive) {
            val opener = listOf(TRANS[1], TRANS[3], TRANS[0]) // slip {R} / roll {R} / slip {L}
            val o = opener.random(rng); addMove(o.say, o.leaves)
        } else if (opts.insideEntry) {
            // A LONG opener that leads into inside work: jab / jab+hook / feint / feint+hook / cross-to-body.
            when (listOf("jab", "jab_hook", "feint", "feint_hook", "cross_body").random(rng)) {
                "jab" -> addPunch(punch("jab"))
                "cross_body" -> addPunch(punch("cross to the body"))
                "feint" -> addMove("feint", Bal.N)
                "jab_hook" -> {
                    addPunch(punch("jab"))
                    addPunch(if (rng.nextBoolean()) punch("{L} hook") else punch("{R} hook"))
                }
                "feint_hook" -> {
                    addMove("feint", Bal.N)
                    addPunch(if (rng.nextBoolean()) punch("{L} hook") else punch("{R} hook"))
                }
            }
        }

        val pool = poolFor(opts)
        val levelFree = opts.levelOnly == null

        // ---- body ----
        var guard = 0
        while (guard < 40) {
            guard++
            if (opts.exactPunches != null && punchSeq.size >= opts.exactPunches) break
            if (opts.exactPunches == null &&
                punchSeq.size >= 2 && scoreOf(punchSeq, acts.count { !it.second }) >= opts.target) break

            var p = pickPunch(pool, prev, levelFree, jabRun, opts.jabCap, rng)
            if (cur !in p.needs) {
                if (opts.allowLinks) {
                    val (bsay, bl) = bridge(cur, p.needs, prev?.shape, rng); addMove(bsay, bl)
                } else {
                    // no links (sharp 2/3-punch): pick a punch that fits the current weight
                    val fits = pool.filter {
                        cur in it.needs &&
                            !(it.isJab() && jabRun >= opts.jabCap) &&
                            !(prev != null && prev!!.isStraightBody() && it.isStraightBody())
                    }
                    if (fits.isNotEmpty()) p = fits.random(rng)
                }
            }
            addPunch(p)
        }

        // ---- ending ----
        // inside / free must finish on a power shot; a terminal jab is legal ONLY as a
        // disengaging jab, i.e. immediately followed by a backward/pivot exit. outRange
        // may end on a jab (spacing) and only occasionally disengages.
        var exited = false
        val endsOnJab = punchSeq.isNotEmpty() && punchSeq.last().isJab()
        if (opts.powerEnding && opts.exactPunches == null) {
            if (endsOnJab) {
                if (opts.allowExit) { acts.add(EXITS.random(rng) to false); exited = true }
                else {                                   // strip trailing jab(s) back to a power shot
                    while (punchSeq.isNotEmpty() && punchSeq.last().isJab()) {
                        val last = punchSeq.removeAt(punchSeq.size - 1)
                        for (i in acts.indices.reversed()) if (acts[i].second && acts[i].first == last.say) { acts.removeAt(i); break }
                    }
                    if (punchSeq.isEmpty()) addPunch(punch("cross"))
                }
            } else if (opts.allowExit && punchSeq.size >= 3 && rng.nextDouble() < 0.45) {
                acts.add(EXITS.random(rng) to false); exited = true
            }
        }
        if (!exited && !opts.powerEnding && opts.exactPunches == null &&
            opts.allowExit && punchSeq.size >= 3 && rng.nextDouble() < 0.35) {
            acts.add(EXITS.random(rng) to false)   // outRange occasional disengage
        }

        val text = acts.joinToString(", ") { ComboLibrary.render(it.first, stance) }
            .replaceFirstChar { it.uppercase() }
        return Combo(text, acts.size, scoreOf(punchSeq, acts.count { !it.second }))
    }
}
