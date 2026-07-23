package com.setu.lending.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records mic audio as 16 kHz mono PCM16 and returns a WAV byte array suitable
 * for Sarvam STT. Publishes a 0..1 [amplitude] for the Siri orb, and does
 * simple silence-based endpointing so the user doesn't have to press stop.
 */
class AudioRecorder {

    private val sampleRate = 16_000
    private val channel = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    val amplitude = MutableStateFlow(0f)

    @Volatile private var stopRequested = false

    fun requestStop() { stopRequested = true }

    /**
     * Blocks (on IO) until endpointed and returns WAV bytes.
     * @param maxMs hard cap on utterance length
     * @param trailingSilenceMs silence after speech that ends the turn
     */
    @SuppressLint("MissingPermission")
    suspend fun record(maxMs: Int = 12_000, trailingSilenceMs: Int = 1_300): ByteArray =
        withContext(Dispatchers.IO) {
            stopRequested = false
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            val bufSize = if (minBuf > 0) minBuf * 2 else sampleRate // fallback
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, channel, encoding, bufSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@withContext ByteArray(0)
            }

            val pcm = ByteArrayOutputStream()
            val buffer = ShortArray(minOf(bufSize / 2, 2048).coerceAtLeast(512))
            recorder.startRecording()

            val startNs = System.nanoTime()
            var speechStarted = false
            var lastVoiceNs = startNs
            // amplitude threshold (RMS on PCM16, normalised 0..1)
            val speechThreshold = 0.06f

            try {
                while (!stopRequested) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    // RMS -> normalised amplitude
                    var sum = 0.0
                    for (i in 0 until read) {
                        val s = buffer[i].toDouble()
                        sum += s * s
                    }
                    val rms = sqrt(sum / read) / 32768.0
                    val amp = min(1.0, rms * 4.0).toFloat()
                    amplitude.value = amp

                    // append raw little-endian PCM16
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val v = buffer[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    pcm.write(bytes)

                    val now = System.nanoTime()
                    if (amp >= speechThreshold) {
                        speechStarted = true
                        lastVoiceNs = now
                    }
                    val elapsedMs = (now - startNs) / 1_000_000
                    val silenceMs = (now - lastVoiceNs) / 1_000_000
                    if (elapsedMs >= maxMs) break
                    if (speechStarted && silenceMs >= trailingSilenceMs) break
                }
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
                amplitude.value = 0f
            }

            val pcmBytes = pcm.toByteArray()
            if (pcmBytes.isEmpty()) ByteArray(0) else wrapWav(pcmBytes)
        }

    /** Wrap raw PCM16 mono into a minimal 44-byte-header WAV container. */
    private fun wrapWav(pcm: ByteArray): ByteArray {
        val byteRate = sampleRate * 2 // mono * 16-bit
        val dataLen = pcm.size
        val totalLen = 36 + dataLen
        val out = ByteArrayOutputStream(44 + dataLen)
        fun writeStr(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

        writeStr("RIFF"); writeIntLE(totalLen); writeStr("WAVE")
        writeStr("fmt "); writeIntLE(16); writeShortLE(1); writeShortLE(1)
        writeIntLE(sampleRate); writeIntLE(byteRate); writeShortLE(2); writeShortLE(16)
        writeStr("data"); writeIntLE(dataLen); out.write(pcm)
        return out.toByteArray()
    }
}
