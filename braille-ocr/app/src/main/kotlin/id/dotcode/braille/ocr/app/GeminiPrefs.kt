package id.dotcode.braille.ocr.app

import android.content.Context

/**
 * Settings for the optional Gemini correction pass: off by default, and inert without a
 * key. The key lives in this app's private `SharedPreferences` (excluded from backup via
 * `allowBackup="false"`), which is adequate for a tester's own key, not for a shipped one.
 */
class GeminiPrefs(context: Context) {

    data class Settings(val enabled: Boolean, val apiKey: String, val model: String) {
        /** Whether a scan should actually be sent. */
        val isActive: Boolean get() = enabled && apiKey.isNotBlank()
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
        model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: GeminiProtocol.DEFAULT_MODEL,
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putString(KEY_MODEL, settings.model.trim())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "gemini_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
    }
}
