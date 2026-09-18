package id.dotcode.braille.ocr.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.dotcode.braille.ocr.mlkit.OcrEngine
import id.dotcode.braille.ocr.model.FailureReason
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.OcrResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Where a [UiState.Done] result came from - a training sample only makes sense for a photo. */
enum class SourceKind { CAMERA, DOCUMENT }

/** Which part of the work is running, for the processing screen's checklist. */
enum class WorkStage { RECOGNIZING, CORRECTING, EXTRACTING }

sealed interface UiState {
    data object Idle : UiState
    data class Working(val kind: SourceKind, val stage: WorkStage, val geminiPlanned: Boolean) : UiState
    /**
     * [sourceUri] travels with the result so a correct-capture sample can be saved later.
     * [result] holds the corrected page; its `uncorrected` field the recognizer's own text.
     * [fromHistory] results were reopened from storage and have no source image any more.
     */
    data class Done(
        val result: OcrResult,
        val sourceUri: Uri,
        val correction: GeminiCorrection = GeminiCorrection.Off,
        val sourceKind: SourceKind = SourceKind.CAMERA,
        val historyId: String? = null,
        val fromHistory: Boolean = false,
    ) : UiState
}

/** What the optional Gemini pass did to the most recent scan. */
sealed interface GeminiCorrection {
    data object Off : GeminiCorrection
    data class Applied(val changedBlocks: Int, val elapsedMs: Long) : GeminiCorrection
    data class Failed(val message: String) : GeminiCorrection
}

/** Outcome of the most recent "tandai hasil salah" tap, so the result screen can react. */
sealed interface SampleSaveState {
    data object Idle : SampleSaveState
    data object Saved : SampleSaveState
    data object Failed : SampleSaveState
}

/** Progress of the most recent send to the BraillePad (classic Bluetooth). */
sealed interface Esp32SendState {
    data object Idle : Esp32SendState
    data object Sending : Esp32SendState
    data class Sent(val bytes: Int) : Esp32SendState
    data class Failed(val error: Esp32SendException) : Esp32SendState
}

/** Read Mode's per-word send, which only reports the latest word. */
sealed interface WordSendState {
    data object Idle : WordSendState
    data object NotReady : WordSendState
    data object Sending : WordSendState
    data object Sent : WordSendState
    data class Failed(val error: Esp32SendException) : WordSendState
}

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    /** [firmware] is null when the device answered the connection but not the info request. */
    data class Ok(val firmware: String?) : ConnectionTestState
    data class Failed(val error: Esp32SendException) : ConnectionTestState
}

data class LastSend(val bytes: Int, val at: Long)

class OcrViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = OcrEngine(application)
    private val engineLock = Mutex()
    private val trainingDataPrefs = TrainingDataPrefs(application)
    private val sampleStore = TrainingSampleStore(application)
    private val geminiPrefs = GeminiPrefs(application)
    private val geminiCorrector = GeminiCorrector()
    private val correctionPrefs = CorrectionPrefs(application)
    private val appPrefs = AppPrefs(application)
    private val historyStore = HistoryStore(File(application.filesDir, "history"))

    val esp32Sender = Esp32BluetoothSender(application) { appPrefs.selectedDeviceAddress }
    val braillePad = BraillePad(application, appPrefs, esp32Sender.requiredPermissions)
    val speech = SpeechController(application)
    val haptics = Haptics(application) { _assist.value.haptics }
    val offlinePackages = OfflinePackages(application, speech, viewModelScope)
    val navigator = Navigator(
        when {
            !appPrefs.onboardingDone -> Route.Onboarding
            !appPrefs.setupDone -> Route.Setup
            else -> Route.Home
        },
    )

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _sampleSaveState = MutableStateFlow<SampleSaveState>(SampleSaveState.Idle)
    val sampleSaveState: StateFlow<SampleSaveState> = _sampleSaveState.asStateFlow()

    private val _esp32SendState = MutableStateFlow<Esp32SendState>(Esp32SendState.Idle)
    val esp32SendState: StateFlow<Esp32SendState> = _esp32SendState.asStateFlow()

    private val _wordSendState = MutableStateFlow<WordSendState>(WordSendState.Idle)
    val wordSendState: StateFlow<WordSendState> = _wordSendState.asStateFlow()

    private val _connectionTest = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    private val _lastSend = MutableStateFlow<LastSend?>(null)
    val lastSend: StateFlow<LastSend?> = _lastSend.asStateFlow()

    private val _geminiSettings = MutableStateFlow(geminiPrefs.load())
    val geminiSettings: StateFlow<GeminiPrefs.Settings> = _geminiSettings.asStateFlow()

    private val _correctionSettings = MutableStateFlow(correctionPrefs.load())
    val correctionSettings: StateFlow<CorrectionPrefs.Settings> = _correctionSettings.asStateFlow()

    private val _trainingDataEnabled = MutableStateFlow(trainingDataPrefs.isEnabled())
    val trainingDataEnabled: StateFlow<Boolean> = _trainingDataEnabled.asStateFlow()

    private val _language = MutableStateFlow(appPrefs.language)
    val language: StateFlow<String> = _language.asStateFlow()

    private val _assist = MutableStateFlow(appPrefs.loadAssist())
    val assist: StateFlow<AppPrefs.Assist> = _assist.asStateFlow()

    private val _reading = MutableStateFlow(appPrefs.loadReading())
    val reading: StateFlow<AppPrefs.Reading> = _reading.asStateFlow()

    private val allHistory = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val pendingDeletes = MutableStateFlow<Set<String>>(emptySet())
    val history: StateFlow<List<HistoryEntry>> = combine(allHistory, pendingDeletes) { entries, pending ->
        entries.filterNot { it.id in pending }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var workJob: Job? = null

    private val phaseHistory = PhaseHistory(appPrefs.phaseHistory)
    private val _progress = MutableStateFlow<WorkProgress?>(null)
    val progress: StateFlow<WorkProgress?> = _progress.asStateFlow()

    // Written from the recognizer's threads through its stage callback.
    @Volatile private var progressPlan: List<Pair<ScanPhase, Long>> = emptyList()
    @Volatile private var currentPhase: ScanPhase = ScanPhase.DECODE
    @Volatile private var phaseStartedAt = 0L
    private var progressTicker: Job? = null
    private val wordRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        applyCorrectionSettings(_correctionSettings.value, warmUp = true)
        // Fetch the on-demand parts (scanner module, voices) while there's internet.
        offlinePackages.ensure()
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { historyStore.list() }
            // A scan may already have finished while the list was loading; keep it.
            allHistory.update { current ->
                (current + stored.filterNot { s -> current.any { it.id == s.id } })
                    .sortedByDescending { it.createdAt }
            }
        }
        // One connection at a time; while one word is going out, only the newest waits.
        viewModelScope.launch {
            wordRequests.collect { word -> sendWordNow(word) }
        }
    }

    fun setCorrectionSettings(settings: CorrectionPrefs.Settings) {
        val switchedOn = settings.enabled && !_correctionSettings.value.enabled
        correctionPrefs.save(settings)
        _correctionSettings.value = settings
        applyCorrectionSettings(settings, warmUp = switchedOn)
    }

    private fun applyCorrectionSettings(settings: CorrectionPrefs.Settings, warmUp: Boolean) {
        engine.correctionSettings = OcrEngine.CorrectionSettings(settings.enabled, settings.extraWordList)
        // Loading the dictionary and Tesseract's models takes a moment; do it before the
        // first scan rather than during it. Not on every keystroke in the word list.
        if (warmUp) viewModelScope.launch(Dispatchers.Default) { engine.warmUp() }
    }

    fun recognize(uri: Uri) {
        val phases = listOfNotNull(
            ScanPhase.DECODE,
            ScanPhase.PREPROCESS,
            ScanPhase.RECOGNIZE,
            ScanPhase.SECOND_READ.takeIf { _correctionSettings.value.enabled },
            ScanPhase.ASSEMBLE,
            ScanPhase.GEMINI.takeIf { _geminiSettings.value.isActive },
        )
        startWork(SourceKind.CAMERA, WorkStage.RECOGNIZING, planOf(phases)) {
            val result = guarded {
                withContext(Dispatchers.Default) {
                    engineLock.withLock { engine.recognize(uri) { enterPhase(phaseOf(it)) } }
                }
            }
            if (result is OcrResult.Success) learn(ScanProgress.phasesOf(result.document.timings))
            val gemini = _geminiSettings.value
            if (result is OcrResult.Success && gemini.isActive) {
                correctWithGemini(result, uri, gemini, SourceKind.CAMERA, historyId = null)
            } else {
                finish(UiState.Done(result, uri))
            }
        }
    }

    /**
     * Extracts text straight from a PDF/DOCX - no OCR, the text layer is already there, so
     * unlike [recognize] this never runs Gemini/offline correction: there's no recognition
     * error to fix, and correcting already-exact text only risks the model rewriting it.
     */
    fun importDocument(uri: Uri, mimeType: String?) {
        val size = fileSize(uri)
        // The learned EXTRACT figure is a rate per megabyte, so the estimate follows the file.
        val expected = (phaseHistory.expected(ScanPhase.EXTRACT) * size / BYTES_PER_MB).coerceAtLeast(MIN_EXTRACT_MS)
        startWork(SourceKind.DOCUMENT, WorkStage.EXTRACTING, listOf(ScanPhase.EXTRACT to expected)) {
            val started = SystemClock.elapsedRealtime()
            val (name, result) = withContext(Dispatchers.IO) {
                displayName(uri) to guarded { DocumentTextExtractor.extract(getApplication(), uri, mimeType) }
            }
            if (result is OcrResult.Success && size > 0) {
                val elapsed = SystemClock.elapsedRealtime() - started
                learn(mapOf(ScanPhase.EXTRACT to elapsed * BYTES_PER_MB / size))
            }
            finish(UiState.Done(result, uri, sourceKind = SourceKind.DOCUMENT), fileName = name)
        }
    }

    /**
     * Stops the running work; its result, if it still arrives, is dropped. Cancelling the
     * Gemini pass keeps the page already read, marked as a failed correction that can be retried.
     */
    fun cancelWork() {
        workJob?.cancel()
        workJob = null
        val fallback = geminiFallback
        geminiFallback = null
        if (_state.value !is UiState.Working) return
        if (fallback != null) {
            val cancelled = stringsFor(_language.value).failure(FailureReason.Cancelled)
            finish(fallback.copy(correction = GeminiCorrection.Failed(cancelled)))
        } else {
            stopProgress(completed = false)
            _state.value = UiState.Idle
        }
    }

    /** What to show if the Gemini pass in progress is cancelled. */
    private var geminiFallback: UiState.Done? = null

    /** Re-runs only the Gemini pass on the current scan, e.g. after a "high demand" failure. */
    fun retryGeminiCorrection() {
        val current = _state.value as? UiState.Done ?: return
        val result = current.result as? OcrResult.Success ?: return
        if (current.correction !is GeminiCorrection.Failed) return
        startWork(current.sourceKind, WorkStage.CORRECTING, planOf(listOf(ScanPhase.GEMINI))) {
            correctWithGemini(result, current.sourceUri, _geminiSettings.value, current.sourceKind, current.historyId)
        }
    }

    private fun startWork(
        kind: SourceKind,
        stage: WorkStage,
        plan: List<Pair<ScanPhase, Long>>,
        block: suspend () -> Unit,
    ) {
        workJob?.cancel()
        startProgress(plan)
        geminiFallback = null
        _sampleSaveState.value = SampleSaveState.Idle
        _esp32SendState.value = Esp32SendState.Idle
        speech.stop()
        _state.value = UiState.Working(
            kind, stage,
            geminiPlanned = stage == WorkStage.CORRECTING ||
                (kind == SourceKind.CAMERA && _geminiSettings.value.isActive),
        )
        workJob = viewModelScope.launch { block() }
    }

    /** A crash inside a recognizer or a malformed file must not take the app down with it. */
    private inline fun guarded(block: () -> OcrResult): OcrResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Reading failed", e)
        OcrResult.Failure(FailureReason.NoTextFound, e.message)
    }

    private fun planOf(phases: List<ScanPhase>) = phases.map { it to phaseHistory.expected(it) }

    private fun phaseOf(stage: OcrEngine.Stage) = when (stage) {
        OcrEngine.Stage.DECODING -> ScanPhase.DECODE
        OcrEngine.Stage.PREPROCESSING -> ScanPhase.PREPROCESS
        OcrEngine.Stage.RECOGNIZING -> ScanPhase.RECOGNIZE
        OcrEngine.Stage.SECOND_READ -> ScanPhase.SECOND_READ
        OcrEngine.Stage.ASSEMBLING -> ScanPhase.ASSEMBLE
    }

    private fun startProgress(plan: List<Pair<ScanPhase, Long>>) {
        progressPlan = plan
        enterPhase(plan.first().first)
        _progress.value = WorkProgress(0f, plan.sumOf { it.second })
        progressTicker?.cancel()
        progressTicker = viewModelScope.launch {
            while (true) {
                val estimate = ScanProgress.estimate(
                    progressPlan, currentPhase, SystemClock.elapsedRealtime() - phaseStartedAt,
                )
                // The bar never moves backwards, even when a phase finishes early.
                _progress.update { previous ->
                    if (previous == null || estimate.fraction >= previous.fraction) estimate
                    else previous.copy(remainingMs = estimate.remainingMs)
                }
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private fun enterPhase(phase: ScanPhase) {
        currentPhase = phase
        phaseStartedAt = SystemClock.elapsedRealtime()
    }

    private fun stopProgress(completed: Boolean) {
        progressTicker?.cancel()
        progressTicker = null
        _progress.value = if (completed) WorkProgress(1f, 0) else null
    }

    private fun learn(durations: Map<ScanPhase, Long>) {
        phaseHistory.record(durations)
        appPrefs.phaseHistory = phaseHistory.serialize()
    }

    private companion object {
        const val TAG = "OcrViewModel"
        const val PROGRESS_TICK_MS = 100L
        const val BYTES_PER_MB = 1_048_576L
        const val MIN_EXTRACT_MS = 500L
    }

    /** Runs Gemini on top of the offline correction; the recognizer's own text stays available. */
    private suspend fun correctWithGemini(
        result: OcrResult.Success,
        uri: Uri,
        gemini: GeminiPrefs.Settings,
        sourceKind: SourceKind,
        historyId: String?,
    ) {
        geminiFallback = UiState.Done(result, uri, sourceKind = sourceKind, historyId = historyId)
        enterPhase(ScanPhase.GEMINI)
        _state.value = UiState.Working(sourceKind, WorkStage.CORRECTING, geminiPlanned = true)
        val done = try {
            val model = gemini.model.ifBlank { GeminiProtocol.DEFAULT_MODEL }
            val outcome = geminiCorrector.correct(result.document, gemini.apiKey, model)
            learn(mapOf(ScanPhase.GEMINI to outcome.elapsedMs))
            val corrected = if (outcome.changedBlocks == 0) {
                result
            } else {
                OcrResult.Success(outcome.document, uncorrected = result.uncorrected ?: result.document)
            }
            UiState.Done(
                corrected, uri,
                GeminiCorrection.Applied(outcome.changedBlocks, outcome.elapsedMs),
                sourceKind, historyId,
            )
        } catch (e: GeminiException) {
            // A failed correction must not cost the scan: keep the offline result.
            UiState.Done(result, uri, GeminiCorrection.Failed(e.message ?: "Gemini"), sourceKind, historyId)
        }
        geminiFallback = null
        finish(done)
    }

    /** Publishes a result and records a successful one in History. */
    private fun finish(done: UiState.Done, fileName: String? = null) {
        val success = done.result as? OcrResult.Success
        stopProgress(completed = success != null)
        if (success == null) {
            (done.result as? OcrResult.Failure)?.let { failure ->
                if (failure.detail != null) Log.w(TAG, "${done.sourceKind} failed: ${failure.reason}: ${failure.detail}")
            }
            _state.value = done
            return
        }
        val id = done.historyId ?: UUID.randomUUID().toString()
        _state.value = done.copy(historyId = id)

        val document = success.document
        val existing = allHistory.value.firstOrNull { it.id == id }
        val title = existing?.title ?: HistoryStore.titleFor(document, fileName)
        val entry = HistoryEntry(
            id = id,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            title = title,
            preview = HistoryStore.previewFor(document, title),
            sourceKind = done.sourceKind,
            wordCount = wordCount(TextExport.toPlainText(document)),
            corrected = success.uncorrected != null,
            sentToDevice = existing?.sentToDevice ?: false,
        )
        allHistory.update { entries ->
            (listOf(entry) + entries.filterNot { it.id == id }).sortedByDescending { it.createdAt }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { historyStore.save(entry, document, success.uncorrected) }
        }
    }

    /** Shows a stored scan on the result screen; false if it can no longer be read. */
    suspend fun openHistory(id: String): Boolean {
        val entry = history.value.firstOrNull { it.id == id } ?: return false
        val documents = withContext(Dispatchers.IO) { historyStore.load(id) } ?: return false
        workJob?.cancel()
        _sampleSaveState.value = SampleSaveState.Idle
        _esp32SendState.value = Esp32SendState.Idle
        speech.stop()
        _state.value = UiState.Done(
            OcrResult.Success(documents.document, documents.uncorrected),
            Uri.EMPTY,
            sourceKind = entry.sourceKind,
            historyId = id,
            fromHistory = true,
        )
        return true
    }

    /**
     * Hides an entry at once; it is only removed from disk by [commitDeleteHistory]. Returns
     * false if the entry was already on its way out, so a repeated request is ignored.
     */
    fun deleteHistory(id: String): Boolean {
        if (id in pendingDeletes.value) return false
        pendingDeletes.update { it + id }
        return true
    }

    fun undoDeleteHistory(id: String) = pendingDeletes.update { it - id }

    fun commitDeleteHistory(id: String) {
        if (id !in pendingDeletes.value) return
        allHistory.update { entries -> entries.filterNot { it.id == id } }
        pendingDeletes.update { it - id }
        viewModelScope.launch(Dispatchers.IO) { historyStore.delete(id) }
    }

    fun clearHistory() {
        allHistory.value = emptyList()
        pendingDeletes.value = emptySet()
        viewModelScope.launch(Dispatchers.IO) { historyStore.deleteAll() }
    }

    fun setGeminiSettings(settings: GeminiPrefs.Settings) {
        geminiPrefs.save(settings)
        _geminiSettings.value = settings
    }

    fun setLanguage(language: String) {
        appPrefs.language = language
        _language.value = language
    }

    fun setReading(reading: AppPrefs.Reading) {
        appPrefs.saveReading(reading)
        _reading.value = reading
    }

    /** Reads the current result aloud, the same playback the result screen's player shows. */
    fun speakResult() {
        val document = ((_state.value as? UiState.Done)?.result as? OcrResult.Success)?.document ?: return
        val reading = _reading.value
        speech.speak(
            RESULT_SPEECH_KEY,
            TextExport.toPlainText(document),
            voiceLocale(reading.voiceLanguage),
            reading.speechRate,
        )
    }

    fun setAssist(assist: AppPrefs.Assist) {
        appPrefs.saveAssist(assist)
        _assist.value = assist
    }

    fun completeOnboarding() {
        appPrefs.onboardingDone = true
        // First run continues into setup, then the tutorial.
        navigator.resetTo(if (appPrefs.setupDone) Route.Home else Route.Setup)
    }

    fun completeSetup() {
        appPrefs.setupDone = true
        navigator.resetTo(if (appPrefs.tutorialDone) Route.Home else Route.Tutorial)
    }

    fun completeTutorial() {
        appPrefs.tutorialDone = true
        navigator.resetTo(Route.Home)
    }

    fun openSetup() = navigator.push(Route.Setup)

    fun openTutorial() = navigator.push(Route.Tutorial)

    fun showOnboardingAgain() {
        appPrefs.onboardingDone = false
        navigator.resetTo(Route.Onboarding)
    }

    fun reset() {
        workJob?.cancel()
        stopProgress(completed = false)
        _sampleSaveState.value = SampleSaveState.Idle
        _esp32SendState.value = Esp32SendState.Idle
        _state.value = UiState.Idle
    }

    fun setTrainingDataEnabled(enabled: Boolean) {
        trainingDataPrefs.setEnabled(enabled)
        _trainingDataEnabled.value = enabled
    }

    /** Saves the current capture (image + document + text) as a raw-OCR training sample. */
    fun saveWrongResultSample(uri: Uri, document: OcrDocument) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { sampleStore.save(uri, document) }
            _sampleSaveState.value = if (ok) SampleSaveState.Saved else SampleSaveState.Failed
        }
    }

    /** Sends the same plain text "Ekspor Teks" produces to the BraillePad over classic Bluetooth. */
    fun sendToEsp32(document: OcrDocument) {
        if (_esp32SendState.value == Esp32SendState.Sending) return
        _esp32SendState.value = Esp32SendState.Sending
        val historyId = (_state.value as? UiState.Done)?.historyId
        viewModelScope.launch {
            _esp32SendState.value = try {
                val bytes = esp32Sender.send(TextExport.toPlainText(document))
                _lastSend.value = LastSend(bytes, System.currentTimeMillis())
                if (historyId != null) markSent(historyId)
                Esp32SendState.Sent(bytes)
            } catch (e: Esp32SendException) {
                Esp32SendState.Failed(e)
            }
            braillePad.refresh()
        }
    }

    /** Read Mode: queue one word for the BraillePad; only the newest pending word is kept. */
    fun sendWord(word: String) {
        if (braillePad.status.value !is BraillePad.Status.Ready) {
            _wordSendState.value = WordSendState.NotReady
            return
        }
        wordRequests.tryEmit(word)
    }

    fun clearWordSendState() {
        _wordSendState.value = WordSendState.Idle
    }

    private suspend fun sendWordNow(word: String) {
        _wordSendState.value = WordSendState.Sending
        _wordSendState.value = try {
            val bytes = esp32Sender.send(word)
            _lastSend.value = LastSend(bytes, System.currentTimeMillis())
            WordSendState.Sent
        } catch (e: Esp32SendException) {
            WordSendState.Failed(e)
        }
    }

    /** Connects to the BraillePad and reads its firmware version; doubles as a connection test. */
    fun checkDevice() {
        if (_connectionTest.value == ConnectionTestState.Testing) return
        val address = (braillePad.status.value as? BraillePad.Status.Ready)?.device?.address ?: return
        _connectionTest.value = ConnectionTestState.Testing
        viewModelScope.launch {
            _connectionTest.value = try {
                val firmware = esp32Sender.readFirmware()
                if (firmware != null) braillePad.rememberFirmware(address, firmware)
                ConnectionTestState.Ok(firmware)
            } catch (e: Esp32SendException) {
                ConnectionTestState.Failed(e)
            }
        }
    }

    fun clearConnectionTest() {
        if (_connectionTest.value != ConnectionTestState.Testing) _connectionTest.value = ConnectionTestState.Idle
    }

    fun selectDevice(address: String?) {
        braillePad.select(address)
        clearConnectionTest()
    }

    private fun markSent(id: String) {
        val entry = allHistory.value.firstOrNull { it.id == id } ?: return
        if (entry.sentToDevice) return
        val updated = entry.copy(sentToDevice = true)
        allHistory.update { entries -> entries.map { if (it.id == id) updated else it } }
        viewModelScope.launch(Dispatchers.IO) { runCatching { historyStore.updateMeta(updated) } }
    }

    private fun fileSize(uri: Uri): Long = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L }
    }.getOrNull() ?: 0L

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    suspend fun trainingSampleCount(): Int = withContext(Dispatchers.IO) { sampleStore.count() }

    suspend fun deleteAllTrainingSamples(): Int =
        withContext(Dispatchers.IO) { sampleStore.deleteAll() }

    override fun onCleared() {
        // Deletions still waiting on their undo snackbar are final once the screen is gone.
        pendingDeletes.value.forEach { runCatching { historyStore.delete(it) } }
        engine.close()
        speech.shutdown()
        braillePad.close()
        super.onCleared()
    }
}
