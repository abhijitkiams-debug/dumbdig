package com.originalgames.astra

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioAttributes
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Generates all sound effects procedurally at runtime. No audio files are
 * shipped or used, which keeps the app completely free of third-party assets.
 *
 * Each effect is a short PCM tone (sine wave with an exponential decay
 * envelope) rendered to an [AudioTrack] on a throwaway thread so it never
 * blocks the game loop.
 */
class SoundManager(var enabled: Boolean = true) {

    private val sampleRate = 44100

    /** A soft pluck for loosing an arrow from the bow. */
    fun shoot() = play(startFreq = 700f, endFreq = 430f, durationMs = 70, volume = 0.22f)

    /** A bright tick for an arrow striking Ravan. */
    fun hit() = play(startFreq = 900f, endFreq = 1180f, durationMs = 70, volume = 0.30f)

    /** A heavy descending tone for severing one of Ravan's heads. */
    fun sever() = play(startFreq = 540f, endFreq = 120f, durationMs = 360, volume = 0.55f)

    /** A rising divine whoosh for unleashing an astra. */
    fun astra() = play(startFreq = 320f, endFreq = 1400f, durationMs = 320, volume = 0.45f)

    /** A low thud for Ram taking a hit. */
    fun hurt() = play(startFreq = 300f, endFreq = 70f, durationMs = 300, volume = 0.5f)

    /** A short confirmation for choosing a boon / starting a battle. */
    fun confirm() = play(startFreq = 480f, endFreq = 720f, durationMs = 130, volume = 0.30f)

    /** A bright triumphant tone for victory over Ravan. */
    fun victory() = play(startFreq = 660f, endFreq = 990f, durationMs = 480, volume = 0.5f)

    private fun play(startFreq: Float, endFreq: Float, durationMs: Int, volume: Float) {
        if (!enabled) return
        thread(isDaemon = true) {
            try {
                val samples = sampleRate * durationMs / 1000
                val buffer = ShortArray(samples)
                var phase = 0.0
                for (i in 0 until samples) {
                    val t = i.toFloat() / samples
                    // Linearly glide frequency from start to end.
                    val freq = startFreq + (endFreq - startFreq) * t
                    phase += 2.0 * PI * freq / sampleRate
                    // Exponential decay so notes never click on release.
                    val env = exp(-3.0 * t).toFloat()
                    val s = sin(phase).toFloat() * env * volume
                    buffer[i] = (s * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
                writeAndPlay(buffer)
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
