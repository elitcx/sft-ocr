@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package id.dotcode.braille.ocr.app

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import id.dotcode.braille.ocr.model.Alignment as BlockAlignment
import id.dotcode.braille.ocr.model.BlockRole
import id.dotcode.braille.ocr.model.FailureReason
import id.dotcode.braille.ocr.model.OcrDocument
import id.dotcode.braille.ocr.model.OcrResult
import id.dotcode.braille.ocr.model.TextBlock
import kotlinx.coroutines.launch

internal const val RESULT_SPEECH_KEY = "result"
private const val COLLAPSED_CHARS = 480
private const val MAX_EXPANDED_BLOCKS = 300
private const val BRAILLE_PREVIEW_WORDS = 6

@Composable
fun ResultScreen(model: OcrViewModel) {
    val state by model.state.collectAsState()
    val done = state as? UiState.Done
    if (done == null) {
        // Nothing to show (e.g. the scan was reset); leave rather than render an empty page.
        LaunchedEffect(Unit) { model.navigator.pop() }
        Box(Modifier.fillMaxSize().background(Brl.Paper50))
        return
    }
    when (val result = done.result) {
        is OcrResult.Failure -> FailureView(result.reason, done.sourceKind, model)
        is OcrResult.Success -> DocumentResult(done, result, model)
    }
}

