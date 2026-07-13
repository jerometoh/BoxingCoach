package com.jerome.boxingcoach

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { SETUP, REVIEW, WORKOUT, HISTORY, SETTINGS }

class MainActivity : ComponentActivity() {

    private lateinit var settingsStore: SettingsStore
    private lateinit var historyStore: HistoryStore
    private var voiceCommands: VoiceCommands? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        historyStore = HistoryStore(this)
        if (WorkoutEngine.tts == null) WorkoutEngine.tts = TtsManager(applicationContext)

        // First-launch permissions
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        wanted += Manifest.permission.RECORD_AUDIO
        permissionLauncher.launch(wanted.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App()
            }
        }
    }

    @Composable
    fun App() {
        var screen by remember { mutableStateOf(Screen.SETUP) }
        var settings by remember { mutableStateOf(settingsStore.load()) }
        var params by remember { mutableStateOf(settingsStore.loadParams()) }
        var routine by remember { mutableStateOf<Routine?>(null) }
        val workoutState by WorkoutEngine.state.collectAsStateWithLifecycle()

        // Keep engine config in sync
        LaunchedEffect(settings) {
            WorkoutEngine.tts?.voiceMode = settings.voiceMode
            WorkoutEngine.restCoaching = settings.restCoaching
        }
        // Log history when a workout finishes
        LaunchedEffect(workoutState.phase) {
            if (workoutState.phase == WorkoutPhase.FINISHED) {
                routine?.let {
                    historyStore.add(
                        HistoryEntry(
                            completedAt = System.currentTimeMillis(),
                            durationSec = workoutState.elapsedTotal,
                            difficulty = it.params.difficulty,
                            intensity = it.params.intensity,
                            summary = it.sections.joinToString(" · ") { s -> s.title },
                        )
                    )
                }
                WorkoutService.stop(this@MainActivity)
                voiceCommands?.stop()
            }
        }

        Scaffold(
            bottomBar = {
                if (screen != Screen.WORKOUT) NavigationBar {
                    NavigationBarItem(screen == Screen.SETUP, { screen = Screen.SETUP },
                        icon = {}, label = { Text("Train") })
                    NavigationBarItem(screen == Screen.HISTORY, { screen = Screen.HISTORY },
                        icon = {}, label = { Text("History") })
                    NavigationBarItem(screen == Screen.SETTINGS, { screen = Screen.SETTINGS },
                        icon = {}, label = { Text("Settings") })
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (screen) {
                    Screen.SETUP -> SetupScreen(params,
                        onParams = { params = it; settingsStore.saveParams(it) },
                        onGenerate = {
                            routine = RoutineGenerator.generate(params, settings.stance)
                            screen = Screen.REVIEW
                        })
                    Screen.REVIEW -> routine?.let { r ->
                        ReviewScreen(r,
                            onRegenAll = { routine = RoutineGenerator.generate(params, settings.stance) },
                            onRegenSection = { si -> routine = RoutineGenerator.regenerateSection(r, si, settings.stance) },
                            onRegenRound = { si, ri -> routine = RoutineGenerator.regenerateRound(r, si, ri, settings.stance) },
                            onBack = { screen = Screen.SETUP },
                            onStart = {
                                WorkoutService.start(this@MainActivity)
                                WorkoutEngine.start(r)
                                if (settings.voiceCommands) {
                                    voiceCommands = VoiceCommands(this@MainActivity).also { it.start() }
                                }
                                screen = Screen.WORKOUT
                            })
                    }
                    Screen.WORKOUT -> WorkoutScreen(workoutState,
                        onPause = { WorkoutEngine.pause() },
                        onResume = { WorkoutEngine.resume() },
                        onSkip = { WorkoutEngine.skip() },
                        onEnd = {
                            WorkoutEngine.stop()
                            WorkoutService.stop(this@MainActivity)
                            voiceCommands?.stop()
                            screen = Screen.SETUP
                        },
                        onDone = { screen = Screen.SETUP })
                    Screen.HISTORY -> HistoryScreen(historyStore.load())
                    Screen.SETTINGS -> SettingsScreen(settings) {
                        settings = it; settingsStore.save(it)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        voiceCommands?.stop()
        super.onDestroy()
    }
}

// ---------------------------------------------------------------- SETUP

@Composable
private fun SetupScreen(
    params: RoutineParams,
    onParams: (RoutineParams) -> Unit,
    onGenerate: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Build your session", fontSize = 24.sp, fontWeight = FontWeight.Bold) }

        item { SectionHeader("Sections") }
        item { ToggleRow("Warm-up", params.includeWarmup) { onParams(params.copy(includeWarmup = it)) } }
        item {
            ToggleRow("Shadow boxing", params.includeShadow) { onParams(params.copy(includeShadow = it)) }
            if (params.includeShadow) {
                Stepper("Rounds", params.shadowRounds, 1, 8) { onParams(params.copy(shadowRounds = it)) }
                Stepper("Round length (min)", params.shadowRoundSec / 60, 1, 5) { onParams(params.copy(shadowRoundSec = it * 60)) }
            }
        }
        item {
            ToggleRow("Heavy bag", params.includeBag) { onParams(params.copy(includeBag = it)) }
            if (params.includeBag) {
                Stepper("Rounds", params.bagRounds, 1, 12) { onParams(params.copy(bagRounds = it)) }
                Stepper("Round length (min)", params.bagRoundSec / 60, 1, 5) { onParams(params.copy(bagRoundSec = it * 60)) }
            }
        }
        item {
            ToggleRow("Core", params.includeCore) { onParams(params.copy(includeCore = it)) }
            if (params.includeCore) Stepper("Minutes", params.coreSec / 60, 3, 15) { onParams(params.copy(coreSec = it * 60)) }
        }
        item { ToggleRow("Cool-down", params.includeCooldown) { onParams(params.copy(includeCooldown = it)) } }

        item { SectionHeader("Difficulty — combo & movement complexity") }
        item {
            SegmentedRow(Difficulty.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                params.difficulty.ordinal) { onParams(params.copy(difficulty = Difficulty.entries[it])) }
        }
        item { SectionHeader("Intensity — cardio load & pace") }
        item {
            SegmentedRow(Intensity.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) },
                params.intensity.ordinal) { onParams(params.copy(intensity = Intensity.entries[it])) }
        }
        item { Stepper("Base rest between rounds (sec)", params.restSec, 30, 120, step = 15) { onParams(params.copy(restSec = it)) } }

        item {
            Button(onClick = onGenerate, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Generate routine", fontSize = 18.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- REVIEW

@Composable
private fun ReviewScreen(
    routine: Routine,
    onRegenAll: () -> Unit,
    onRegenSection: (Int) -> Unit,
    onRegenRound: (Int, Int) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Review routine", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Total: ${routine.totalSec / 60} min ${routine.totalSec % 60} sec",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            routine.sections.forEachIndexed { si, section ->
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(section.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { onRegenSection(si) }) { Text("Redo section") }
                    }
                }
                itemsIndexed(section.rounds) { ri, round ->
                    if (!round.isRest) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(round.label, fontWeight = FontWeight.Medium)
                                if (section.type == SectionType.SHADOW || section.type == SectionType.BAG) {
                                    TextButton(onClick = { onRegenRound(si, ri) }) { Text("Redo") }
                                }
                            }
                            Text("${round.durationSec / 60} min ${round.durationSec % 60} sec — ${round.summary}",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            round.cues.take(4).forEach { Text("• ${it.text}", fontSize = 13.sp) }
                            if (round.cues.size > 4) Text("… +${round.cues.size - 4} more cues",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, Modifier.weight(1f)) { Text("Back") }
            OutlinedButton(onClick = onRegenAll, Modifier.weight(1f)) { Text("Regen all") }
            Button(onClick = onStart, Modifier.weight(1.4f)) { Text("Start") }
        }
    }
}

// ---------------------------------------------------------------- WORKOUT

@Composable
private fun WorkoutScreen(
    s: WorkoutState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onEnd: () -> Unit,
    onDone: () -> Unit,
) {
    val bg = when {
        s.phase == WorkoutPhase.FINISHED -> Color(0xFF1B5E20)
        s.isRest -> Color(0xFF0D2B45)
        else -> Color(0xFF200A0A)
    }
    Column(
        Modifier.fillMaxSize().background(bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text(s.sectionTitle, fontSize = 18.sp, color = Color.White.copy(alpha = 0.7f))
            Text(if (s.isRest) "REST" else s.roundLabel,
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White,
                textAlign = TextAlign.Center)
        }

        Text(
            "%d:%02d".format(s.secondsLeft / 60, s.secondsLeft % 60),
            fontSize = 96.sp, fontWeight = FontWeight.Bold, color = Color.White
        )

        Text(
            s.currentCue, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            color = Color(0xFFFFD54F), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        if (s.phase == WorkoutPhase.FINISHED) {
            Button(onClick = onDone, Modifier.fillMaxWidth().height(64.dp)) {
                Text("Done", fontSize = 20.sp)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (s.phase == WorkoutPhase.PAUSED)
                        Button(onClick = onResume, Modifier.weight(1f).height(72.dp)) { Text("RESUME", fontSize = 20.sp) }
                    else
                        Button(onClick = onPause, Modifier.weight(1f).height(72.dp)) { Text("PAUSE", fontSize = 20.sp) }
                    OutlinedButton(onClick = onSkip, Modifier.weight(1f).height(72.dp)) { Text("SKIP", fontSize = 20.sp) }
                }
                TextButton(onClick = onEnd, Modifier.fillMaxWidth()) { Text("End workout", color = Color.White.copy(alpha = 0.6f)) }
            }
        }
    }
}

// ---------------------------------------------------------------- HISTORY

@Composable
private fun HistoryScreen(entries: List<HistoryEntry>) {
    val fmt = remember { SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.UK) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("History", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        if (entries.isEmpty()) item { Text("No workouts logged yet.") }
        items(entries.sortedByDescending { it.completedAt }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(fmt.format(Date(e.completedAt)), fontWeight = FontWeight.Medium)
                    Text("${e.durationSec / 60} min · ${e.difficulty.name.lowercase()} difficulty · ${e.intensity.name.lowercase()} intensity",
                        fontSize = 13.sp)
                    Text(e.summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- SETTINGS

@Composable
private fun SettingsScreen(s: AppSettings, onChange: (AppSettings) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        SectionHeader("Voice")
        SegmentedRow(listOf("Duck music", "Over music", "Text only"), s.voiceMode.ordinal) {
            onChange(s.copy(voiceMode = VoiceMode.entries[it]))
        }
        Text("Duck music lowers Spotify / YouTube Music while cues are spoken.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionHeader("Stance")
        SegmentedRow(listOf("Orthodox", "Southpaw"), s.stance.ordinal) {
            onChange(s.copy(stance = Stance.entries[it]))
        }

        ToggleRow("Coaching tips during rest", s.restCoaching) { onChange(s.copy(restCoaching = it)) }
        ToggleRow("Voice commands (experimental)", s.voiceCommands) { onChange(s.copy(voiceCommands = it)) }
        Text("Voice commands: say pause / go / skip / repeat. Accuracy drops with loud music — on-screen buttons always work.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- shared widgets

@Composable
private fun SectionHeader(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { if (value - step >= min) onChange(value - step) }) { Text("−") }
            Text("$value", Modifier.padding(horizontal = 12.dp), fontSize = 16.sp)
            OutlinedButton(onClick = { if (value + step <= max) onChange(value + step) }) { Text("+") }
        }
    }
}

@Composable
private fun SegmentedRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size)
            ) { Text(label) }
        }
    }
}
