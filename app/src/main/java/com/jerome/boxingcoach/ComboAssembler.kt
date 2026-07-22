package com.jerome.boxingcoach

import kotlin.random.Random

/**
 * Generative, weight-aware, RANGE-aware combo assembler.
 *
 * WEIGHT: every action needs a balance state to be thrown well and leaves the weight
 * somewhere afterwards. The assembler only appends a punch the current weight allows;
 * when the next punch doesn't fit it bridges with an IN-RANGE reset (slip / roll / duck /
 * pull) whose result supplies the needed weight — slips answer straights, rolls answer
 * hooks, never redundant. Steps are NOT bridges: "step in" is a range change owned by the
 * caller (RANGE mode); steps/pivots only appear as terminal EXITS or as fragment joins.
 *
 * RANGE: LONG (distance) = jab, jab body, cross-to-BODY, feint. INSIDE (mid+close) = cross
 * to the head, lead/rear hooks (head & body), lead/rear uppercuts. Builders: outRange
 * (LONG, ≤2 jabs, may end on a jab), inside (LONG entry → work inside → power finish →
 * optional exit), free (loose, power finish).
 *
 * Four refinements layered on top of the weight model:
 *  1. CLASSICS AS ATTRACTORS — canonical numbered combos (1-2, 1-2-3, 2-3-2, 1-6-3-2 …) are
 *     up-weighted so output leans toward combos boxers actually drill; legal-but-odd still
 *     appears, just less often.
 *  2. SAME-HAND DOUBLES — a same-hand pair is thrown DIRECTLY (no reset) when it's varied by
 *     level or shape (rear hook body → rear hook head, lead hook → lead uppercut); the pair
 *     re-loads within itself. Identical-twice is banned except the jab; a reset between
 *     always legitimises a repeat.
 *  3. COMPOUND ATOMS — a defensive move and the punch its momentum produces are ONE unit:
 *     "roll {L} into the {L} uppercut", plus disengaging "step back, jab" / "pivot, jab".
 *     Advanced; gated by the caller.
 *  4. FRAGMENTS & FUSION — fragment() builds a short inside piece that ends on a re-angle
 *     step so it chains cleanly; fuse() concatenates fragments with weight-legal joins so a
 *     later round can be built from earlier ones (the ladder).
 *
 * Weight is stored in lead/rear terms and rendered to left/right by stance, so everything
 * mirrors correctly for southpaw.
 */
object ComboAssembler {

    internal enum class Bal { L, N, R }
    internal enum class PHand { LEAD, REAR }
    internal enum class PLevel { HEAD, BODY }
    internal enum class PShape { STR, HOOK, UPP }
    internal enum class PRange { LONG, INSIDE }

    internal class Punch(
        val say: String, val hand: PHand, val level: PLevel, val num: Int,
        val needs: Set<Bal>, val leaves: Bal, val shape: PShape, val range: PRange,
    )
    private class Trans(val say: String, val leaves: Bal, val family: String)

    /** One assembled action. isThrow = punch or compound; isCompound distinguishes the bundled unit. */
    internal class SA(val say: String, val isThrow: Boolean, val isCompound: Boolean)

    private fun Punch.isStraightBody() = shape == PShape.STR && level == PLevel.BODY
    private fun Punch.isJab() = shape == PShape.STR && hand == PHand.LEAD

    private val ANY = setOf(Bal.L, Bal.N, Bal.R)

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
    private val SAY_TO_PUNCH = PUNCHES.associateBy { it.say }

    // In-combo weight bridges: slip / roll / duck / pull ONLY. NO step.
    private val TRANS = listOf(
        Trans("slip {L}", Bal.L, "slip"), Trans("slip {R}", Bal.R, "slip"),
        Trans("roll {L}", Bal.L, "roll"), Trans("roll {R}", Bal.R, "roll"),
        Trans("duck", Bal.N, "duck"), Trans("pull back", Bal.R, "pull"),
    )
    private val TRANS_LEAVES = TRANS.associate { it.say to it.leaves }
    private val EXITS = listOf("step back", "pivot {L}", "pivot {R}", "step out")
    private val REANGLE_STEPS = listOf("step back", "step out")           // leave NEUTRAL; used to join fragments

