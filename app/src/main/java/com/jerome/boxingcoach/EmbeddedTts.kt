package com.jerome.boxingcoach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Embedded, fully offline neural voice: a bundled Piper/VITS model run through
 * sherpa-onnx (ONNX Runtime) entirely inside the app — no system TTS engine to
 * install, no Android settings to configure. This is what makes "the voice"
 * work identically for everyone who builds the app, out of the box.
 *
 * EXPERIMENTAL: wraps a third-party native library whose exact Kotlin API can
 * shift slightly between releases. If this file fails to compile, it's almost
 * always a small signature mismatch against whatever sherpa-onnx-android version
 * Gradle resolves — send the exact error and it's a quick patch, not a rebuild.
 *
 * Requires model files bundled at app/src/main/assets/piper/ — model.onnx,
 * tokens.txt, espeak-ng-data/ (see README "Embedded voice" for where to get a
 * free Piper voice and the exact commands to add it). Without those files,
 * isAvailable() returns false and CoachVoice falls back to system TTS — the
 * app builds and runs fine either way.
 */
class EmbeddedTts private constructor(
    context: Context,
    private val tts: OfflineTts,
) : SpeechEngine {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    override var voiceMode: VoiceMode = VoiceMode.DUCK_MUSIC

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<String>(capacity = Channel.UNLIMITED)
    private val currentTrack = AtomicReference<AudioTrack?>(null)
    private var worker: Job? = null

    init {
        worker = scope.launch {
            for (text in queue) {
                if (!isActive) break
                runCatching { synthesizeAndPlay(text) }
            }
        }
    }

    override fun speak(text: String) {
        if (voiceMode == VoiceMode.TEXT_ONLY) return
        queue.trySend(text)
    }

    /** Immediately drop anything queued and stop whatever's playing right now. */
    override fun cut() {
        while (queue.tryReceive().isSuccess) { /* drain queued lines */ }
        currentTrack.getAndSet(null)?.let { runCatching { it.stop(); it.release() } }
    }

    private suspend fun synthesizeAndPlay(text: String) {
        val audio: GeneratedAudio = runCatching { tts.generate(text, 0, 1.05f) }.getOrNull() ?: return
        val samples = audio.samples
        val sr = audio.sampleRate
        if (samples.isEmpty()) return

        var focusRequest: AudioFocusRequest? = null
        if (voiceMode == VoiceMode.DUCK_MUSIC) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs).build()
            audioManager.requestAudioFocus(focusRequest)
        }

        val pcm = ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        currentTrack.set(track)
        track.write(pcm, 0, pcm.size)
        track.play()

        val durationMs = pcm.size * 1000L / sr
        delay(durationMs + 60)
        runCatching { track.release() }
        currentTrack.compareAndSet(track, null)
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    fun shutdown() {
        worker?.cancel()
        runCatching { tts.release() }
    }

    companion object {
        /** Cheap check — true only if a bundled model is present in assets/piper/. */
        fun isAvailable(context: Context): Boolean = runCatching {
            val files = context.assets.list("piper") ?: return false
            files.contains("model.onnx") && files.contains("tokens.txt")
        }.getOrDefault(false)

        /** Attempts to load the embedded model. Returns null on ANY failure (missing
         *  or corrupt assets, native init error) — caller falls back to system TTS. */
        fun tryCreate(context: Context): EmbeddedTts? {
            if (!isAvailable(context)) return null
            return runCatching {
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = "piper/model.onnx",
                            tokens = "piper/tokens.txt",
                            dataDir = "piper/espeak-ng-data",
                            lexicon = "",
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                    maxNumSentences = 1,
                )
                val tts = OfflineTts(assetManager = context.assets, config = config)
                EmbeddedTts(context, tts)
            }.getOrNull()
        }
    }
}