@Composable
private fun FailureView(reason: FailureReason, kind: SourceKind, model: OcrViewModel) {
    val strings = LocalStrings.current
    val nav = model.navigator
    val context = LocalContext.current
    val isDocument = kind == SourceKind.DOCUMENT
    val pickDocument = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { model.importDocument(it, context.contentResolver.getType(it)) } }
    Column(
        Modifier
            .fillMaxSize()
            .background(Brl.Paper50)
            .padding(screenPadding()),
    ) {
        ScreenHeader(strings.resultTitle, onBack = { nav.pop() }, backDescription = strings.back) {
            Tag(strings.tagFailed, tone = TagTone.Alert)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(32.dp)).background(Brl.Alert100),
                contentAlignment = Alignment.Center,
            ) { BrlIcon(R.drawable.ic_error, tint = Brl.Alert700, size = 44.dp) }
            Spacer(Modifier.height(24.dp))
            Text(
                strings.failureTitle,
                style = BrlText.H2,
                color = Brl.Ink900,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isDocument) strings.documentFailure(reason) else strings.failure(reason),
                style = BrlText.Body,
                color = Brl.Ink600,
                textAlign = TextAlign.Center,
            )
        }
        if (isDocument) {
            BrlButton(
                strings.chooseAnotherDocument,
                onClick = { pickDocument.launch(arrayOf(DocumentTextExtractor.PDF_MIME, DocumentTextExtractor.DOCX_MIME)) },
                icon = R.drawable.ic_upload_file,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            BrlButton(
                strings.scanAgain,
                onClick = { nav.replaceTop(Route.Scan) },
                icon = R.drawable.ic_document_scanner,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DocumentResult(done: UiState.Done, result: OcrResult.Success, model: OcrViewModel) {
    val strings = LocalStrings.current
    val nav = model.navigator
    val context = LocalContext.current
    val sendState by model.esp32SendState.collectAsState()
    val padStatus by model.braillePad.status.collectAsState()
    val trainingEnabled by model.trainingDataEnabled.collectAsState()
    val sampleState by model.sampleSaveState.collectAsState()

    var showOriginal by rememberSaveable(done.historyId) { mutableStateOf(false) }
    // On by default: the demo depends on the highlighting being visible immediately.
    var highlightCorrections by rememberSaveable(done.historyId) { mutableStateOf(true) }
    var showShare by remember { mutableStateOf(false) }
    val uncorrected = result.uncorrected
    // Everything below - views, exports, the send - follows whichever text is on screen.
    val document = if (showOriginal && uncorrected != null) uncorrected else result.document
    val plainText = remember(document) { TextExport.toPlainText(document) }

    DisposableEffect(Unit) { onDispose { model.speech.stop(RESULT_SPEECH_KEY) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brl.Paper50)
            .verticalScroll(rememberScrollState())
            .padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(strings.resultTitle, onBack = { nav.pop() }, backDescription = strings.back) {
            Tag(strings.tagReady, tone = TagTone.Success)
        }

        TextCard(
            document = document,
            plainText = plainText,
            hasUncorrected = uncorrected != null,
            showOriginal = showOriginal,
            onShowOriginalChange = {
                model.speech.stop(RESULT_SPEECH_KEY)
                showOriginal = it
            },
            // The pre-correction document has no corrections to mark up.
            highlightCorrections = highlightCorrections && !showOriginal,
            model = model,
        )

        CorrectionStatus(
            corrected = result.document,
            gemini = done.correction,
            showOriginal = showOriginal,
            highlightCorrections = highlightCorrections,
            onHighlightCorrectionsChange = { highlightCorrections = it },
            onRetryGemini = model::retryGeminiCorrection,
        )

        PipelineCard(document = result.document, gemini = done.correction)

        BrailleOutputCard(plainText, onOpenReadMode = { nav.push(Route.Read) })

        SendSection(
            state = sendState,
            padReady = padStatus is BraillePad.Status.Ready,
            onSend = { model.sendToEsp32(document) },
            onOpenConnection = { nav.push(Route.Connect) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrlButton(
                strings.readMode,
                onClick = { nav.push(Route.Read) },
                style = BrlButtonStyle.Outline,
                icon = R.drawable.ic_visibility,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            )
            BrlButton(
                strings.share,
                onClick = { showShare = true },
                style = BrlButtonStyle.Dark,
                icon = R.drawable.ic_ios_share,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        ToolsCard(
            document = document,
            canSaveSample = trainingEnabled && done.sourceKind == SourceKind.CAMERA && !done.fromHistory,
            sampleState = sampleState,
            onSaveSample = {
                // A training sample is the recognizer's own output, uncorrected.
                model.saveWrongResultSample(done.sourceUri, result.uncorrected ?: result.document)
            },
            onDetail = { nav.push(Route.Detail) },
            onScanAgain = { nav.push(Route.Scan) },
        )
    }

    if (showShare) {
        ShareSheet(
            onDismiss = { showShare = false },
            onExportText = { shareText(context, document, strings.exportText) },
            onExportJson = { shareJson(context, document, strings.exportJson) },
        )
    }
}

@Composable
private fun TextCard(
    document: OcrDocument,
    plainText: String,
    hasUncorrected: Boolean,
    showOriginal: Boolean,
    onShowOriginalChange: (Boolean) -> Unit,
    highlightCorrections: Boolean,
    model: OcrViewModel,
) {
    val strings = LocalStrings.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val collapsedCount = remember(document) {
        var chars = 0
        document.blocks.indexOfFirst { block ->
            chars += block.text.length
            chars > COLLAPSED_CHARS
        }.let { if (it < 0) document.blocks.size else it + 1 }
    }
    val truncated = collapsedCount < document.blocks.size
    val shown = if (expanded || !truncated) document.blocks.take(MAX_EXPANDED_BLOCKS) else document.blocks.take(collapsedCount)

    BrlCard(accent = Brl.Alice500) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrlIcon(R.drawable.ic_article, tint = Brl.Alice500, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (showOriginal) strings.beforeCorrection.uppercase(strings.locale) else strings.originalText,
                style = BrlText.Overline,
                color = Brl.Ink500,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (hasUncorrected) {
                BrlButton(
                    if (showOriginal) strings.afterCorrection else strings.beforeCorrection,
                    onClick = { onShowOriginalChange(!showOriginal) },
                    style = BrlButtonStyle.Outline,
                    mini = true,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(tween(300))
                .then(
                    if (truncated && !expanded) {
                        Modifier.drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Brl.Paper0),
                                    startY = size.height - 48.dp.toPx(),
                                    endY = size.height,
                                ),
                            )
                        }
                    } else Modifier,
                ),
        ) {
            shown.forEachIndexed { index, block ->
                ReadingBlock(block, isFirst = index == 0, highlightCorrections = highlightCorrections)
            }
            if (expanded && document.blocks.size > MAX_EXPANDED_BLOCKS) {
                Text(
                    "+${document.blocks.size - MAX_EXPANDED_BLOCKS} ${strings.blocksLabel}",
                    style = BrlText.Caption,
                    color = Brl.Ink400,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
        if (truncated) {
            val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300), label = "chevron")
            Row(
                Modifier
                    .padding(top = 8.dp)
                    .clip(Brl.Pill)
                    .clickable { expanded = !expanded }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) strings.showLess else strings.showAll,
                    style = BrlText.Label.copy(fontWeight = FontWeight.Bold),
                    color = Brl.Alice700,
                )
                BrlIcon(R.drawable.ic_expand_more, tint = Brl.Alice700, size = 20.dp, modifier = Modifier.rotate(rotation))
            }
        }
        Spacer(Modifier.height(16.dp))
        AudioPlayer(plainText, model)
    }
}

/** `.audio-player`: text-to-speech of the whole page, with a live progress bar. */
@Composable
private fun AudioPlayer(text: String, model: OcrViewModel) {
    val strings = LocalStrings.current
    val availability by model.speech.availability.collectAsState()
    val languageMissing by model.speech.languageMissing.collectAsState()
    val playback by model.speech.playback.collectAsState()
    val reading by model.reading.collectAsState()
    val mine = playback?.takeIf { it.key == RESULT_SPEECH_KEY && it.length == text.length }
    val speaking = mine?.speaking == true
    val progress by animateFloatAsState(mine?.fraction ?: 0f, tween(250), label = "progress")
    val locale = voiceLocale(reading.voiceLanguage)
    val ready = availability == SpeechController.Availability.READY

    // Roughly 150 words a minute at normal rate.
    val totalSeconds = remember(text, reading.speechRate) {
        (wordCount(text) / (150f * reading.speechRate) * 60f).toInt().coerceAtLeast(1)
    }
    val remaining = if (mine != null && !mine.finished && mine.position > 0) {
        (totalSeconds * (1f - mine.fraction)).toInt()
    } else totalSeconds

    Row(
        Modifier
            .fillMaxWidth()
            .clip(Brl.Pill)
            .background(Brl.Paper100)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrlIconButton(
            if (speaking) R.drawable.ic_pause else R.drawable.ic_play_arrow,
            if (speaking) strings.pause else strings.play,
            enabled = ready,
            size = 40.dp,
            onClick = {
                when {
                    speaking -> model.speech.pause(RESULT_SPEECH_KEY)
                    mine != null && !mine.finished -> model.speech.resume(RESULT_SPEECH_KEY, locale, reading.speechRate)
                    else -> model.speech.speak(RESULT_SPEECH_KEY, text, locale, reading.speechRate)
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Brl.Ink900,
            trackColor = Brl.Ink200,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Spacer(Modifier.width(12.dp))
        Text(
            formatDuration(remaining),
            style = BrlText.Caption,
            color = Brl.Ink900,
            modifier = Modifier.padding(end = 10.dp),
        )
    }
    when {
        availability == SpeechController.Availability.UNAVAILABLE -> HelperText(strings.ttsUnavailable)
        mine?.failed == true -> HelperText(strings.ttsFailed)
        languageMissing -> HelperText(strings.ttsLanguageMissing)
    }
}

@Composable
private fun HelperText(text: String) {
    Text(text, style = BrlText.Caption, color = Brl.Ink500, modifier = Modifier.padding(top = 8.dp, start = 8.dp))
}

fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

fun voiceLocale(language: String): java.util.Locale =
    if (language == AppPrefs.LANG_EN) java.util.Locale.US else java.util.Locale.forLanguageTag("id-ID")

/**
 * The three models that ran, with what each cost, collapsed by default.
 *
 * The timings already existed on [OcrDocument.timings] and already rendered in DetailScreen,
 * but nobody opens Detail. Naming the models here is deliberate: "processing" tells a reader
 * nothing, while "ML Kit", "Tesseract LSTM" and "Gemini" say plainly that this is a
 * multi-model pipeline.
 */
@Composable
private fun PipelineCard(document: OcrDocument, gemini: GeminiCorrection) {
    val strings = LocalStrings.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val t = document.timings
    BrlCard(
        elevated = false,
        border = BorderStroke(1.dp, Brl.Paper200),
        contentPadding = PaddingValues(16.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrlIcon(R.drawable.ic_data_object, tint = Brl.Ink500, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(strings.howItWorks, style = BrlText.Label, color = Brl.Ink700, modifier = Modifier.weight(1f))
            Text("${t.totalMs} ms", style = BrlText.Caption, color = Brl.Ink500)
            Spacer(Modifier.width(6.dp))
            BrlIcon(
                R.drawable.ic_expand_more,
                tint = Brl.Ink400,
                size = 20.dp,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                StageRow(strings.stageMlKit, "${t.recognizeMs} ms")
                // Honest degradation: say the second reader was skipped rather than show 0 ms.
                StageRow(
                    strings.stageTesseract,
                    if (t.secondReadWords > 0) "${t.secondReadMs} ms \u00B7 ${strings.secondReaderWords(t.secondReadWords)}"
                    else strings.stageNotRun,
                )
                StageRow(strings.stageStructure, "${t.structureMs} ms")
                StageRow(strings.stageCorrection, "${t.correctMs} ms")
                StageRow(
                    strings.stageGemini,
                    when (gemini) {
                        is GeminiCorrection.Applied ->
                            if (gemini.changedBlocks == 0) strings.geminiNoChange
                            else strings.geminiChanged(gemini.changedBlocks)
                        is GeminiCorrection.Failed -> strings.geminiFailed
                        GeminiCorrection.Off -> strings.stageNotRun
                    },
                )
            }
        }
    }
}

@Composable
private fun StageRow(name: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(name, style = BrlText.BodySmall, color = Brl.Ink700, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(detail, style = BrlText.Caption, color = Brl.Ink500)
    }
}

@Composable
private fun CorrectionStatus(
    corrected: OcrDocument,
    gemini: GeminiCorrection,
    showOriginal: Boolean,
    highlightCorrections: Boolean,
    onHighlightCorrectionsChange: (Boolean) -> Unit,
    onRetryGemini: () -> Unit,
) {
    val strings = LocalStrings.current
    val offlineFixes = remember(corrected) { corrected.blocks.sumOf { it.corrections.size } }
    val tags = buildList {
        if (!showOriginal && offlineFixes > 0) add(strings.offlineFixes(offlineFixes))
        if (!showOriginal && gemini is GeminiCorrection.Applied) {
            add(if (gemini.changedBlocks == 0) strings.geminiNoChange else strings.geminiChanged(gemini.changedBlocks))
        }
    }
    if (tags.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { Tag(it, tone = TagTone.Info, icon = R.drawable.ic_spellcheck) }
        }
    }
    // Only offered when there is something to mark up, and never over the pre-correction text.
    if (!showOriginal && offlineFixes > 0) {
        Spacer(Modifier.height(8.dp))
        BrlButton(
            strings.showCorrections,
            onClick = { onHighlightCorrectionsChange(!highlightCorrections) },
            style = if (highlightCorrections) BrlButtonStyle.Primary else BrlButtonStyle.Outline,
            icon = R.drawable.ic_spellcheck,
            mini = true,
        )
    }
    if (gemini is GeminiCorrection.Failed) {
        NoticeCard(
            title = strings.geminiFailed,
            message = gemini.message,
            actionLabel = strings.retry,
            onAction = onRetryGemini,
        )
    }
}

@Composable
fun NoticeCard(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    tone: TagTone = TagTone.Alert,
) {
    val (background, border, content) = when (tone) {
        TagTone.Alert -> Triple(Brl.Alert100, Brl.Alert300, Brl.Alert700)
        TagTone.Vanila -> Triple(Brl.Vanila100, Brl.Vanila300, Brl.Vanila700)
        TagTone.Success -> Triple(Brl.Honeydew100, Brl.Honeydew300, Brl.Honeydew700)
        else -> Triple(Brl.Alice100, Brl.Alice300, Brl.Alice700)
    }
    BrlCard(
        color = background,
        elevated = false,
        border = BorderStroke(1.dp, border),
        contentPadding = PaddingValues(16.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            BrlIcon(if (tone == TagTone.Success) R.drawable.ic_check_circle else R.drawable.ic_error, tint = content, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = BrlText.Label.copy(fontWeight = FontWeight.Bold), color = content)
                Text(message, style = BrlText.BodySmall, color = content)
                if (actionLabel != null) {
                    Spacer(Modifier.height(10.dp))
                    BrlButton(actionLabel, onAction, style = BrlButtonStyle.Outline, mini = true)
                }
            }
        }
    }
}

@Composable
private fun BrailleOutputCard(plainText: String, onOpenReadMode: () -> Unit) {
    val strings = LocalStrings.current
    val words = remember(plainText) { ReadingText.of(plainText).words }
    val preview = words.take(BRAILLE_PREVIEW_WORDS)
    BrlCard(
        color = Brl.Ink800,
        elevated = false,
        contentPadding = PaddingValues(24.dp),
        onClick = onOpenReadMode,
        onClickLabel = strings.readMode,
    ) {
        Text(
            strings.brailleOutput,
            style = BrlText.Overline,
            color = Brl.Ink400,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.clearAndSetSemantics {
                contentDescription = strings.brailleFor(preview.joinToString(" ") { it.text })
            },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            preview.forEach { word -> BrailleWord(word.text) }
        }
        if (words.size > preview.size) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.moreWordsInReadMode(words.size - preview.size),
                    style = BrlText.Label,
                    color = Brl.Vanila200,
                    modifier = Modifier.weight(1f),
                )
                BrlIcon(R.drawable.ic_chevron_right, tint = Brl.Vanila200, size = 20.dp)
            }
        }
    }
}

@Composable
private fun BrailleWord(word: String) {
    val cells = remember(word) { Braille.cellsFor(word) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            word.uppercase(),
            color = Brl.Ink400,
            fontFamily = Urbanist,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            cells.forEach { cell ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BrailleCellView(
                        dots = cell.dots,
                        dotSize = 8.dp,
                        gap = 4.dp,
                        inactiveColor = Brl.Ink600,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brl.Ink700)
                            .padding(8.dp),
                    )
                    Text(
                        cell.label,
                        style = TextStyle(fontFamily = Urbanist, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                        color = Brl.Paper0,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SendSection(
    state: Esp32SendState,
    padReady: Boolean,
    onSend: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "send",
        ) { current ->
            when (current) {
                is Esp32SendState.Sent -> BrlButton(
                    strings.sent(current.bytes),
                    onClick = onSend,
                    style = BrlButtonStyle.Success,
                    icon = R.drawable.ic_check_circle,
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> BrlButton(
                    if (current == Esp32SendState.Sending) strings.sending else strings.sendToPad,
                    // Without a usable BraillePad the send can only fail; show how to set it up.
                    onClick = { if (padReady) onSend() else onOpenConnection() },
                    icon = R.drawable.ic_send,
                    loading = current == Esp32SendState.Sending,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        AnimatedVisibility(
            visible = state is Esp32SendState.Failed,
            enter = expandVertically(tween(250)) + fadeIn(tween(250)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
        ) {
            val error = (state as? Esp32SendState.Failed)?.error
            if (error != null) {
                NoticeCard(
                    title = strings.sendFailed,
                    message = strings.sendError(error),
                    actionLabel = strings.openConnection,
                    onAction = onOpenConnection,
                )
            }
        }
    }
}

@Composable
private fun ToolsCard(
    document: OcrDocument,
    canSaveSample: Boolean,
    sampleState: SampleSaveState,
    onSaveSample: () -> Unit,
    onDetail: () -> Unit,
    onScanAgain: () -> Unit,
) {
    val strings = LocalStrings.current
    var open by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(300), label = "tools")
    val words = remember(document) { wordCount(TextExport.toPlainText(document)) }
    BrlCard(elevated = false, border = BorderStroke(1.dp, Brl.Paper200), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrlIcon(R.drawable.ic_tune, tint = Brl.Ink500, size = 20.dp)
            Spacer(Modifier.width(12.dp))
            Text(strings.moreTools, style = BrlText.Label.copy(fontWeight = FontWeight.Bold), color = Brl.Ink900, modifier = Modifier.weight(1f))
            BrlIcon(R.drawable.ic_expand_more, tint = Brl.Ink500, modifier = Modifier.rotate(rotation))
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(300)) + fadeIn(tween(300)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(150)),
        ) {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    strings.resultStats(document.blocks.size, words, document.timings.totalMs),
                    style = BrlText.Caption,
                    color = Brl.Ink500,
                )
                ToolRow(R.drawable.ic_data_object, strings.detailView, strings.detailViewDesc, onDetail)
                if (canSaveSample) {
                    when (sampleState) {
                        SampleSaveState.Idle -> ToolRow(R.drawable.ic_flag, strings.markWrong, strings.markWrongDesc, onSaveSample)
                        SampleSaveState.Saved -> Tag(strings.sampleSaved, tone = TagTone.Success, icon = R.drawable.ic_check)
                        SampleSaveState.Failed -> Tag(strings.sampleFailed, tone = TagTone.Alert, icon = R.drawable.ic_error)
                    }
                }
                BrlButton(
                    strings.scanAgain,
                    onClick = onScanAgain,
                    style = BrlButtonStyle.Outline,
                    icon = R.drawable.ic_document_scanner,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ToolRow(icon: Int, title: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(Brl.Paper50)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Brl.Paper0),
            contentAlignment = Alignment.Center,
        ) { BrlIcon(icon, tint = Brl.Ink700, size = 20.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = BrlText.Label.copy(fontWeight = FontWeight.Bold), color = Brl.Ink900)
            Text(description, style = BrlText.Caption.copy(fontWeight = FontWeight.Normal), color = Brl.Ink500)
        }
        BrlIcon(R.drawable.ic_chevron_right, tint = Brl.Ink300, size = 20.dp)
    }
}

@Composable
private fun ShareSheet(onDismiss: () -> Unit, onExportText: () -> Unit, onExportJson: () -> Unit) {
    val strings = LocalStrings.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Brl.Paper0,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = Brl.Gutter, end = Brl.Gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.share, style = BrlText.Title, color = Brl.Ink900, modifier = Modifier.semantics { heading() })
            ToolRow(R.drawable.ic_text_snippet, strings.exportText, strings.exportTextDesc) { closeThen(onExportText) }
            ToolRow(R.drawable.ic_data_object, strings.exportJson, strings.exportJsonDesc) { closeThen(onExportJson) }
        }
    }
}

private fun shareText(context: Context, document: OcrDocument, title: String) {
    val file = TextExport.write(context.cacheDir, document)
    shareFile(context, file, "text/plain", title)
}

private fun shareJson(context: Context, document: OcrDocument, title: String) {
    // The document travels as a file URI, not as EXTRA_TEXT: a non-text MIME resolves to
    // targets that expect a stream, and a page's JSON can approach the Binder limit.
    val file = JsonExport.write(context.cacheDir, document.toJson())
    shareFile(context, file, "application/json", title)
}

private fun shareFile(context: Context, file: java.io.File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, title))
}

