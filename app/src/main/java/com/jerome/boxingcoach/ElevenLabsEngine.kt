package com.jerome.boxingcoach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Expressive coach voice via the ElevenLabs cloud TTS API, with a visible,
 * reinstall-proof audio cache.
 *
 * Design (chosen with the user to keep it within the free tier):
 *  - Cache key is the FULL cue text (phrase-level) — never per word — so each
 *    distinct phrase is generated once and replayed free forever. Whole phrases
 *    preserve ElevenLabs' natural flow/emphasis; stitching words would sound
 *    robotic.
 *  - Cache lives in app-external storage (Android/data/<pkg>/files/voice-cache).
 *    It survives update-installs (same signing key) — which is how the user
 *    reinstalls via bcpush — so quota isn't re-spent on routine rebuilds. It
 *    needs no storage permission, and (unlike a MediaStore folder) doesn't
 *    register the clips as audio media that would pollute music apps. A full
 *    uninstall still clears it; that's an accepted rare-case regen cost.
 *  - If there's no API key, no network, or the API errors, speak() falls back to
 *    the system TTS engine so a workout is never left silent.
 *  - prewarm() generates+caches a batch of cue texts up front (called at Start,
 *    during warm-up) so live playback is local and lag-free.
 */
class ElevenLabsEngine(
    private val context: Context,
    private val fallback: SpeechEngine,
) : SpeechEngine {

    override var voiceMode: VoiceMode = VoiceMode.DUCK_MUSIC
        set(value) { field = value; fallback.voiceMode = value }

    var apiKey: String = ""
    var voiceId: String = DEFAULT_VOICE_ID // ElevenLabs voice; user can override in Settings
    /** Master switch: when false, this engine reports not-configured so CoachVoice
     *  uses system TTS even if a key is saved. Lets the user keep the key stored
     *  but toggle the expensive cloud voice on/off. */
    var enabled: Boolean = true

    /** Human-readable result of the most recent speak/generate, for the Test
     *  panel. e.g. "Played ElevenLabs (voice ****TYuZ)", "API 401: invalid api key",
     *  "Offline — used system voice". */
    @Volatile var lastStatus: String = ""
        private set

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playJob: Job? = null
    @Volatile private var currentPlayer: MediaPlayer? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()

    /** App-external cache dir: Android/data/<pkg>/files/voice-cache.
     *  Survives update-installs; no permission needed; not indexed as media. */
    private val cacheDir: File by lazy {
        File(context.getExternalFilesDir(null), "voice-cache").apply { mkdirs() }
    }

    fun isConfigured(): Boolean = enabled && apiKey.isNotBlank()

    private fun keyFor(text: String): String {
        // Include voiceId so switching voices doesn't collide with old cache.
        val raw = "$voiceId::$text"
        val md = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return md.joinToString("") { "%02x".format(it) } + ".mp3"
    }

    private fun cachedFile(text: String): File = File(cacheDir, keyFor(text))

    /** Returns a ready audio file for [text], generating+caching it if needed.
     *  Returns null if unavailable (no key / network / API error). Sets lastStatus. */
    private fun ensureAudio(text: String): File? {
        val f = cachedFile(text)
        if (f.exists() && f.length() > 0) {
            lastStatus = "Cached (voice ${voiceIdTail()}) — free replay"
            return f
        }
        if (!isConfigured()) {
            lastStatus = if (!enabled) "ElevenLabs off — using system voice"
                         else "No API key — using system voice"
            return null
        }
        return runCatching { synthesizeToFile(text, f) }.getOrElse {
            val kind = it.javaClass.simpleName
            val msg = it.message?.take(120).orEmpty()
            lastStatus = "Network error: $kind ${msg} — used system voice"
            null
        }
    }

    private fun voiceIdTail(): String =
        if (voiceId.length >= 4) "****" + voiceId.takeLast(4) else voiceId

    private fun synthesizeToFile(text: String, dest: File): File? {
        // Sanitise credentials: copy-paste often introduces stray newlines/spaces.
        // A newline in the api-key header throws IllegalArgumentException ("unexpected
        // char 0x0a in header value"), and any stray char in the voice ID breaks the URL.
        val cleanKey = apiKey.filterNot { it.isWhitespace() }
        val cleanVoice = voiceId.filterNot { it.isWhitespace() }
        if (!cleanVoice.matches(Regex("^[A-Za-z0-9]{20}$"))) {
            lastStatus = "Voice ID looks invalid (need 20 letters/digits, got ${cleanVoice.length}) — used system voice"
            return null
        }
        val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$cleanVoice")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("xi-api-key", cleanKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
        }
        // Coach-style delivery: model with expressive range + higher style,
        // lower stability = more energy/variation.
        val body = """
            {"text": ${jsonString(text)},
             "model_id": "eleven_turbo_v2_5",
             "voice_settings": {"stability": 0.35, "similarity_boost": 0.8, "style": 0.6, "use_speaker_boost": true}}
        """.trimIndent()
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            // Read the error body — ElevenLabs returns a JSON message explaining why
            // (invalid voice id, bad key, quota exceeded, etc.). This is the info
            // that was previously thrown away, leaving a silent fallback.
            val err = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty().take(180)
            lastStatus = "API $code: ${err.ifBlank { "request rejected" }} — used system voice"
            conn.disconnect()
            return null
        }
        val tmp = File(dest.parentFile, dest.name + ".part")
        conn.inputStream.use { input ->
            java.io.FileOutputStream(tmp).use { out -> input.copyTo(out) }
        }
        conn.disconnect()
        if (tmp.length() <= 0) { tmp.delete(); lastStatus = "Empty audio — used system voice"; return null }
        tmp.renameTo(dest)
        lastStatus = "Generated ElevenLabs (voice ${voiceIdTail()})"
        return dest
    }

    /** Pre-generate & cache a batch of cue texts. Safe to call in background at Start. */
    fun prewarm(texts: Collection<String>) {
        if (!isConfigured()) return
        scope.launch {
            texts.distinct().forEach { runCatching { ensureAudio(it) } }
        }
    }

    override fun speak(text: String) {
        if (voiceMode == VoiceMode.TEXT_ONLY) return
        playJob = scope.launch {
            val file = ensureAudio(text)
            if (file == null) {
                // Nothing cached and couldn't generate → don't leave silence.
                fallback.speak(text)
                return@launch
            }
            playFile(file)
        }
    }

    private fun playFile(file: File) {
        runCatching {
            if (voiceMode == VoiceMode.DUCK_MUSIC) audioManager.requestAudioFocus(focusRequest)
            val mp = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (currentPlayer === it) currentPlayer = null
                    if (voiceMode == VoiceMode.DUCK_MUSIC) audioManager.abandonAudioFocusRequest(focusRequest)
                }
                prepare()
                start()
            }
            currentPlayer = mp
        }.onFailure {
            if (voiceMode == VoiceMode.DUCK_MUSIC) audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    override fun cut() {
        playJob?.cancel()
        currentPlayer?.let { runCatching { it.stop(); it.release() } }
        currentPlayer = null
        fallback.cut()
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    /** For the test panel: is this exact phrase already cached (free to play)? */
    fun isCached(text: String): Boolean = cachedFile(text).let { it.exists() && it.length() > 0 }

    /** Delete this phrase's cached clip so the next speak() regenerates it — used
     *  by the test panel's Force-regenerate so a new voice/settings change is heard. */
    fun clearCached(text: String) { runCatching { cachedFile(text).delete() } }

    companion object {
        // "Michael"-style energetic male preset. User can paste any voice ID in Settings.
        const val DEFAULT_VOICE_ID = "flq6f7yk4E4fJM5XTYuZ"

        private fun jsonString(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
            return sb.append("\"").toString()
        }
    }
}
