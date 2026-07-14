package com.jerome.boxingcoach

/**
 * Curated library of realistic boxing combos and cues.
 *
 * Combos are written in LEAD/REAR terminology internally, then rendered to
 * left/right based on the user's stance (orthodox: lead=left; southpaw: lead=right).
 * Numbers follow standard coaching convention: 1 jab, 2 cross, 3 lead hook,
 * 4 rear hook, 5 lead uppercut, 6 rear uppercut. "b" suffix = to the body.
 */
object ComboLibrary {

    data class Combo(
        val punches: Int,        // punch count
        val tier: Int,           // 1 = simplest … 4 = most complex
        val text: String,        // lead/rear phrasing, {L}/{R} tokens for side words
    )

    // ---- Fixed, named combos (tiered) ----
    val combos = listOf(
        // Tier 1 — singles and basics
        Combo(1, 1, "Jab"),
        Combo(1, 1, "Double jab"),
        Combo(1, 1, "Jab to the body"),
        Combo(1, 1, "Cross"),
        // Tier 2 — classic two-punch
        Combo(2, 2, "One-two"),
        Combo(2, 2, "Jab, cross"),
        Combo(2, 2, "Jab to the body, cross to the head"),
        Combo(2, 2, "Double jab, cross"),
        Combo(2, 2, "Cross, {L} hook"),
        Combo(2, 2, "{L} hook to the body, {L} hook to the head"),
        // Tier 3 — three-punch staples
        Combo(3, 3, "Jab, cross, {L} hook"),
        Combo(3, 3, "One-two, {L} hook to the body"),
        Combo(3, 3, "Jab, {R} uppercut, {L} hook"),
        Combo(3, 3, "Jab, cross, {R} hook to the body"),
        Combo(3, 3, "Cross, {L} hook, cross"),
        Combo(3, 3, "{L} uppercut, cross, {L} hook"),
        Combo(3, 3, "Jab to the body, {L} hook to the head, cross"),
        // Tier 4 — four-punch and layered
        Combo(4, 4, "Jab, cross, {L} hook, cross"),
        Combo(4, 4, "One-two, {L} hook to the body, {L} hook to the head"),
        Combo(4, 4, "Double jab, cross, {L} hook"),
        Combo(4, 4, "Jab, cross, {L} uppercut, cross"),
        Combo(4, 4, "Cross, {L} hook, {R} uppercut, {L} hook"),
        Combo(4, 4, "Jab, {R} hook to the body, {L} hook to the head, cross"),
    )

    // ---- Open-ended instructions ----
    val openCombos = listOf(
        "Any two-punch combo" to 2,
        "Any three-punch combo" to 3,
        "Any four-punch combo" to 4,
        "Any two body shots" to 2,
        "Any combo ending with a {L} hook" to 3,
        "Any combo ending to the body" to 3,
        "Freestyle — mix it up" to 3,
    )

    // ---- Defensive movement / footwork cues (appended or standalone) ----
    val movements = listOf(
        "Slip {L}", "Slip {R}", "Roll under", "Step back",
        "Pivot {L}", "Pivot {R}", "Angle out {L}", "Angle out {R}",
        "Double up the feint, then go", "Feint the jab, step in",
        "Circle {L}", "Circle {R}",
    )

    // ---- Combo + movement pairings (higher tiers) ----
    val comboWithMovement = listOf(
        "One-two, step back, cross",
        "Jab, slip {R}, cross",
        "One-two, roll under, {L} hook",
        "Jab, cross, pivot {L}",
        "{L} hook, roll under, {L} hook, cross",
        "Jab, step back, double jab, cross",
        "Slip {L}, {L} hook to the body, {L} hook to the head",
        "Feint, one-two, angle out {R}",
    )

    // ---- Conditioning moves (mixed in at high intensity) ----
    val conditioning = listOf(
        "Burpee, then one-two",
        "Sprawl, then any three-punch combo",
        "Ten straight punches, fast",
        "Squat, then cross, {L} hook",
        "Four sprawls, then freestyle until the bell",
        "Twenty-second nonstop punch-out — go",
    )

    // ---- Conditioning moves assigned per-round (announced in the intro; triggered by the round's down-word) ----
    val downMoves = listOf(
        "two squats",
        "four mountain climbers",
        "one burpee",
        "a squat hold until I call you back up",
        "five fast punches from the floor",
        "one sprawl",
    )

    // ---- Spacing/filler cues used between "Go" commands — short, not full combos ----
    val spacingCues = listOf(
        "Feint",
        "Jab, keep range",
        "Circle {L}",
        "Circle {R}",
        "Reset your guard",
        "Slip and reset",
        "Bounce, stay loose",
    )

    // ---- Single-focus round themes ----
    val focusThemes = listOf(
        "This whole round: {L} hooks to the body only. Work angles between shots.",
        "This whole round: jab only. Vary speed, level and rhythm.",
        "This whole round: body shots only. Bend the knees.",
        "This whole round: one-two only. Perfect form every rep.",
        "This whole round: counter work — slip or roll before every combo.",
        "This whole round: southpaw practice — switch your stance.",
    )

    // ---- Warm-up ----
    val warmupMoves = listOf(
        "Neck circles, both directions",
        "Arm circles, small to large",
        "Shoulder rolls, forward and back",
        "Torso twists, loose and easy",
        "Hip circles, both directions",
        "Leg swings, front to back, each side",
        "Light bounce on the toes — find your rhythm",
        "Wrist rolls and open-close fists",
        "Lunge with a twist, alternating sides",
        "High knees, twenty seconds, easy pace",
    )

    // ---- Core exercises ----
    val coreMoves = listOf(
        "Plank hold",
        "Bicycle crunches",
        "Russian twists",
        "Leg raises",
        "Mountain climbers",
        "Side plank, {L} side",
        "Side plank, {R} side",
        "Sit-up with a cross at the top",
        "Hollow body hold",
        "Flutter kicks",
    )

    // ---- Cool-down ----
    val cooldownMoves = listOf(
        "Deep breaths — in through the nose, out through the mouth",
        "Cross-body shoulder stretch, hold each side",
        "Overhead triceps stretch, both sides",
        "Standing quad stretch, each leg",
        "Standing hamstring fold, hang loose",
        "Chest opener against a wall or doorframe",
        "Neck side stretch, gentle, both sides",
        "Child's pose or deep squat hold — slow the breathing right down",
    )

    // ---- Rest-period coaching lines ----
    val restTips = listOf(
        "Breathe. In through the nose, out through the mouth.",
        "Hands up when you're tired — that's when it counts.",
        "Shake the arms out. Stay loose.",
        "Sip water if you need it.",
        "Next round, sit down on your punches.",
        "Snap the jab back as fast as it goes out.",
        "Exhale sharply on every punch.",
        "Stay on the balls of your feet.",
        "Turn the hips — power comes from the ground up.",
        "Keep the chin tucked behind the shoulder.",
    )

    /** Render {L}/{R} tokens to left/right for the given stance. */
    fun render(text: String, stance: Stance): String {
        val lead = if (stance == Stance.ORTHODOX) "left" else "right"
        val rear = if (stance == Stance.ORTHODOX) "right" else "left"
        return text.replace("{L}", lead).replace("{R}", rear)
    }
}
