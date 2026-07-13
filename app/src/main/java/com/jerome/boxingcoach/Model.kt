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
    val difficulty: Difficulty = Difficulty.INTERMEDIATE,
    val intensity: Intensity = Intensity.MEDIUM,
)

/** A single spoken/displayed instruction at an offset (seconds) into its round. */
data class Cue(val offsetSec: Int, val text: String)

data class Round(
    val label: String,             // e.g. "Shadow boxing — Round 2 of 3"
    val durationSec: Int,
    val cues: List<Cue>,
    val summary: String,           // short description shown in review screen
    val isRest: Boolean = false,
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
    val voiceCommands: Boolean = false,   // experimental hands-free control
)
