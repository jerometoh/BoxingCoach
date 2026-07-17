package com.jerome.boxingcoach

enum class Stance { ORTHODOX, SOUTHPAW }
enum class VoiceMode { DUCK_MUSIC, OVERLAY, TEXT_ONLY }
enum class SectionType { WARMUP, SHADOW, BAG, CORE, COOLDOWN }

data class RoutineParams(
    val includeWarmup: Boolean = true,
    val includeShadow: Boolean = true,
    val includeBag: Boolean = true,
    val includeCore: Boolean = true,
    val includeCooldown: Boolean = true,
    val shadowRounds: Int = 3,
    val shadowRoundSec: Int = 120,
    val bagRounds: Int = 6,
    val bagRoundSec: Int = 180,
    val coreSec: Int = 300,
    val restSec: Int = 60,
    val restBetweenSectionsSec: Int = 120,
    // Two independent 1–10 scales (replaced the old 3-option Difficulty/Intensity
    // enums). complexity = how intricate combos & movements get; intensity = cardio
    // load & pace. Old Beginner/Intermediate/Advanced map to roughly 2 / 5 / 8.
    val complexity: Int = 5,
    val intensity: Int = 5,
)

/** A single spoken/displayed instruction at an offset (seconds) into its round.
 *  isIntro = the pre-round explanation (combo assignment), shown/spoken once at t=0.
 *  isCommand = a short live trigger word ("Go", "Down", "Feint"...) rendered large. */
/** comboIndex: for multi-combo rounds, 1 or 2 identifies which assigned combo this
 *  "go one / go two" command calls for — the workout screen renders it as a big
 *  ONE! / TWO!. 0 for everything else (single-combo "go", "down", spacing, tips). */
data class Cue(
    val offsetSec: Int,
    val text: String,
    val isIntro: Boolean = false,
    val isCommand: Boolean = false,
    val comboIndex: Int = 0,
)

/** One step in a guided section (warm-up / core / cool-down). The engine speaks
 *  [announce], WAITS for speech to finish, then either counts [reps] at [secPerCount]
 *  cadence (doubled with a "Switch" if [perSide]) or holds for [holdSec] with a
 *  spoken countdown. Because each step waits for its own speech, counts never stack
 *  behind the announcement. Guided rounds have no fixed duration — length is emergent. */
data class GuidedStep(
    val announce: String,
    val name: String = "",     // clean exercise name shown on screen; announce is voice-only
    val reps: Int = 0,
    val secPerCount: Int = 2,
    val perSide: Boolean = false,
    val holdSec: Int = 0,
)

data class Round(
    val label: String,             // e.g. "Shadow boxing — Round 2 of 3"
    val durationSec: Int,
    val cues: List<Cue>,
    val summary: String,           // short description shown in review screen
    val isRest: Boolean = false,
    val hasFinisher: Boolean = false, // round ends on a forced-intensity burst that owns the last ~10-14s (engine stays quiet then)
    val legend: String = "",       // trigger-word mapping shown on the workout screen, e.g. "Go → jab, cross · Down → two squats"
    val combos: List<String> = emptyList(), // this round's assigned combo(s), indexed by Cue.comboIndex (1-based)
    val guidedSteps: List<GuidedStep>? = null,  // non-null => guided section (warm-up/core/cool-down)
    val exerciseNames: List<String> = emptyList(), // names shown in the review screen for guided sections
) {
    val isGuided: Boolean get() = guidedSteps != null
}

data class Section(
    val type: SectionType,
    val title: String,
    val rounds: List<Round>,
)

data class Routine(
    val createdAt: Long = System.currentTimeMillis(),
    val params: RoutineParams = RoutineParams(),
    val sections: List<Section> = emptyList(),
) {
    val totalSec: Int get() = sections.sumOf { s -> s.rounds.sumOf { r ->
        if (r.isGuided) r.guidedSteps!!.sumOf { st ->
            8 + when { // ~8s to announce each step, plus its work
                st.reps > 0 -> st.reps * st.secPerCount * (if (st.perSide) 2 else 1)
                st.holdSec > 0 -> st.holdSec * (if (st.perSide) 2 else 1)
                else -> 0
            }
        } else r.durationSec
    } }
}

data class HistoryEntry(
    val completedAt: Long,
    val durationSec: Int,
    val complexity: Int,
    val intensity: Int,
    val summary: String,
)

data class AppSettings(
    val voiceMode: VoiceMode = VoiceMode.DUCK_MUSIC,
    val stance: Stance = Stance.ORTHODOX,
    val restCoaching: Boolean = true,     // in-round coaching tips (spoken between combos)
    val countReps: Boolean = true,        // count warm-up reps aloud ("1, 2, 3…") vs announce only
    val voiceCommands: Boolean = false,   // experimental hands-free control
    val keepScreenOn: Boolean = true,     // prevent screen dimming/locking during workout
    val warnSound: Boolean = true,        // clapper sound at 10 seconds remaining
    val endBell: Boolean = true,          // ring bell at round changes
    val voiceName: String = "",           // TTS voice identifier; empty = auto-pick best
    val tryEmbeddedVoice: Boolean = false, // opt-in: attempt the bundled neural voice (experimental — can crash on some devices)
    val elevenApiKey: String = "",         // ElevenLabs API key (empty = use system TTS)
    val elevenVoiceId: String = "",        // ElevenLabs voice id (empty = app default)
    val elevenEnabled: Boolean = true,     // master switch: use ElevenLabs when a key is present
)