    // #3 compound body atoms: (say, needs, leaves) — one unit, the move IS the punch's set-up.
    private val COMPOUNDS = listOf(
        Triple("roll {L} into the {L} uppercut", ANY, Bal.R),
        Triple("roll {R} into the {R} uppercut", ANY, Bal.L),
    )
    private val COMPOUND_SAYS = COMPOUNDS.map { it.first }.toSet()
    private val COMPOUND_LEAVES = COMPOUNDS.associate { it.first to it.third }
    // #3 disengaging compound exits (terminal): defensive angle + a parting jab.
    private val COMPOUND_EXITS = listOf("step back, jab", "pivot {L}, jab", "pivot {R}, jab")

    // #1 canonical numbered combos (by punch number) used as soft attractors.
    private val CLASSICS = listOf(
        listOf(1, 2), listOf(1, 1, 2), listOf(1, 2, 3), listOf(1, 2, 3, 2),
        listOf(2, 3, 2), listOf(3, 2, 3), listOf(1, 6, 3, 2), listOf(1, 2, 5, 2),
        listOf(1, 3, 2), listOf(1, 1, 3, 2),
    )

    private class Opts(
        val target: Double = 4.0,
        val handOnly: PHand? = null,
        val levelOnly: PLevel? = null,
        val rangeOnly: PRange? = null,
        val exactPunches: Int? = null,
        val maxPunches: Int? = null,       // #1 hard cap on throws (fragments)
        val insideEntry: Boolean = false,
        val startDefensive: Boolean = false,
        val jabCap: Int = 99,
        val powerEnding: Boolean = true,
        val allowLinks: Boolean = true,
        val allowExit: Boolean = true,
        val attract: Boolean = true,       // #1
        val compounds: Boolean = false,    // #3
        val fragmentEnding: Boolean = false, // #4: end on a re-angle step for clean joins
    )

    data class Combo(val text: String, val actionCount: Int, val score: Double)

    /** A reusable inside piece with a clean (re-angle) tail, carried between rounds for the ladder. */
    class Fragment internal constructor(
        internal val acts: List<SA>,
        val text: String, val actionCount: Int, val score: Double,
    )

    private class BuildRes(val acts: List<SA>, val score: Double)

    // ---- public builders ----
    fun inside(target: Double, stance: Stance, rng: Random, compounds: Boolean = false): Combo =
        combo(assemble(Opts(target = target, insideEntry = true, rangeOnly = PRange.INSIDE, compounds = compounds), rng), stance)
    fun outRange(target: Double, stance: Stance, rng: Random): Combo =
        combo(assemble(Opts(target = target, rangeOnly = PRange.LONG, jabCap = 2, powerEnding = false, attract = false), rng), stance)
    fun free(target: Double, stance: Stance, rng: Random, compounds: Boolean = false): Combo =
        combo(assemble(Opts(target = target, compounds = compounds), rng), stance)
    fun leadOnly(target: Double, stance: Stance, rng: Random): Combo =
        combo(assemble(Opts(target = target, handOnly = PHand.LEAD), rng), stance)
    fun bodyOnly(target: Double, stance: Stance, rng: Random): Combo =
        combo(assemble(Opts(target = target, levelOnly = PLevel.BODY), rng), stance)
    fun counters(target: Double, stance: Stance, rng: Random): Combo =
        combo(assemble(Opts(target = target, startDefensive = true), rng), stance)
    fun exact(nPunches: Int, stance: Stance, rng: Random): Combo =
        combo(assemble(Opts(exactPunches = nPunches, allowLinks = false, allowExit = false), rng), stance)

