package com.jerome.boxingcoach

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.roundToInt

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
        SoundFx.init(applicationContext)
        CoachVoice.init(applicationContext)
        val startupSettings = settingsStore.load()
        CoachVoice.configureEleven(startupSettings.elevenApiKey, startupSettings.elevenVoiceId, startupSettings.elevenEnabled)
        WorkoutEngine.tts = CoachVoice.active

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
            WorkoutEngine.warnSound = settings.warnSound
            WorkoutEngine.endBell = settings.endBell
            CoachVoice.systemEngine?.setVoice(settings.voiceName)
            CoachVoice.configureEleven(settings.elevenApiKey, settings.elevenVoiceId, settings.elevenEnabled)
            WorkoutEngine.tts = CoachVoice.active
        }
        // Keep the screen awake only while a workout is actually on screen and running
        LaunchedEffect(screen, workoutState.phase, settings.keepScreenOn) {
            val shouldStayOn = settings.keepScreenOn && screen == Screen.WORKOUT &&
                (workoutState.phase == WorkoutPhase.RUNNING || workoutState.phase == WorkoutPhase.PAUSED)
            if (shouldStayOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // Log history when a workout finishes
        LaunchedEffect(workoutState.phase) {
            if (workoutState.phase == WorkoutPhase.FINISHED) {
                routine?.let {
                    historyStore.add(
                        HistoryEntry(
                            completedAt = System.currentTimeMillis(),
                            durationSec = workoutState.elapsedTotal,
                            complexity = it.params.complexity,
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
                            routine = RoutineGenerator.generate(params, settings.stance, settings.countReps, settings.restCoaching)
                            screen = Screen.REVIEW
                        })
                    Screen.REVIEW -> routine?.let { r ->
                        ReviewScreen(r,
                            onRegenAll = { routine = RoutineGenerator.generate(params, settings.stance, settings.countReps, settings.restCoaching) },
                            onRegenSection = { si -> routine = RoutineGenerator.regenerateSection(r, si, settings.stance, settings.countReps, settings.restCoaching) },
                            onRegenRound = { si, ri -> routine = RoutineGenerator.regenerateRound(r, si, ri, settings.stance, settings.countReps, settings.restCoaching) },
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

        item { SectionHeader("Difficulty & intensity") }
        item {
            ScaleRow("Combo complexity", "How intricate the combos and movements get.",
                params.complexity) { onParams(params.copy(complexity = it)) }
        }
        item {
            ScaleRow("Cardio intensity", "Pace, rest length, and how much conditioning gets mixed in.",
                params.intensity) { onParams(params.copy(intensity = it)) }
        }
        item { Stepper("Rest between rounds (sec)", params.restSec, 30, 120, step = 15) { onParams(params.copy(restSec = it)) } }
        item { Stepper("Rest between segments — gear change (sec)", params.restBetweenSectionsSec, 30, 300, step = 30) { onParams(params.copy(restBetweenSectionsSec = it)) } }

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
                                } else if (round.isGuided) {
                                    TextButton(onClick = { onRegenSection(si) }) { Text("Redo") }
                                }
                            }
                            if (round.isGuided) {
                                Text("${round.exerciseNames.size} exercises — ${round.summary}",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                round.exerciseNames.forEachIndexed { idx, name ->
                                    Text("${idx + 1}. $name", fontSize = 13.sp,
                                        modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                                }
                            } else {
                            Text("${round.durationSec / 60} min ${round.durationSec % 60} sec — ${round.summary}",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            round.cues.firstOrNull { it.isIntro }?.let {
                                Text(it.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                            }
                            val commandSample = round.cues.filter { it.isCommand }.take(5)
                            if (commandSample.isNotEmpty()) {
                                Text("Live: " + commandSample.joinToString("  ·  ") { it.text },
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            }
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
        Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Minimal, low-weight context line — round/section info is secondary to timing+action.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${if (s.isRest) "REST" else s.sectionTitle.uppercase()} · ${s.roundLabel.substringAfter("— ").ifBlank { s.roundLabel }}",
                fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center, maxLines = 1
            )
            // Trigger-word legend for this round: e.g. "Go → jab, cross · Down → two squats"
            if (s.legend.isNotBlank() && !s.isRest) {
                Text(
                    s.legend,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            // Guided sections: which exercise of how many (only on actual exercises).
            if (s.guided && s.stepTotal > 0 && (s.timed || s.repTotal > 0)) {
                Text(
                    "Exercise ${s.stepIndex.coerceAtLeast(1)} / ${s.stepTotal}",
                    fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // ---- Focal area: the single thing to do right now ----
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when {
                s.phase == WorkoutPhase.FINISHED -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("WORKOUT", fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f))
                    Text("COMPLETE", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
                s.guided -> GuidedFocus(s)
                s.isRest -> RestFocus(s)
                else -> WorkFocus(s)
            }
        }

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

/** m:ss for a countdown. */
private fun clock(sec: Int): String {
    val v = sec.coerceAtLeast(0)
    return "%d:%02d".format(v / 60, v % 60)
}

/** Combat round focal area: countdown timer above, the live command below —
 *  a big gold ONE!/TWO! on multi-combo calls, "GET READY" before the first cue. */
@Composable
private fun WorkFocus(s: WorkoutState) {
    val gold = Color(0xFFFFD54F)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Timer sits above as a secondary reference; the command/combo below is the hero.
        Text(clock(s.secondsLeft), fontSize = 66.sp, fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.75f), lineHeight = 66.sp)
        Spacer(Modifier.height(20.dp))
        when {
            // Multi-combo call: giant ONE! / TWO! plus the combo, boxed to pop off the bg.
            s.comboCue > 0 -> CommandBox(gold) {
                Text("${comboWord(s.comboCue)}!", fontSize = 96.sp, fontWeight = FontWeight.Black,
                    color = gold, lineHeight = 96.sp, maxLines = 1, textAlign = TextAlign.Center)
                if (s.comboCueText.isNotBlank())
                    Text(s.comboCueText, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        color = Color.White, textAlign = TextAlign.Center, lineHeight = 38.sp,
                        maxLines = 2, modifier = Modifier.padding(top = 8.dp))
            }
            s.currentCue.isBlank() ->
                Text("GET READY", fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.55f), textAlign = TextAlign.Center)
            // Single trigger word ("GO", "HIT", "DOWN"…): big and boxed.
            s.currentCueIsCommand -> CommandBox(gold) {
                Text(s.currentCue, fontSize = 68.sp, fontWeight = FontWeight.Black,
                    color = gold, textAlign = TextAlign.Center, lineHeight = 72.sp, maxLines = 2)
            }
            // Non-command spoken line (a coaching tip): present but understated.
            else ->
                Text(s.currentCue, fontSize = 26.sp, fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center,
                    lineHeight = 32.sp, maxLines = 4)
        }
    }
}

/** Highlighted container that makes the live command/combo pop off the background. */
@Composable
private fun CommandBox(accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
            .border(BorderStroke(2.dp, accent.copy(alpha = 0.5f)), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** Rest focal area: just the label and the big countdown. */
@Composable
private fun RestFocus(s: WorkoutState) {
    val gold = Color(0xFFFFD54F)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("REST", fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.7f), letterSpacing = 4.sp)
        Text(clock(s.secondsLeft), fontSize = 88.sp, fontWeight = FontWeight.Bold,
            color = Color.White, lineHeight = 88.sp)

        // Preview the upcoming round for the whole rest, so the combos become familiar
        // before the bell. The spoken intro still comes later in the rest.
        if (s.nextRoundLabel.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(gold.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                    .border(BorderStroke(1.dp, gold.copy(alpha = 0.4f)), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("NEXT ROUND", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = gold, letterSpacing = 3.sp)
                    Text(s.nextRoundLabel.substringAfter("— ").ifBlank { s.nextRoundLabel },
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                    if (s.nextLegend.isNotBlank())
                        Text(s.nextLegend, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center,
                            lineHeight = 22.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** Guided focal area (warm-up / core / cool-down): the exercise name and its
 *  detail, then a live countdown for timed holds or a rep counter for counted
 *  sets, plus a LEFT/RIGHT side prompt. No spoken narration on screen. */
@Composable
private fun GuidedFocus(s: WorkoutState) {
    val gold = Color(0xFFFFD54F)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(s.exerciseName.uppercase().ifBlank { "GET READY" },
            fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
            textAlign = TextAlign.Center, lineHeight = 44.sp, maxLines = 3)
        if (s.exerciseDetail.isNotBlank())
            Text(s.exerciseDetail, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        if (s.timed)
            Text("${s.secondsLeft}", fontSize = 88.sp, fontWeight = FontWeight.Bold,
                color = gold, lineHeight = 88.sp)
        else if (s.repTotal > 0)
            Text("${s.repCount} / ${s.repTotal}", fontSize = 72.sp, fontWeight = FontWeight.Bold,
                color = gold, lineHeight = 72.sp)
        if (s.sidePrompt.isNotBlank())
            Text(s.sidePrompt, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
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
                    Text("${e.durationSec / 60} min · complexity ${e.complexity}/10 · intensity ${e.intensity}/10",
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        SectionHeader("Voice")
        SegmentedRow(listOf("Duck music", "Over music", "Text only"), s.voiceMode.ordinal) {
            onChange(s.copy(voiceMode = VoiceMode.entries[it]))
        }
        Text("Duck music lowers Spotify / YouTube Music while cues are spoken.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionHeader("Coach voice — ElevenLabs (expressive)")
        Text("Paste an ElevenLabs API key to use the expressive cloud coach voice. Leave blank (or turn off below) to use the system/neural TTS engine. Audio for each phrase is generated once and cached, so repeat use stays within the free tier. Falls back to system voice when offline.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow("Use ElevenLabs voice", s.elevenEnabled) { onChange(s.copy(elevenEnabled = it)) }
        OutlinedTextField(
            value = s.elevenApiKey,
            onValueChange = { onChange(s.copy(elevenApiKey = it.trim())) },
            label = { Text("ElevenLabs API key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = s.elevenVoiceId,
            onValueChange = { onChange(s.copy(elevenVoiceId = it.trim())) },
            label = { Text("Voice ID (optional — blank = default)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (CoachVoice.usingEleven) "Active: ElevenLabs expressive voice."
            else if (s.elevenApiKey.isNotBlank() && !s.elevenEnabled) "Active: system / neural TTS (ElevenLabs turned off)."
            else "Active: system / neural TTS (no ElevenLabs key set).",
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = if (CoachVoice.usingEleven) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ---- Voice test panel (fast iteration on cue phrasing / voice) ----
        SectionHeader("Test voice")
        var testText by remember { mutableStateOf("Round two! Jab, cross, left hook — GO! Empty the tank!") }
        var lastStatus by remember { mutableStateOf("") }
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            label = { Text("Cue to speak") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                CoachVoice.active?.let { it.cut(); it.speak(testText) }
                // Give the engine a beat to attempt generation, then read status.
                lastStatus = "Speaking…"
            }, modifier = Modifier.weight(1f)) { Text("Speak") }
            OutlinedButton(onClick = { CoachVoice.active?.cut() }, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }
        // Force-regenerate: clears this phrase's cached clip so a new voice ID or
        // voice-setting change is actually heard (otherwise the old cached audio
        // just replays). Only meaningful when ElevenLabs is active.
        TextButton(onClick = {
            CoachVoice.elevenLabs?.let {
                it.clearCached(testText)
                it.cut(); it.speak(testText)
                lastStatus = "Regenerating…"
            }
        }) { Text("Force regenerate this phrase") }

        // Live status readout — polls the engine's lastStatus so you can see which
        // voice actually played, or the exact API error if it fell back.
        LaunchedEffect(lastStatus) {
            if (lastStatus.endsWith("…")) {
                kotlinx.coroutines.delay(1200)
                lastStatus = CoachVoice.elevenLabs?.lastStatus?.ifBlank { "Done" } ?: "System voice"
            }
        }
        val cacheState = CoachVoice.elevenLabs?.let {
            if (!it.isConfigured()) "System voice — no caching."
            else if (it.isCached(testText)) "This exact phrase is cached (free replay)."
            else "Not cached yet — first Speak generates it (uses characters)."
        } ?: ""
        if (cacheState.isNotBlank()) Text(cacheState, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lastStatus.isNotBlank()) Text("Status: $lastStatus", fontSize = 12.sp,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)

        SectionHeader("Voice engine (fallback / no-key)")
        var voiceRefresh by remember { mutableStateOf(0) }
        val voices = remember(voiceRefresh) { CoachVoice.systemEngine?.availableVoices() ?: emptyList() }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Voices found: ${voices.size}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { voiceRefresh++ }) { Text("Refresh") }
        }
        if (voices.isEmpty()) {
            Text("If you installed a neural TTS engine (e.g. SherpaTTS / sherpa-onnx), open its app once to finish setup, set it as the default engine in Android Settings → Text-to-speech, then tap Refresh. Fully reopening this app also helps.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (voices.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                val currentLabel = voices.firstOrNull { it.name == s.voiceName }
                    ?.let { friendlyVoiceName(it.name) } ?: "Auto (best available)"
                Box {
                    OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
                        Text("System voice: $currentLabel")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Auto (best available)") },
                            onClick = {
                                expanded = false
                                onChange(s.copy(voiceName = ""))
                                CoachVoice.systemEngine?.setVoice("", preview = true)
                            })
                        voices.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(friendlyVoiceName(v.name)) },
                                onClick = {
                                    expanded = false
                                    onChange(s.copy(voiceName = v.name))
                                    CoachVoice.systemEngine?.setVoice(v.name, preview = true)
                                })
                        }
                    }
                }
                Text("Picking a voice plays a short preview. Only voices installed on the phone are listed.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

        SectionHeader("Stance")
        SegmentedRow(listOf("Orthodox", "Southpaw"), s.stance.ordinal) {
            onChange(s.copy(stance = Stance.entries[it]))
        }

        ToggleRow("In-round coaching tips", s.restCoaching) { onChange(s.copy(restCoaching = it)) }
        ToggleRow("Count warm-up reps aloud", s.countReps) { onChange(s.copy(countReps = it)) }
        ToggleRow("Voice commands (experimental)", s.voiceCommands) { onChange(s.copy(voiceCommands = it)) }
        Text("Voice commands: say pause / go / skip / repeat. Accuracy drops with loud music — on-screen buttons always work.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionHeader("During workout")
        ToggleRow("Keep screen on", s.keepScreenOn) { onChange(s.copy(keepScreenOn = it)) }
        ToggleRow("Clap sound at 10 seconds left", s.warnSound) { onChange(s.copy(warnSound = it)) }
        ToggleRow("Bell at round changes", s.endBell) { onChange(s.copy(endBell = it)) }
    }
}

// ---------------------------------------------------------------- shared widgets

/** Combo number → spoken-style word for the big in-workout ONE! / TWO! display. */
private fun comboWord(n: Int): String = when (n) {
    1 -> "ONE"
    2 -> "TWO"
    3 -> "THREE"
    else -> n.toString()
}

/** "en-gb-x-gbb-local" → "English (GB) — gbb". Best effort; raw name fallback. */
private fun friendlyVoiceName(raw: String): String {
    val parts = raw.split("-")
    return if (parts.size >= 4 && parts[2] == "x") {
        "English (${parts[1].uppercase()}) — ${parts[3]}"
    } else raw
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { if (value - step >= min) onChange(value - step) }) { Text("−") }
            Text("$value", Modifier.padding(horizontal = 12.dp), fontSize = 16.sp)
            OutlinedButton(onClick = { if (value + step <= max) onChange(value + step) }) { Text("+") }
        }
    }
}

/** A labelled 1–10 slider with a live value readout and a one-line hint. */
@Composable
private fun ScaleRow(label: String, hint: String, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("$value / 10", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(1, 10)) },
            valueRange = 1f..10f,
            steps = 8, // 8 stops between the ends => 10 discrete values (1..10)
        )
        Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
