package com.originalgames.flipside

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

    /** A quick rising "blip" for flipping gravity. */
    fun flip() = play(startFreq = 520f, endFreq = 760f, durationMs = 90, volume = 0.35f)

    /** A bright sparkle for collecting a gem. */
    fun gem() = play(startFreq = 880f, endFreq = 1320f, durationMs = 120, volume = 0.40f)

    /** A low falling tone for crashing. */
    fun crash() = play(startFreq = 320f, endFreq = 70f, durationMs = 420, volume = 0.55f)

    /** A soft confirmation tone for starting a run. */
    fun start() = play(startFreq = 440f, endFreq = 660f, durationMs = 140, volume = 0.30f)

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
