package id.dotcode.braille.ocr.app

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import id.dotcode.braille.ocr.model.Alignment
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.FailureReason
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.OcrResult
import id.dotcode.braille.ocr.model.TextBlock

@Composable
fun ResultScreen(
    result: OcrResult,
    trainingDataEnabled: Boolean,
    sampleSaveState: SampleSaveState,
    onSaveWrongResult: () -> Unit,
    onRetake: () -> Unit,
) {
    when (result) {
        is OcrResult.Failure -> FailureView(result.reason, onRetake)
        is OcrResult.Success -> DocumentView(
            document = result.document,
            trainingDataEnabled = trainingDataEnabled,
            sampleSaveState = sampleSaveState,
            onSaveWrongResult = onSaveWrongResult,
            onRetake = onRetake,
        )
    }
}

@Composable
private fun FailureView(reason: FailureReason, onRetake: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
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
private fun DocumentView(
    document: OcrDocument,
    trainingDataEnabled: Boolean,
    sampleSaveState: SampleSaveState,
    onSaveWrongResult: () -> Unit,
    onRetake: () -> Unit,
) {
    val context = LocalContext.current
    var isReadingView by remember { mutableStateOf(true) }

    // The header (HUD, actions, view toggle) is fixed chrome, so it gets statusBarsPadding
    // to clear the status bar. The block list below scrolls under the nav bar (edge-to-edge
    // looks intentional there) but carries matching bottom content padding via navBarsPadding
    // so the last item is never left underneath it.
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // Timing HUD stays visible in both views: the measured per-stage numbers are
            // what makes a slow run diagnosable, reading view or not.
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
                        // The document travels as a file URI, not as EXTRA_TEXT: a non-text
                        // MIME resolves to targets that expect a stream, and a page's JSON
                        // can approach the Binder limit an intent extra has to fit inside.
                        val file = JsonExport.write(context.cacheDir, document.toJson())
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Ekspor JSON"))
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Ekspor JSON") }
                Button(
                    onClick = {
                        val file = TextExport.write(context.cacheDir, document)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file,
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Ekspor Teks"))
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Ekspor Teks") }
            }

            if (trainingDataEnabled) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    when (sampleSaveState) {
                        SampleSaveState.Saved -> Text(
                            "Tersimpan untuk membantu perbaikan akurasi.",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        SampleSaveState.Failed -> Text(
                            "Gagal menyimpan sampel. Coba lagi.",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        SampleSaveState.Idle -> OutlinedButton(onClick = onSaveWrongResult) {
                            Text("Tandai Hasil Salah & Simpan")
                        }
                    }
                }
            }

            ViewToggle(isReadingView = isReadingView, onChange = { isReadingView = it })
        }

        Box(Modifier.weight(1f)) {
            if (isReadingView) ReadingView(document) else DebugView(document)
        }
    }
}

/**
 * Switches between the reading view (default, for a teacher checking the scan) and the
 * debug view (the original per-block metadata dump, kept for diagnosing real defects).
 */
@Composable
private fun ViewToggle(isReadingView: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToggleOption(
            label = "Bacaan",
            description = if (isReadingView) "Tampilan bacaan, sedang aktif" else "Beralih ke tampilan bacaan",
            selected = isReadingView,
            onClick = { onChange(true) },
        )
        ToggleOption(
            label = "Detail",
            description = if (!isReadingView) "Tampilan detail, sedang aktif" else "Beralih ke tampilan detail",
            selected = !isReadingView,
            onClick = { onChange(false) },
        )
    }
}

@Composable
private fun ToggleOption(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.semantics { contentDescription = description }
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

/**
 * The default screen: the document rendered as prose, typography driven by [BlockRole].
 *
 * Role detection on a handheld photo is weakly evidenced (no bold/size signal from ML Kit,
 * a deliberately conservative classifier), so most real documents come back mostly
 * PARAGRAPH. That has to read as clean continuous prose, not a broken-looking list of
 * "untagged" blocks - hierarchy only appears where it was actually detected.
 */
@Composable
private fun ReadingView(document: OcrDocument) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = blockListContentPadding()) {
        itemsIndexed(document.blocks) { index, block ->
            ReadingBlock(block, isFirst = index == 0)
        }
    }
}

/**
 * The block list is allowed to scroll under the navigation bar (edge-to-edge, matching the
 * rest of the screen), so it needs bottom content padding of at least the nav bar's height -
 * otherwise the last block ends up rendered partly underneath it and unreadable/untappable.
 */
@Composable
private fun blockListContentPadding(): PaddingValues {
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBarBottom + 16.dp)
}

@Composable
private fun ReadingBlock(block: TextBlock, isFirst: Boolean) {
    val fullText = if (block.marker != null) "${block.marker} ${block.text}" else block.text
    val isHeading = block.role == BlockRole.TITLE || block.role == BlockRole.HEADING

    val topPadding = when {
        isFirst -> 0.dp
        block.role == BlockRole.TITLE -> 8.dp
        block.role == BlockRole.HEADING -> 28.dp
        block.role == BlockRole.CAPTION -> 4.dp
        block.role == BlockRole.PAGE_NUMBER -> 24.dp
        else -> 16.dp
    }
    // indentLevel is real indentation here, not a debug number.
    val startPadding = (block.indentLevel * 20).dp
    val textAlign = when (block.alignment) {
        Alignment.CENTER -> TextAlign.Center
        Alignment.RIGHT -> TextAlign.End
        Alignment.LEFT -> TextAlign.Start
    }

    val baseStyle = MaterialTheme.typography.bodyLarge
    val (style, color) = when (block.role) {
        BlockRole.TITLE -> MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            lineHeight = 34.sp,
        ) to Color.Unspecified
        BlockRole.HEADING -> MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            lineHeight = 28.sp,
        ) to Color.Unspecified
        BlockRole.QUESTION, BlockRole.LIST_ITEM, BlockRole.PARAGRAPH -> baseStyle.copy(
            lineHeight = 26.sp,
        ) to Color.Unspecified
        BlockRole.CAPTION -> MaterialTheme.typography.bodySmall to
            MaterialTheme.colorScheme.onSurfaceVariant
        BlockRole.PAGE_NUMBER -> MaterialTheme.typography.labelSmall to
            MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = topPadding, start = startPadding)
            // A block reads as one focusable unit under TalkBack: the visible layout may
            // compose a marker and body as separate Text nodes, but semantics must not
            // fragment that into two stops. Headings additionally get heading() semantics
            // so TalkBack announces the role and users can navigate by heading.
            .clearAndSetSemantics {
                if (isHeading) heading()
                contentDescription = fullText
            },
    ) {
        Text(
            text = fullText,
            style = style,
            color = color,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The original per-block metadata dump: essential for diagnosing real defects. */
@Composable
private fun DebugView(document: OcrDocument) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = blockListContentPadding()) {
        items(document.blocks) { block -> BlockCard(block) }
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