    /** #4 A short inside fragment that ends on a re-angle step so it chains into the next. */
    fun fragment(target: Double, stance: Stance, rng: Random, compounds: Boolean = false): Fragment {
        val r = assemble(Opts(target = target, insideEntry = true, rangeOnly = PRange.INSIDE, compounds = compounds, fragmentEnding = true, maxPunches = 5), rng)
        return Fragment(r.acts, render(r.acts, stance), r.acts.size, r.score)
    }

    /** #4 Concatenate fragments into one combo, inserting a weight-legal bridge at each join.
     *  Intermediate re-angle steps become the joins; the final fragment's trailing step is
     *  dropped so the fused combo lands on a power shot. */
    fun fuse(frags: List<Fragment>, stance: Stance, rng: Random): Combo {
        if (frags.isEmpty()) return Combo("", 0, 0.0)
        val maxActions = 15                                   // #1 cap the fused combo length
        val out = ArrayList<SA>()
        var cur = Bal.N
        for (fr in frags) {
            val acts = fr.acts
            // stop before we blow the cap (+1 for a possible join bridge); always include the first
            if (out.isNotEmpty() && out.size + acts.size + 1 > maxActions) break
            if (out.isNotEmpty()) {                           // join: bridge if the weight can't start this fragment
                val need = firstNeed(acts)
                if (cur !in need) { val (bsay, bl) = bridge(cur, need, null, rng); out.add(SA(bsay, false, false)); cur = bl }
            }
            for (a in acts) { out.add(a); cur = weightAfter(a, cur) }
        }
        // ending: drop the last fragment's trailing re-angle so it lands on power; no bare-jab finish
        if (out.isNotEmpty() && !out.last().isThrow && out.last().say in REANGLE_STEPS) out.removeAt(out.size - 1)
        val lastThrow = out.lastOrNull { it.isThrow }
        if (lastThrow != null && !lastThrow.isCompound && SAY_TO_PUNCH[lastThrow.say]?.isJab() == true)
            out.add(SA(EXITS[1 + rng.nextInt(2)], false, false))
        val score = out.count { it.isThrow } + 1.5 * out.count { !it.isThrow }
        return Combo(render(out, stance), out.size, score)
    }

    // ---- rendering ----
    private fun render(acts: List<SA>, stance: Stance): String =
        acts.joinToString(", ") { ComboLibrary.render(it.say, stance) }.replaceFirstChar { it.uppercase() }

    private fun combo(r: BuildRes, stance: Stance) = Combo(render(r.acts, stance), r.acts.size, r.score)

    // ---- weight helpers (also drive fusion) ----
    private fun weightAfter(a: SA, cur: Bal): Bal = when {
        a.isCompound && a.say in COMPOUND_LEAVES -> COMPOUND_LEAVES[a.say]!!
        a.isCompound -> Bal.N                                  // compound exits (step/pivot, jab) end neutral
        a.isThrow -> SAY_TO_PUNCH[a.say]?.leaves ?: Bal.N
        a.say in TRANS_LEAVES -> TRANS_LEAVES[a.say]!!
        else -> Bal.N                                          // feint / step / pivot
    }
    private fun firstNeed(acts: List<SA>): Set<Bal> {
        val f = acts.firstOrNull { it.isThrow } ?: return ANY
        if (f.isCompound) return ANY
        return SAY_TO_PUNCH[f.say]?.needs ?: ANY
    }

    // ---- core ----
    private fun scoreOf(punches: List<Punch>, defMove: Int, compoundCount: Int): Double {
        var s = punches.size + 1.5 * defMove + 1.5 * compoundCount
        for (i in 1 until punches.size) {
            if (punches[i].level != punches[i - 1].level) s += 1.0
            if (punches[i].hand == punches[i - 1].hand) s += 0.5
        }
        return s
    }

