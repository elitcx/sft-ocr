package id.dotcode.braille.ocr.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Pengaturan: the one place the opt-in toggle, the honest privacy explanation, and the
 * "inspectable and deletable" requirement all live. Every string here is Indonesian, matching
 * the rest of the app.
 */
@Composable
fun SettingsScreen(
    trainingDataEnabled: Boolean,
    onTrainingDataEnabledChange: (Boolean) -> Unit,
    sampleCount: suspend () -> Int,
    onDeleteAll: suspend () -> Int,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var count by remember { mutableIntStateOf(0) }
    var showConfirmDelete by remember { mutableStateOf(false) }
    var lastDeletedMessage by remember { mutableStateOf<String?>(null) }

    // Recomputed whenever the toggle or a deletion changes what is actually on disk, not
    // just once at first composition — a stale count would defeat the "inspectable" promise.
    LaunchedEffect(trainingDataEnabled, lastDeletedMessage) {
        count = sampleCount()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        Text("Pengaturan", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Bantu Tingkatkan Akurasi", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Jika diaktifkan, kamu bisa menyimpan foto dan hasil teks di perangkat " +
                        "ini saat hasil bacaan salah, untuk membantu perbaikan akurasi di " +
                        "masa depan. Semua data HANYA tersimpan di perangkat ini — tidak " +
                        "pernah diunggah atau dikirim ke internet. Fitur ini mati secara " +
                        "default dan bisa kamu matikan kapan saja.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Switch(
                checked = trainingDataEnabled,
                onCheckedChange = onTrainingDataEnabledChange,
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))

        Text("Data Tersimpan", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "$count sampel tersimpan di perangkat ini.",
            style = MaterialTheme.typography.bodyMedium,
        )
        lastDeletedMessage?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { showConfirmDelete = true },
            enabled = count > 0,
        ) { Text("Hapus Semua Sampel") }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose) { Text("Tutup") }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Hapus semua sampel?") },
            text = { Text("Tindakan ini akan menghapus $count sampel yang tersimpan di perangkat ini secara permanen.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDelete = false
                    scope.launch {
                        val deleted = onDeleteAll()
                        lastDeletedMessage = "$deleted sampel telah dihapus."
                    }
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) { Text("Batal") }
            },
        )
    }
}
