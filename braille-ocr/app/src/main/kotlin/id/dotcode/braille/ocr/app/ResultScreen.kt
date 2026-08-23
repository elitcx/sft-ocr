package id.dotcode.braille.ocr.app

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.dotcode.braille.ocr.model.FailureReason
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.OcrResult
import id.dotcode.braille.ocr.model.TextBlock

@Composable
fun ResultScreen(result: OcrResult, onRetake: () -> Unit) {
    when (result) {
        is OcrResult.Failure -> FailureView(result.reason, onRetake)
        is OcrResult.Success -> DocumentView(result.document, onRetake)
    }
}

@Composable
private fun FailureView(reason: FailureReason, onRetake: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(reason.toIndonesian(), style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onRetake, modifier = Modifier.padding(top = 16.dp)) { Text("Coba Lagi") }
    }
}

/** Actionable Indonesian feedback, because "TooBlurry" helps nobody in a classroom. */
private fun FailureReason.toIndonesian(): String = when (this) {
    FailureReason.TooBlurry -> "Foto kurang tajam. Tahan perangkat lebih stabil, lalu coba lagi."
    FailureReason.TooDark -> "Cahaya kurang. Dekatkan ke sumber cahaya, lalu coba lagi."
    FailureReason.NoTextFound -> "Tidak ada teks yang terbaca. Pastikan lembar kerja tampak penuh."
    FailureReason.ModelUnavailable -> "Mesin pengenalan teks tidak tersedia."
    FailureReason.Cancelled -> "Proses dibatalkan."
}

@Composable
private fun DocumentView(document: OcrDocument, onRetake: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "${document.timings.totalMs} ms  •  ${document.blocks.size} blok  •  " +
                "${document.columnCount} kolom  •  skew ${"%.1f".format(document.skewDeg)}°",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "decode ${document.timings.decodeMs} / pre ${document.timings.preprocessMs} / " +
                "ocr ${document.timings.recognizeMs} / struct ${document.timings.structureMs} ms",
            style = MaterialTheme.typography.labelSmall,
        )

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Button(onClick = onRetake) { Text("Foto Lagi") }
            Button(
                onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, document.toJson())
                    }
                    context.startActivity(Intent.createChooser(share, "Ekspor JSON"))
                },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Ekspor JSON") }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(document.blocks) { block -> BlockCard(block) }
        }
    }
}

@Composable
private fun BlockCard(block: TextBlock) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "#${block.id}  ${block.role}  kol ${block.columnIndex}  " +
                    "indent ${block.indentLevel}  ${block.alignment}  " +
                    "×${"%.2f".format(block.relativeTextHeight)}",
                style = MaterialTheme.typography.labelSmall,
            )
            block.marker?.let {
                Text("penanda: $it", style = MaterialTheme.typography.labelMedium)
            }
            Text(block.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