    private fun poolFor(opts: Opts): List<Punch> = PUNCHES.filter {
        (opts.handOnly == null || it.hand == opts.handOnly) &&
            (opts.levelOnly == null || it.level == opts.levelOnly) &&
            (opts.rangeOnly == null || it.range == opts.rangeOnly)
    }

    private fun sameHandVaried(prev: Punch?, p: Punch) =
        prev != null && p.hand == prev.hand &&
            prev.shape != PShape.STR && p.shape != PShape.STR &&   // #3 hooks/uppercuts only — never two straights
            (p.level != prev.level || p.shape != prev.shape)

    private fun pickPunch(
        pool: List<Punch>, prev: Punch?, levelFree: Boolean, jabRun: Int, jabCap: Int,
        seqNums: List<Int>, attract: Boolean, rng: Random,
    ): Punch {
        val out = ArrayList<Pair<Punch, Double>>()
        for (p in pool) {
            if (p.isJab() && jabRun >= jabCap) continue
            if (prev != null && prev.isStraightBody() && p.isStraightBody()) continue
            // #2 ban a consecutive IDENTICAL punch (same number & level) unless it's the jab
            if (prev != null && p.num == prev.num && p.level == prev.level && !p.isJab()) continue
            var w = 1.0
            if (prev == null) {
                w = if (p.shape == PShape.STR) (if (p.num == 1) 6.0 else 2.5) else 0.15
                if (p.level == PLevel.BODY && levelFree) w *= 0.5
            } else {
                w *= when {
                    sameHandVaried(prev, p) -> 0.9    // #2/#3 varied same-hand (hook/upp): idiomatic, thrown direct
                    p.hand == prev.hand -> 0.4
                    else -> 1.2
                }
                if (p.level == PLevel.BODY && levelFree) {
                    w *= 0.35
                    if (prev.level == PLevel.BODY) w *= 0.4
                }
            }
            if (attract) {                                     // #1 boost a canonical continuation
                val n = seqNums.size
                if (CLASSICS.any { it.size > n && it.subList(0, n) == seqNums && it[n] == p.num }) w *= 2.6
            }
            out.add(p to w)
        }
        if (out.isEmpty()) {
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
                    "roll" -> if (prevShape == PShape.HOOK) 2.5 else 0.5
                    "slip" -> if (prevShape == PShape.STR || prevShape == PShape.UPP) 2.5 else 0.6
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

    private fun punch(say: String) = SAY_TO_PUNCH.getValue(say)

    private fun assemble(opts: Opts, rng: Random): BuildRes {
        val acts = ArrayList<SA>()
        val punchSeq = ArrayList<Punch>()
        val seqNums = ArrayList<Int>()
        var cur = Bal.N
        var prev: Punch? = null
        var jabRun = 0
        var compoundCount = 0

        fun addPunch(p: Punch) {
            acts.add(SA(p.say, isThrow = true, isCompound = false))
            cur = p.leaves; prev = p; punchSeq.add(p); seqNums.add(p.num)
            jabRun = if (p.isJab()) jabRun + 1 else 0
        }
        fun addMove(say: String, leaves: Bal) { acts.add(SA(say, false, false)); cur = leaves; jabRun = 0 }
        fun addCompound(c: Triple<String, Set<Bal>, Bal>) {
            acts.add(SA(c.first, isThrow = true, isCompound = true))
            cur = c.third; prev = null; jabRun = 0; compoundCount++; seqNums.add(0)
        }

        // ---- entry ----
        if (opts.startDefensive) {
            val opener = listOf(TRANS[1], TRANS[3], TRANS[0]); val o = opener.random(rng); addMove(o.say, o.leaves)
        } else if (opts.insideEntry) {
            when (listOf("jab", "jab_hook", "feint", "feint_hook", "cross_body").random(rng)) {
                "jab" -> addPunch(punch("jab"))
                "cross_body" -> addPunch(punch("cross to the body"))
                "feint" -> addMove("feint", Bal.N)
                "jab_hook" -> { addPunch(punch("jab")); addPunch(if (rng.nextBoolean()) punch("{L} hook") else punch("{R} hook")) }
                "feint_hook" -> { addMove("feint", Bal.N); addPunch(if (rng.nextBoolean()) punch("{L} hook") else punch("{R} hook")) }
            }
        }

        val pool = poolFor(opts)
        val levelFree = opts.levelOnly == null
        val insideish = opts.rangeOnly == PRange.INSIDE || opts.rangeOnly == null

        // ---- body ----
        var guard = 0
        while (guard < 40) {
            guard++
            val effLen = punchSeq.size + compoundCount
            if (opts.exactPunches != null && effLen >= opts.exactPunches) break
            if (opts.maxPunches != null && effLen >= opts.maxPunches) break   // #1 fragment length cap
            if (opts.exactPunches == null && effLen >= 2 &&
                scoreOf(punchSeq, acts.count { !it.isThrow }, compoundCount) >= opts.target) break

            // #3 occasionally throw a compound (advanced; inside/free only; never two in a row)
            if (opts.compounds && insideish && effLen >= 1 && acts.lastOrNull()?.isCompound != true &&
                rng.nextDouble() < 0.18) {
                addCompound(COMPOUNDS.random(rng)); continue
            }

            var p = pickPunch(pool, prev, levelFree, jabRun, opts.jabCap, seqNums, opts.attract, rng)
            if (cur !in p.needs) {
                if (sameHandVaried(prev, p)) {
                    // #2 sanctioned same-hand varied double — re-loads within the pair, no bridge
                } else if (opts.allowLinks) {
                    val (bsay, bl) = bridge(cur, p.needs, prev?.shape, rng); addMove(bsay, bl)
                } else {
                    val fits = pool.filter {
                        cur in it.needs && !(it.isJab() && jabRun >= opts.jabCap) &&
                            !(prev != null && prev!!.isStraightBody() && it.isStraightBody())
                    }
                    if (fits.isNotEmpty()) p = fits.random(rng)
                }
            }
            addPunch(p)
        }

        // ---- ending ----
        val lastThrow = acts.lastOrNull { it.isThrow }
        val endsOnJab = lastThrow != null && !lastThrow.isCompound && (SAY_TO_PUNCH[lastThrow.say]?.isJab() == true)
        if (opts.fragmentEnding) {
            addMove(REANGLE_STEPS.random(rng), Bal.N)          // #4 clean join tail
        } else if (opts.powerEnding && opts.exactPunches == null) {
            if (endsOnJab) {
                if (opts.allowExit) {
                    if (opts.compounds && rng.nextDouble() < 0.5) acts.add(SA(COMPOUND_EXITS.random(rng), false, false))
                    else acts.add(SA(EXITS.random(rng), false, false))
                } else {
                    while (punchSeq.isNotEmpty() && punchSeq.last().isJab()) {
                        val last = punchSeq.removeAt(punchSeq.size - 1)
                        for (i in acts.indices.reversed()) if (acts[i].isThrow && acts[i].say == last.say) { acts.removeAt(i); break }
                    }
                    if (punchSeq.isEmpty()) addPunch(punch("cross"))
                }
            } else if (opts.allowExit && punchSeq.size >= 3 && rng.nextDouble() < 0.45) {
                if (opts.compounds && rng.nextDouble() < 0.35) acts.add(SA(COMPOUND_EXITS.random(rng), false, false))
                else acts.add(SA(EXITS.random(rng), false, false))
            }
        } else if (!opts.powerEnding && opts.exactPunches == null &&
            opts.allowExit && punchSeq.size >= 3 && rng.nextDouble() < 0.35) {
            acts.add(SA(EXITS.random(rng), false, false))      // outRange occasional disengage
        }

        val score = scoreOf(punchSeq, acts.count { !it.isThrow }, compoundCount)
        return BuildRes(acts, score)
    }
}
