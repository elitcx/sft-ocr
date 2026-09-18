package id.dotcode.braille.ocr.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The parts of the app that Android downloads on demand rather than shipping in the APK:
 * Google's document scanner (a Play services module) and the text-to-speech voices. They are
 * fetched as soon as the app first runs with internet, so later use works offline.
 */
class OfflinePackages(
    context: Context,
    private val speech: SpeechController,
    private val scope: CoroutineScope,
) {
    enum class State { CHECKING, DOWNLOADING, READY, NEEDS_INTERNET, UNAVAILABLE }

    data class Status(
        val scanner: State = State.CHECKING,
        /** 0..1 while the scanner module downloads, when Play services reports it. */
        val scannerProgress: Float? = null,
        val voices: Map<String, State> = VOICE_LANGUAGES.associateWith { State.CHECKING },
    ) {
        val allReady: Boolean get() = scanner == State.READY && voices.values.all { it == State.READY }
    }

    private val appContext = context.applicationContext
    private val moduleClient = ModuleInstall.getClient(appContext)
    private val scanner = GmsDocumentScanning.getClient(
        GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build(),
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Checks everything and starts whatever download is still missing. Safe to call often. */
    fun ensure() {
        ensureScanner()
        scope.launch { ensureVoices() }
    }

    private fun ensureScanner() {
        val current = _status.value.scanner
        if (current == State.READY || current == State.DOWNLOADING) return
        moduleClient.areModulesAvailable(scanner)
            .addOnSuccessListener { response ->
                if (response.areModulesAvailable()) {
                    setScanner(State.READY)
                } else {
                    installScanner()
                }
            }
            .addOnFailureListener { setScanner(State.UNAVAILABLE) }
    }

    private fun installScanner() {
        if (!isOnline()) {
            setScanner(State.NEEDS_INTERNET)
            return
        }
        val listener = object : InstallStatusListener {
            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                val progress = update.progressInfo?.let { info ->
                    if (info.totalBytesToDownload > 0) {
                        info.bytesDownloaded.toFloat() / info.totalBytesToDownload
                    } else null
                }
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                        setScanner(State.READY)
                        moduleClient.unregisterListener(this)
                    }
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                        setScanner(if (isOnline()) State.UNAVAILABLE else State.NEEDS_INTERNET)
                        moduleClient.unregisterListener(this)
                    }
                    else -> _status.update { it.copy(scanner = State.DOWNLOADING, scannerProgress = progress) }
                }
            }
        }
        setScanner(State.DOWNLOADING)
        val request = ModuleInstallRequest.newBuilder()
            .addApi(scanner)
            .setListener(listener)
            .build()
        moduleClient.installModules(request)
            .addOnSuccessListener { response ->
                if (response.areModulesAlreadyInstalled()) {
                    setScanner(State.READY)
                    moduleClient.unregisterListener(listener)
                }
            }
            .addOnFailureListener {
                setScanner(if (isOnline()) State.UNAVAILABLE else State.NEEDS_INTERNET)
                moduleClient.unregisterListener(listener)
            }
    }

    private suspend fun ensureVoices() {
        val availability = speech.availability.first { it != SpeechController.Availability.INITIALIZING }
        if (availability != SpeechController.Availability.READY) {
            _status.update { status -> status.copy(voices = status.voices.mapValues { State.UNAVAILABLE }) }
            return
        }
        VOICE_LANGUAGES.forEach { language ->
            val locale = voiceLocale(language)
            val state = when (speech.voiceData(locale)) {
                SpeechController.VoiceData.READY -> State.READY
                SpeechController.VoiceData.UNSUPPORTED -> State.UNAVAILABLE
                SpeechController.VoiceData.MISSING -> {
                    if (isOnline()) {
                        // The engine fetches a missing voice when first asked to use it.
                        speech.prefetchVoice(locale)
                        State.DOWNLOADING
                    } else {
                        State.NEEDS_INTERNET
                    }
                }
            }
            setVoice(language, state)
        }
        // A voice download finishes in the engine's own time; look again for a while.
        repeat(VOICE_RECHECKS) {
            if (_status.value.voices.values.none { it == State.DOWNLOADING }) return
            delay(VOICE_RECHECK_MS)
            VOICE_LANGUAGES.forEach { language ->
                if (_status.value.voices[language] == State.DOWNLOADING &&
                    speech.voiceData(voiceLocale(language)) == SpeechController.VoiceData.READY
                ) {
                    setVoice(language, State.READY)
                }
            }
        }
    }

    private fun setScanner(state: State) {
        _status.update { it.copy(scanner = state, scannerProgress = null) }
    }

    private fun setVoice(language: String, state: State) {
        _status.update { it.copy(voices = it.voices + (language to state)) }
    }

    private fun isOnline(): Boolean {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        val VOICE_LANGUAGES = listOf(AppPrefs.LANG_ID, AppPrefs.LANG_EN)
        private const val VOICE_RECHECKS = 30
        private const val VOICE_RECHECK_MS = 10_000L
    }
}
