package id.dotcode.braille.ocr.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.OcrResult
import id.dotcode.braille.ocr.model.TextBlock

/** The per-block metadata dump and stage timings: essential for diagnosing real defects. */
@Composable
fun DetailScreen(model: OcrViewModel) {
    val strings = LocalStrings.current
    val nav = model.navigator
    val state by model.state.collectAsState()
    val document = ((state as? UiState.Done)?.result as? OcrResult.Success)?.document
    if (document == null) {
        LaunchedEffect(Unit) { nav.pop() }
        Box(Modifier.fillMaxSize().background(Brl.Paper50))
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Brl.Paper50),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(strings.detailView, onBack = { nav.pop() }, backDescription = strings.back)
            Spacer(Modifier.height(8.dp))
        }
        item { TimingCard(document, strings) }
        items(document.blocks) { BlockCard(it, strings) }
    }
}

private val Mono = FontFamily.Monospace

@Composable
private fun TimingCard(document: OcrDocument, strings: Strings) {
    val t = document.timings
    BrlCard(color = Brl.Ink800, elevated = false) {
        Text(
            "${t.totalMs} ms  •  ${document.blocks.size} ${strings.blocksLabel}  •  " +
                "${document.columnCount} ${strings.columnsLabel}  •  skew ${"%.1f".format(document.skewDeg)}°",
            color = Brl.Vanila200,
            fontFamily = Urbanist,
            fontSize = 15.sp,
            style = BrlText.Label,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "decode ${t.decodeMs} / pre ${t.preprocessMs} / ocr ${t.recognizeMs} / " +
                "tess ${t.secondReadMs} / struct ${t.structureMs} / ${strings.correctionsLabel} ${t.correctMs} ms",
            color = Brl.Ink200,
            fontFamily = Mono,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun BlockCard(block: TextBlock, strings: Strings) {
    BrlCard(
        elevated = false,
        border = BorderStroke(1.dp, Brl.Paper200),
        contentPadding = PaddingValues(16.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            "#${block.id}  ${block.role}  ${strings.columnShort} ${block.columnIndex}  " +
                "indent ${block.indentLevel}  ${block.alignment}  ×${"%.2f".format(block.relativeTextHeight)}",
            color = Brl.Ink500,
            fontFamily = Mono,
            fontSize = 11.sp,
        )
        block.marker?.let {
            Text("${strings.markerLabel}: $it", style = BrlText.Caption, color = Brl.Alice700)
        }
        if (block.corrections.isNotEmpty()) {
            Text(
                "${strings.correctionsLabel}: " + block.corrections.joinToString { "${it.original} → ${it.corrected}" },
                style = BrlText.Caption,
                color = Brl.Vanila700,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(block.text, style = BrlText.Body, color = Brl.Ink900)
    }
}