/**
 * The document rendered as prose, typography driven by [BlockRole].
 *
 * Role detection on a handheld photo is weakly evidenced, so most real documents come back
 * mostly PARAGRAPH. That has to read as clean continuous prose; hierarchy only appears where
 * it was actually detected.
 */
@Composable
fun ReadingBlock(block: TextBlock, isFirst: Boolean, highlightCorrections: Boolean = false) {
    val strings = LocalStrings.current
    val markerPrefix = if (block.marker != null) "${block.marker} " else ""
    val fullText = markerPrefix + block.text
    val isHeading = block.role == BlockRole.TITLE || block.role == BlockRole.HEADING

    // Offsets are found in block.text, then shifted past the marker the screen prepends.
    val spans = remember(block, highlightCorrections) {
        if (!highlightCorrections) emptyList()
        else CorrectionHighlighting.spansIn(block.text, block.corrections)
            .map { it.copy(start = it.start + markerPrefix.length, end = it.end + markerPrefix.length) }
    }
    var revealed by remember(block, spans) { mutableStateOf<CorrectionSpan?>(null) }
    var layout by remember(block, spans) { mutableStateOf<TextLayoutResult?>(null) }

    // A block with no corrections must cost nothing and render exactly as it always has.
    val rendered = remember(fullText, spans) {
        if (spans.isEmpty()) AnnotatedString(fullText) else buildAnnotatedString {
            append(fullText)
            spans.forEach {
                addStyle(SpanStyle(background = Brl.Vanila200, fontWeight = FontWeight.Bold), it.start, it.end)
            }
        }
    }

    // TalkBack gets the correction spoken, not a colour it cannot see.
    val spokenDescription = remember(fullText, spans, strings) {
        if (spans.isEmpty()) fullText
        else fullText + spans.joinToString(prefix = ". ", separator = ". ") {
            "${it.corrected} ${strings.correctionReadAs(it.original, it.corrected)}"
        }
    }

    val topPadding = when {
        isFirst -> 0.dp
        block.role == BlockRole.TITLE -> 8.dp
        block.role == BlockRole.HEADING -> 24.dp
        block.role == BlockRole.CAPTION -> 4.dp
        block.role == BlockRole.PAGE_NUMBER -> 20.dp
        else -> 12.dp
    }
    val textAlign = when (block.alignment) {
        BlockAlignment.CENTER -> TextAlign.Center
        BlockAlignment.RIGHT -> TextAlign.End
        BlockAlignment.LEFT -> TextAlign.Start
    }
    val (style, color) = when (block.role) {
        BlockRole.TITLE -> BrlText.H2 to Brl.Ink900
        BlockRole.HEADING -> BrlText.Title to Brl.Ink900
        BlockRole.QUESTION, BlockRole.LIST_ITEM, BlockRole.PARAGRAPH ->
            BrlText.Body.copy(fontSize = 18.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold) to Brl.Ink900
        BlockRole.CAPTION -> BrlText.BodySmall to Brl.Ink500
        BlockRole.PAGE_NUMBER -> BrlText.Caption to Brl.Ink400
    }

    Box(
        Modifier
            .fillMaxWidth()
            // indentLevel is real indentation here, not a debug number.
            .padding(top = topPadding, start = (block.indentLevel * 20).dp)
            // A block reads as one focusable unit under TalkBack; headings are navigable.
            .clearAndSetSemantics {
                if (isHeading) heading()
                contentDescription = spokenDescription
            },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = rendered,
                style = style,
                color = color,
                textAlign = textAlign,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (spans.isEmpty()) Modifier else Modifier.pointerInput(spans) {
                            detectTapGestures { position ->
                                val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                                val hit = spans.firstOrNull { offset >= it.start && offset < it.end }
                                revealed = if (hit == revealed) null else hit
                            }
                        },
                    ),
            )
            revealed?.let { span ->
                Spacer(Modifier.height(6.dp))
                Tag(
                    "${span.original} → ${span.corrected}",
                    tone = TagTone.Vanila,
                    icon = R.drawable.ic_spellcheck,
                )
            }
        }
    }
}
