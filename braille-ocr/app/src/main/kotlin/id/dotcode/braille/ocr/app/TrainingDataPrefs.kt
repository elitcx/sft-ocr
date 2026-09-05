package id.dotcode.braille.ocr.app

import android.content.Context

/**
 * The opt-in switch for local training-data capture (spec: data collection must be opt-in,
 * off by default, and controllable by the user). Nothing under [TrainingSampleStore] is ever
 * written unless this is `true`, and nothing here ever touches the network — this is a plain
 * `SharedPreferences` boolean, private to this app, read and written on-device only.
 */
class TrainingDataPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFS_NAME = "training_data_prefs"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = false
    }
}
