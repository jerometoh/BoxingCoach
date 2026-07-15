package com.jerome.boxingcoach

enum class Difficulty { BEGINNER, INTERMEDIATE, ADVANCED }
enum class Intensity { LOW, MEDIUM, HIGH }
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
    val difficulty: Difficulty = Difficulty.INTERMEDIATE,
    val intensity: Intensity = Intensity.MEDIUM,
)

/** A single spoken/displayed instruction at an offset (seconds) into its round.
 *  isIntro = the pre-round explanation (combo assignment), shown/spoken once at t=0.
 *  isCommand = a short live trigger word ("Go", "Down", "Feint"...) rendered large. */
data class Cue(val offsetSec: Int, val text: String, val isIntro: Boolean = false, val isCommand: Boolean = false)

data class Round(
    val label: String,             // e.g. "Shadow boxing — Round 2 of 3"
    val durationSec: Int,
    val cues: List<Cue>,
    val summary: String,           // short description shown in review screen
    val isRest: Boolean = false,
    val legend: String = "",       // trigger-word mapping shown on the workout screen, e.g. "Go → jab, cross · Down → two squats"
)

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
    val totalSec: Int get() = sections.sumOf { s -> s.rounds.sumOf { it.durationSec } }
}

data class HistoryEntry(
    val completedAt: Long,
    val durationSec: Int,
    val difficulty: Difficulty,
    val intensity: Intensity,
    val summary: String,
)

data class AppSettings(
    val voiceMode: VoiceMode = VoiceMode.DUCK_MUSIC,
    val stance: Stance = Stance.ORTHODOX,
    val restCoaching: Boolean = true,     // spoken tips during rest vs tone only
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
