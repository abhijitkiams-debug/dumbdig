package com.setu.lending.voice

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for Sarvam AI Speech-to-Text and Text-to-Speech.
 *
 * Docs: https://docs.sarvam.ai  ·  auth header: `api-subscription-key`.
 * All calls run on IO and return null / empty on failure (the caller degrades
 * gracefully to on-screen text).
 */
class SarvamClient(private val apiKeyProvider: () -> String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    var lastError: String? = null
        private set

    /** Transcribe a WAV clip. Returns the recognised text, or null on failure. */
    suspend fun transcribe(wav: ByteArray, languageCode: String = "unknown"): String? =
        withContext(Dispatchers.IO) {
            val key = apiKeyProvider()
            if (key.isBlank()) { lastError = "Missing Sarvam API key"; return@withContext null }
            if (wav.isEmpty()) { lastError = "Empty audio"; return@withContext null }
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", "audio.wav",
                        wav.toRequestBody("audio/wav".toMediaType())
                    )
                    .addFormDataPart("model", "saarika:v2.5")
                    .addFormDataPart("language_code", languageCode)
                    .build()
                val req = Request.Builder()
                    .url("https://api.sarvam.ai/speech-to-text")
                    .addHeader("api-subscription-key", key)
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastError = "STT ${resp.code}: ${text.take(180)}"
                        return@withContext null
                    }
                    val transcript = JSONObject(text).optString("transcript", "").trim()
                    lastError = null
                    if (transcript.isEmpty()) null else transcript
                }
            } catch (e: Exception) {
                lastError = "STT error: ${e.message}"
                null
            }
        }

    /** Synthesise speech. Returns WAV bytes, or empty on failure. */
    suspend fun synthesize(
        text: String,
        targetLanguage: String = "en-IN",
        speaker: String = "anushka"
    ): ByteArray = withContext(Dispatchers.IO) {
        val key = apiKeyProvider()
        if (key.isBlank()) { lastError = "Missing Sarvam API key"; return@withContext ByteArray(0) }
        try {
            val payload = JSONObject().apply {
                put("inputs", JSONArray().put(text.take(480)))
                put("target_language_code", targetLanguage)
                put("speaker", speaker)
                put("pitch", 0)
                put("pace", 1.0)
                put("loudness", 1.0)
                put("speech_sample_rate", 22050)
                put("enable_preprocessing", true)
                put("model", "bulbul:v2")
            }
            val req = Request.Builder()
                .url("https://api.sarvam.ai/text-to-speech")
                .addHeader("api-subscription-key", key)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val respText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    lastError = "TTS ${resp.code}: ${respText.take(180)}"
                    return@withContext ByteArray(0)
                }
                val audios = JSONObject(respText).optJSONArray("audios")
                val b64 = audios?.optString(0).orEmpty()
                if (b64.isEmpty()) { lastError = "TTS: empty audio"; return@withContext ByteArray(0) }
                lastError = null
                Base64.decode(b64, Base64.DEFAULT)
            }
        } catch (e: Exception) {
            lastError = "TTS error: ${e.message}"
            ByteArray(0)
        }
    }
}
