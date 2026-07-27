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

    // --- Distinct, layered ACTIVATION signatures (played on release) ---
    // Each astra layers 2-3 tones so it is identifiable by ear alone; no astra
    // shares a base tone with another.
    fun castBaan() = play(760f, 520f, 90, 0.26f)                                   // crisp bowstring twang
    fun castAgni() { play(180f, 90f, 300, 0.34f, noise = 0.5f); play(520f, 900f, 260, 0.22f) }   // low roar + rising flame
    fun castNaga() { play(1500f, 820f, 240, 0.24f); play(300f, 260f, 240, 0.18f, noise = 0.7f) } // hiss + slither
    fun castGada() { play(150f, 60f, 320, 0.5f); play(300f, 120f, 200, 0.22f) }                   // heavy whirl thud
    fun castBrahma() { play(90f, 70f, 620, 0.4f); play(300f, 1400f, 560, 0.3f); play(660f, 990f, 560, 0.2f) } // divine resonance chord

    fun cast(w: Weapon) = when (w) {
        Weapon.BAAN -> castBaan(); Weapon.AGNI -> castAgni(); Weapon.NAGA -> castNaga()
        Weapon.GADA -> castGada(); Weapon.BRAHMA -> castBrahma()
    }

    // --- Distinct per-astra IMPACT sounds (played on hit) ---
    fun impactBaan() = play(880f, 1150f, 70, 0.30f)
    fun impactAgni() { play(500f, 160f, 360, 0.55f, noise = 0.4f); play(1000f, 300f, 200, 0.2f) }  // fiery burst
    fun impactNaga() { play(1300f, 600f, 240, 0.38f); play(700f, 400f, 200, 0.2f, noise = 0.5f) }  // venom crack
    fun impactGada() = play(220f, 60f, 400, 0.62f, noise = 0.3f)                                    // earth-shaking thud
    fun impactBrahma() { play(300f, 1600f, 500, 0.6f); play(120f, 80f, 520, 0.45f) }                // cataclysm

    fun impact(w: Weapon) = when (w) {
        Weapon.BAAN -> impactBaan(); Weapon.AGNI -> impactAgni(); Weapon.NAGA -> impactNaga()
        Weapon.GADA -> impactGada(); Weapon.BRAHMA -> impactBrahma()
    }

    // --- Helper summons ---
    fun hanuman() = play(300f, 900f, 420, 0.55f)             // heroic leap
    fun lakshman() = play(700f, 1300f, 260, 0.45f)           // precise volley

    /**
     * Renders a short tone: a frequency glide with an exponential decay, plus an
     * optional [noise] mix (0..1) of filtered white noise for fire/hiss texture.
     * Layering several of these (fired together) builds each astra's signature.
     */
    private fun play(startFreq: Float, endFreq: Float, durationMs: Int, volume: Float, noise: Float = 0f) {
        if (!enabled) return
        thread(isDaemon = true) {
            try {
                val samples = sampleRate * durationMs / 1000
                val buffer = ShortArray(samples)
                var phase = 0.0
                var last = 0f
                for (i in 0 until samples) {
                    val t = i.toFloat() / samples
                    val freq = startFreq + (endFreq - startFreq) * t
                    phase += 2.0 * PI * freq / sampleRate
                    val env = exp(-3.0 * t).toFloat()
                    var s = sin(phase).toFloat() * (1f - noise)
                    if (noise > 0f) {
                        // Low-passed white noise for a breathy/fiery layer.
                        val n = (Math.random().toFloat() * 2f - 1f)
                        last += (n - last) * 0.25f
                        s += last * noise
                    }
                    s *= env * volume
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
