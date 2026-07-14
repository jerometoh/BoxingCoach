package com.jerome.boxingcoach

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesizes boxing gym sounds in code (no bundled audio files):
 *  - bell(): a metallic ring built from inharmonic partials with a sharp strike
 *    transient and long decay — reads as a real ring bell, not a beep.
 *    ringBell() plays the classic double "ding-ding".
 *  - clapper(): the wooden 10-second warning clacker — three sharp broadband
 *    clacks in quick succession.
 *
 * Plays on the media stream so it follows the same output route as music/TTS.
 */
object SoundFx {

    private const val SR = 44100

    private fun play(samples: ShortArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.setNotificationMarkerPosition(samples.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) { runCatching { t?.release() } }
            override fun onPeriodicNotification(t: AudioTrack?) {}
        })
        track.play()
    }

    /** One bell strike: inharmonic metallic partials, fast attack, ~1.4s decay. */
    private fun bellSamples(seconds: Double = 1.4, amp: Double = 0.55): ShortArray {
        val n = (SR * seconds).toInt()
        val out = ShortArray(n)
        // Inharmonic partial ratios loosely modelled on a struck bell plate.
        val partials = listOf(
            Triple(620.0, 1.0, 3.2),    // fundamental-ish: freq, relative amp, decay rate
            Triple(1240.0, 0.55, 4.0),
            Triple(1660.0, 0.35, 5.0),
            Triple(2480.0, 0.28, 6.5),
            Triple(3320.0, 0.18, 8.0),
            Triple(4150.0, 0.10, 10.0),
        )
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            var v = 0.0
            for ((f, a, d) in partials) v += a * exp(-d * t) * sin(2 * PI * f * t)
            // Strike transient: brief noise burst in the first ~8ms
            if (t < 0.008) v += (Random.nextDouble() * 2 - 1) * (1 - t / 0.008) * 0.8
            out[i] = (v * amp * Short.MAX_VALUE / 2.2).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    /** One wooden clack: sharp broadband burst with a woody resonance, ~90ms. */
    private fun clackSamples(amp: Double = 0.8): ShortArray {
        val n = (SR * 0.09).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            var v = (Random.nextDouble() * 2 - 1) * exp(-70 * t)          // sharp noise crack
            v += 0.5 * exp(-40 * t) * sin(2 * PI * 850.0 * t)              // woody body
            v += 0.3 * exp(-55 * t) * sin(2 * PI * 1900.0 * t)
            out[i] = (v * amp * Short.MAX_VALUE / 1.8).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun silence(seconds: Double) = ShortArray((SR * seconds).toInt())

    private fun concat(vararg parts: ShortArray): ShortArray {
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var pos = 0
        for (p in parts) { p.copyInto(out, pos); pos += p.size }
        return out
    }

    /** Classic boxing round bell: "ding-ding". */
    fun ringBell() {
        play(concat(bellSamples(0.6), silence(0.05), bellSamples(1.4)))
    }

    /** Single longer ring (workout complete etc.). */
    fun singleBell() {
        play(bellSamples(1.6, amp = 0.6))
    }

    /** Boxing clapper: three fast wooden clacks (the 10-seconds-left warning). */
    fun clapper() {
        play(concat(clackSamples(), silence(0.11), clackSamples(), silence(0.11), clackSamples()))
    }
}
