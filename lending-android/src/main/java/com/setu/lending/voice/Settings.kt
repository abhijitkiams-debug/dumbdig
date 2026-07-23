package com.setu.lending.voice

import android.content.Context
import com.setu.lending.BuildConfig

/**
 * Holds the Sarvam API key and voice settings.
 *
 * The key is resolved at runtime from SharedPreferences first (so a demo APK
 * can be configured without a rebuild), falling back to BuildConfig, which in
 * turn is injected from local.properties / the SARVAM_API_KEY env var at build
 * time. The key is NEVER hard-coded in source or committed to git.
 */
object Settings {
    private const val PREFS = "setu_settings"
    private const val KEY_API = "sarvam_api_key"
    private const val KEY_LANG = "target_language"
    private const val KEY_SPEAKER = "speaker"

    fun apiKey(context: Context): String {
        val stored = prefs(context).getString(KEY_API, null)?.trim()
        return if (!stored.isNullOrEmpty()) stored else BuildConfig.SARVAM_API_KEY
    }

    fun setApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_API, value.trim()).apply()
    }

    fun hasApiKey(context: Context): Boolean = apiKey(context).isNotBlank()

    fun language(context: Context): String =
        prefs(context).getString(KEY_LANG, "en-IN") ?: "en-IN"

    fun speaker(context: Context): String =
        prefs(context).getString(KEY_SPEAKER, "anushka") ?: "anushka"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
