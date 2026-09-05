package id.dotcode.braille.ocr.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.dotcode.braille.ocr.mlkit.OcrEngine
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Idle : UiState
    data object Working : UiState
    /** [sourceUri] travels with the result so a correct-capture sample can be saved later. */
    data class Done(val result: OcrResult, val sourceUri: Uri) : UiState
}

/** Outcome of the most recent "tandai hasil salah" tap, so the result screen can react. */
sealed interface SampleSaveState {
    data object Idle : SampleSaveState
    data object Saved : SampleSaveState
    data object Failed : SampleSaveState
}

class OcrViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = OcrEngine(application)
    private val trainingDataPrefs = TrainingDataPrefs(application)
    private val sampleStore = TrainingSampleStore(application)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _sampleSaveState = MutableStateFlow<SampleSaveState>(SampleSaveState.Idle)
    val sampleSaveState: StateFlow<SampleSaveState> = _sampleSaveState.asStateFlow()

    private val _trainingDataEnabled = MutableStateFlow(trainingDataPrefs.isEnabled())
    val trainingDataEnabled: StateFlow<Boolean> = _trainingDataEnabled.asStateFlow()

    fun recognize(uri: Uri) {
        _state.value = UiState.Working
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { engine.recognize(uri) }
            _state.value = UiState.Done(result, uri)
        }
    }

    fun reset() {
        _sampleSaveState.value = SampleSaveState.Idle
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

    suspend fun trainingSampleCount(): Int = withContext(Dispatchers.IO) { sampleStore.count() }

    suspend fun deleteAllTrainingSamples(): Int =
        withContext(Dispatchers.IO) { sampleStore.deleteAll() }

    override fun onCleared() {
        engine.close()
        super.onCleared()
    }
}
