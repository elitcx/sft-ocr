package id.dotcode.braille.ocr.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.dotcode.braille.ocr.mlkit.OcrEngine
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
    data class Done(val result: OcrResult) : UiState
}

class OcrViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = OcrEngine(application)
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun recognize(uri: Uri) {
        _state.value = UiState.Working
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { engine.recognize(uri) }
            _state.value = UiState.Done(result)
        }
    }

    fun reset() { _state.value = UiState.Idle }

    override fun onCleared() {
        engine.close()
        super.onCleared()
    }
}
