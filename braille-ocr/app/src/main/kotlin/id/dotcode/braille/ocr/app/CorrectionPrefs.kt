package id.dotcode.braille.ocr.app

import android.content.Context

/**
 * Settings for the offline correction layer (second recognizer + dictionary). On by
 * default: it is free, runs on-device and never sends anything anywhere.
 */
class CorrectionPrefs(context: Context) {

    /** [extraWords] is the raw text of the settings field: one word per line. */
    data class Settings(val enabled: Boolean, val extraWords: String) {
        val extraWordList: List<String>
            get() = extraWords.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        extraWords = prefs.getString(KEY_EXTRA_WORDS, null).orEmpty(),
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_EXTRA_WORDS, settings.extraWords)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "correction_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_EXTRA_WORDS = "extra_words"
    }
}
