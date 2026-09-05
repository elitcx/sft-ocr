package id.dotcode.braille.ocr.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import id.dotcode.braille.ocr.model.OcrResult

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val model: OcrViewModel = viewModel()
                val state by model.state.collectAsState()
                val trainingDataEnabled by model.trainingDataEnabled.collectAsState()
                val sampleSaveState by model.sampleSaveState.collectAsState()
                var showSettings by remember { mutableStateOf(false) }

                var hasCamera by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }
                val request = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> hasCamera = granted }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (!hasCamera) request.launch(Manifest.permission.CAMERA)
                }

                if (showSettings) {
                    SettingsScreen(
                        trainingDataEnabled = trainingDataEnabled,
                        onTrainingDataEnabledChange = model::setTrainingDataEnabled,
                        sampleCount = model::trainingSampleCount,
                        onDeleteAll = model::deleteAllTrainingSamples,
                        onClose = { showSettings = false },
                    )
                } else {
                    when (val current = state) {
                        is UiState.Idle ->
                            if (hasCamera) {
                                Box(Modifier.fillMaxSize()) {
                                    CaptureScreen(onImage = model::recognize)
                                    Button(
                                        onClick = { showSettings = true },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            // Target SDK 35 draws content edge-to-edge by
                                            // default: without this, this button's tap
                                            // target sits under the status bar / system
                                            // gesture inset and is unreachable on a real
                                            // device (confirmed on the emulator - a plain
                                            // tap there never reaches the app).
                                            .statusBarsPadding()
                                            .padding(16.dp),
                                    ) { Text("Pengaturan") }
                                }
                            } else Centered("Izin kamera diperlukan")
                        is UiState.Working -> Centered("Membaca teks…")
                        is UiState.Done -> ResultScreen(
                            result = current.result,
                            trainingDataEnabled = trainingDataEnabled,
                            sampleSaveState = sampleSaveState,
                            onSaveWrongResult = {
                                val document = (current.result as? OcrResult.Success)?.document
                                if (document != null) {
                                    model.saveWrongResultSample(current.sourceUri, document)
                                }
                            },
                            onRetake = model::reset,
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Centered(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message) }
}
