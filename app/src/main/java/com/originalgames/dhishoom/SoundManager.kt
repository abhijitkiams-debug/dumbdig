package com.originalgames.dhishoom

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates every sound effect procedurally at runtime — no audio files are
 * shipped or used, which keeps the app completely free of third-party assets.
 *
 * Tonal effects are sine sweeps with an exponential decay envelope; impact
 * effects ("dhishoom!") mix in filtered noise so punches and kicks land with a
 * percussive thwack. Each effect renders to a throwaway [AudioTrack] on its own
 * daemon thread so audio never blocks the 60 fps game loop.
 */
class SoundManager(var enabled: Boolean = true) {

    private val sampleRate = 44100

    /** Soft confirmation blip for menu taps. */
    fun tap() = tone(startFreq = 440f, endFreq = 660f, durationMs = 70, volume = 0.28f)

    /** Airy swing when an attack starts but before it connects. */
    fun whoosh() = noise(durationMs = 110, volume = 0.18f, startCut = 0.9f, endCut = 0.2f)

    /** Crunchy connect for a landed punch. */
    fun punch() = impact(toneFreq = 180f, durationMs = 150, volume = 0.5f, noiseMix = 0.6f)

    /** Heavier, lower thud for a landed kick. */
    fun kick() = impact(toneFreq = 120f, durationMs = 220, volume = 0.6f, noiseMix = 0.55f)

    /** Metallic tick when a hit is blocked. */
    fun block() = tone(startFreq = 1200f, endFreq = 700f, durationMs = 90, volume = 0.35f)

    /** Big rising "DHISHOOM!" for the super special. */
    fun special() {
        tone(startFreq = 220f, endFreq = 880f, durationMs = 260, volume = 0.5f)
        impact(toneFreq = 90f, durationMs = 320, volume = 0.65f, noiseMix = 0.7f)
    }

    /** Low falling boom for a knockdown / K.O. */
    fun ko() = impact(toneFreq = 70f, durationMs = 520, volume = 0.7f, noiseMix = 0.5f)

    /** Ring-the-bell ding to start a round. */
    fun bell() {
        tone(startFreq = 990f, endFreq = 990f, durationMs = 90, volume = 0.4f)
        tone(startFreq = 1480f, endFreq = 1320f, durationMs = 360, volume = 0.3f)
    }

    /** Triumphant rising arpeggio for a match win. */
    fun win() {
        tone(660f, 660f, 110, 0.4f)
        tone(880f, 880f, 110, 0.4f)
        tone(1320f, 1320f, 240, 0.4f)
    }

    // ----------------------------------------------------------------------

    private fun tone(startFreq: Float, endFreq: Float, durationMs: Int, volume: Float) {
        if (!enabled) return
        render {
            val samples = sampleRate * durationMs / 1000
            val buffer = ShortArray(samples)
            var phase = 0.0
            for (i in 0 until samples) {
                val t = i.toFloat() / samples
                val freq = startFreq + (endFreq - startFreq) * t
                phase += 2.0 * PI * freq / sampleRate
                val env = exp(-3.0 * t).toFloat()
                buffer[i] = clamp(sin(phase).toFloat() * env * volume)
            }
            buffer
        }
    }

    /** A burst of low-passed noise — used for swings. */
    private fun noise(durationMs: Int, volume: Float, startCut: Float, endCut: Float) {
        if (!enabled) return
        render {
            val samples = sampleRate * durationMs / 1000
            val buffer = ShortArray(samples)
            var last = 0f
            for (i in 0 until samples) {
                val t = i.toFloat() / samples
                val cut = startCut + (endCut - startCut) * t   // simple one-pole LPF coeff
                val white = Random.nextFloat() * 2f - 1f
                last += cut * (white - last)
                val env = exp(-3.5 * t).toFloat()
                buffer[i] = clamp(last * env * volume)
            }
            buffer
        }
    }

    /** A tonal thump mixed with noise — used for hits/specials/KO. */
    private fun impact(toneFreq: Float, durationMs: Int, volume: Float, noiseMix: Float) {
        if (!enabled) return
        render {
            val samples = sampleRate * durationMs / 1000
            val buffer = ShortArray(samples)
            var phase = 0.0
            var last = 0f
            for (i in 0 until samples) {
                val t = i.toFloat() / samples
                phase += 2.0 * PI * (toneFreq * (1f - 0.5f * t)) / sampleRate
                val toneS = sin(phase).toFloat()
                val white = Random.nextFloat() * 2f - 1f
                last += 0.5f * (white - last)
                val mix = toneS * (1f - noiseMix) + last * noiseMix
                val env = exp(-5.0 * t).toFloat()
                buffer[i] = clamp(mix * env * volume)
            }
            buffer
        }
    }

    private fun clamp(s: Float): Short =
        (s * Short.MAX_VALUE).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()

    private inline fun render(crossinline build: () -> ShortArray) {
        thread(isDaemon = true) {
            try {
                writeAndPlay(build())
            } catch (_: Throwable) {
                // Audio is non-essential; never let it crash gameplay.
            }
        }
    }

    private fun writeAndPlay(buffer: ShortArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val sizeBytes = maxOf(minBuf, buffer.size * 2)

        @Suppress("DEPRECATION")
        val track = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(sizeBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } else {
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                sizeBytes,
                AudioTrack.MODE_STATIC
            )
        }

        track.write(buffer, 0, buffer.size)
        track.setNotificationMarkerPosition(buffer.size)
        track.setPlaybackPositionUpdateListener(object :
            AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) {
                try {
                    t?.stop(); t?.release()
                } catch (_: Throwable) {
                }
            }

            override fun onPeriodicNotification(t: AudioTrack?) {}
        })
        track.play()
    }
}
