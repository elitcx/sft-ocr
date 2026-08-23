package id.dotcode.braille.ocr.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val model: OcrViewModel = viewModel()
                val state by model.state.collectAsState()

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

                when (val current = state) {
                    is UiState.Idle ->
                        if (hasCamera) CaptureScreen(onImage = model::recognize)
                        else Centered("Izin kamera diperlukan")
                    is UiState.Working -> Centered("Membaca teks…")
                    is UiState.Done -> ResultScreen(current.result, onRetake = model::reset)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Centered(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message) }
}
