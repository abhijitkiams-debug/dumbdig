package com.setu.lending.voice

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Plays WAV bytes returned by Sarvam TTS. Writes to a cache file and plays with
 * MediaPlayer, suspending until playback completes.
 */
class AudioPlayer(private val context: Context) {

    @Volatile private var current: MediaPlayer? = null

    suspend fun play(wav: ByteArray) {
        if (wav.isEmpty()) return
        val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
        file.writeBytes(wav)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val mp = MediaPlayer()
                current = mp
                mp.setOnCompletionListener {
                    it.release()
                    if (current === mp) current = null
                    if (cont.isActive) cont.resume(Unit)
                }
                mp.setOnErrorListener { player, _, _ ->
                    player.release()
                    if (current === mp) current = null
                    if (cont.isActive) cont.resume(Unit)
                    true
                }
                cont.invokeOnCancellation {
                    try { mp.stop() } catch (_: Exception) {}
                    mp.release()
                    if (current === mp) current = null
                }
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.prepare()
                    mp.start()
                } catch (e: Exception) {
                    mp.release()
                    if (current === mp) current = null
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        } finally {
            file.delete()
        }
    }

    fun stop() {
        current?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        current = null
    }
}
