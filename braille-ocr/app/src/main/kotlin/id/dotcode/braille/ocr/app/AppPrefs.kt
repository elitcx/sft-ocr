package id.dotcode.braille.ocr.app

import android.content.Context

/** App-level preferences that are not tied to one feature's own prefs file. */
class AppPrefs(context: Context) {

    /** What happens as soon as a scan finishes. */
    enum class AfterScan { RESULT, SPEAK, READ_MODE }

    /** The accessibility choices made on the setup page. */
    data class Assist(
        val guidedCapture: Boolean,
        val spokenStatus: Boolean,
        val haptics: Boolean,
        val afterScan: AfterScan,
    )

    data class Reading(
        val autoSpeak: Boolean,
        val autoSend: Boolean,
        val speechRate: Float,
        val voiceLanguage: String,
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, null)?.takeIf { it == LANG_EN || it == LANG_ID } ?: LANG_ID
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** A manually chosen receiver; null means "pick the ESP32 by name". */
    var selectedDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ADDRESS, value).apply()

    /** Recent per-phase scan durations; see [PhaseHistory]. */
    var phaseHistory: String?
        get() = prefs.getString(KEY_PHASE_HISTORY, null)
        set(value) = prefs.edit().putString(KEY_PHASE_HISTORY, value).apply()

    /** Firmware version the BraillePad last reported, keyed to its address. */
    fun firmwareFor(address: String): String? = prefs.getString(KEY_FIRMWARE_PREFIX + address, null)

    fun saveFirmware(address: String, version: String) {
        prefs.edit().putString(KEY_FIRMWARE_PREFIX + address, version).apply()
    }

    var setupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    var tutorialDone: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_TUTORIAL_DONE, value).apply()

    fun loadAssist() = Assist(
        guidedCapture = prefs.getBoolean(KEY_GUIDED_CAPTURE, true),
        spokenStatus = prefs.getBoolean(KEY_SPOKEN_STATUS, true),
        haptics = prefs.getBoolean(KEY_HAPTICS, true),
        afterScan = runCatching { AfterScan.valueOf(prefs.getString(KEY_AFTER_SCAN, null).orEmpty()) }
            .getOrDefault(AfterScan.RESULT),
    )

    fun saveAssist(assist: Assist) {
        prefs.edit()
            .putBoolean(KEY_GUIDED_CAPTURE, assist.guidedCapture)
            .putBoolean(KEY_SPOKEN_STATUS, assist.spokenStatus)
            .putBoolean(KEY_HAPTICS, assist.haptics)
            .putString(KEY_AFTER_SCAN, assist.afterScan.name)
            .apply()
    }

    fun loadReading() = Reading(
        autoSpeak = prefs.getBoolean(KEY_AUTO_SPEAK, true),
        // Off by default: each word is a separate Bluetooth connection, which only makes
        // sense once a BraillePad is actually set up.
        autoSend = prefs.getBoolean(KEY_AUTO_SEND, false),
        speechRate = prefs.getFloat(KEY_SPEECH_RATE, 1f).coerceIn(MIN_RATE, MAX_RATE),
        voiceLanguage = prefs.getString(KEY_VOICE_LANGUAGE, null)
            ?.takeIf { it == LANG_EN || it == LANG_ID } ?: LANG_ID,
    )

    fun saveReading(reading: Reading) {
        prefs.edit()
            .putBoolean(KEY_AUTO_SPEAK, reading.autoSpeak)
            .putBoolean(KEY_AUTO_SEND, reading.autoSend)
            .putFloat(KEY_SPEECH_RATE, reading.speechRate)
            .putString(KEY_VOICE_LANGUAGE, reading.voiceLanguage)
            .apply()
    }

    companion object {
        const val LANG_ID = "id"
        const val LANG_EN = "en"
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2f

        private const val PREFS_NAME = "app_prefs"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_AUTO_SPEAK = "auto_speak"
        private const val KEY_AUTO_SEND = "auto_send"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_VOICE_LANGUAGE = "voice_language"
        private const val KEY_PHASE_HISTORY = "phase_history"
        private const val KEY_FIRMWARE_PREFIX = "firmware_"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_TUTORIAL_DONE = "tutorial_done"
        private const val KEY_GUIDED_CAPTURE = "guided_capture"
        private const val KEY_SPOKEN_STATUS = "spoken_status"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_AFTER_SCAN = "after_scan"
    }
}
