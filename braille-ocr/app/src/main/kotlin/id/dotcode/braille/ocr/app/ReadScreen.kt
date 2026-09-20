@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package id.dotcode.braille.ocr.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import id.dotcode.braille.ocr.model.OcrResult

private const val SPEECH_KEY = "read"

/** Separate from [SPEECH_KEY] so the resume notice is not cut off by the first word. */
private const val RESUME_KEY = "read-resume"

/** How long the student must settle on a word before it is worth writing to disk. */
private const val POSITION_SAVE_DELAY_MS = 1_200L

@Composable
fun ReadScreen(model: OcrViewModel) {
    val strings = LocalStrings.current
    val nav = model.navigator
    val state by model.state.collectAsState()
    val done = state as? UiState.Done
    val success = done?.result as? OcrResult.Success
    if (success == null) {
        LaunchedEffect(Unit) { nav.pop() }
        Box(Modifier.fillMaxSize().background(Brl.Ink900))
        return
    }

    val text = remember(success.document) { ReadingText.of(TextExport.toPlainText(success.document)) }
    // Reopening a scan continues where it was left off. resumeIndex holds the two rules
    // worth stating: a page already read to the end starts over, and a position that no
    // longer fits the text (the document can be re-corrected between readings) is dropped.
    val resumeAt = remember(done.historyId, text) {
        resumeIndex(model.readingPositionOf(done.historyId), text.words.size)
    }
    var index by rememberSaveable(done.historyId) { mutableIntStateOf(resumeAt) }
    var forward by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    val reading by model.reading.collectAsState()
    val latestReading by rememberUpdatedState(reading)
    val haptics = LocalHapticFeedback.current

    val go: (Int) -> Unit = { target ->
        if (target in text.words.indices && target != index) {
            forward = target > index
            index = target
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val latestGo by rememberUpdatedState(go)
    val latestIndex by rememberUpdatedState(index)

    fun speakWord(word: ReadingText.Word) {
        model.speech.speak(SPEECH_KEY, word.spoken, voiceLocale(latestReading.voiceLanguage), latestReading.speechRate)
    }

    // Each arrival on a word (including the first) is spoken and/or sent, per the settings.
    LaunchedEffect(index, text) {
        val word = text.words.getOrNull(index) ?: return@LaunchedEffect
        if (latestReading.autoSpeak) speakWord(word)
        if (latestReading.autoSend) model.sendWord(word.spoken)
    }
    // Saving on every word would rewrite the meta file a few hundred times per page, so
    // the position is written only once the student settles. Moving on cancels the pending
    // save; onDispose below covers closing the page before the delay elapses.
    val historyId = done.historyId
    if (historyId != null) {
        LaunchedEffect(index, historyId) {
            delay(POSITION_SAVE_DELAY_MS)
            model.rememberReadingPosition(historyId, index)
        }
    }
    val latestSavedIndex by rememberUpdatedState(index)
    DisposableEffect(Unit) {
        onDispose {
            model.speech.stop(SPEECH_KEY)
            model.clearWordSendState()
            if (historyId != null) model.rememberReadingPosition(historyId, latestSavedIndex)
        }
    }

    // A student who cannot see the screen and is dropped into the middle of a page has no
    // way to tell a resumed read from a broken one, so the app says which it is.
    LaunchedEffect(done.historyId) {
        if (resumeAt > 0) {
            model.speech.speak(
                RESUME_KEY,
                strings.resumedAt(resumeAt + 1, text.words.size),
                voiceLocale(latestReading.voiceLanguage),
                latestReading.speechRate,
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brl.Ink900)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Brl.Gutter)
            .padding(top = 12.dp, bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrlIconButton(R.drawable.ic_close, strings.close, { nav.pop() }, tint = Brl.Paper0)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (text.words.isNotEmpty()) {
                    Tag(strings.wordOf(index + 1, text.words.size), tone = TagTone.Dark)
                }
            }
            BrlIconButton(R.drawable.ic_settings, strings.readSettings, { showSettings = true }, tint = Brl.Paper0)
        }

        if (text.words.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(strings.readEmpty, style = BrlText.Body, color = Brl.Ink200, textAlign = TextAlign.Center)
            }
            return@Column
        }

        val word = text.words[index]
        val sentence = text.sentences[word.sentence]
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var total = 0f
                    val threshold = 56.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total <= -threshold) latestGo(latestIndex + 1)
                            else if (total >= threshold) latestGo(latestIndex - 1)
                            total = 0f
                        },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            total += dx
                        },
                    )
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(strings.nextWordAction) { latestGo(latestIndex + 1); true },
                        CustomAccessibilityAction(strings.previousWordAction) { latestGo(latestIndex - 1); true },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    val sign = if (forward) 1 else -1
                    (slideInHorizontally(tween(260)) { sign * it / 4 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(200)) { -sign * it / 4 } + fadeOut(tween(160)))
                },
                label = "cells",
            ) { shownIndex ->
                LargeCells(text.words[shownIndex].text)
            }
            Spacer(Modifier.height(40.dp))
            HighlightedSentence(
                sentence = sentence.text,
                sentenceIndex = word.sentence,
                start = word.start,
                end = word.end,
                modifier = Modifier.heightIn(max = 220.dp),
            )
            Spacer(Modifier.height(20.dp))
            WordSendIndicator(model, visible = reading.autoSend)
        }

        Text(
            strings.swipeHint,
            style = BrlText.Caption.copy(fontWeight = FontWeight.Normal),
            color = Brl.Ink400,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val compact = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            BrlButton(
                strings.previous, { go(index - 1) },
                style = BrlButtonStyle.OutlineOnDark,
                enabled = index > 0,
                modifier = Modifier.weight(1f),
                contentPadding = compact, fontSize = 15.sp,
            )
            BrlButton(
                strings.listen, { speakWord(word) },
                icon = R.drawable.ic_volume_up,
                modifier = Modifier.weight(1f),
                contentPadding = compact, fontSize = 15.sp,
            )
            BrlButton(
                strings.next, { go(index + 1) },
                style = BrlButtonStyle.OutlineOnDark,
                enabled = index < text.words.lastIndex,
                modifier = Modifier.weight(1f),
                contentPadding = compact, fontSize = 15.sp,
            )
        }
    }

    if (showSettings) {
        ReadSettingsSheet(
            reading = reading,
            onChange = model::setReading,
            onStartOver = {
                go(0)
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
}

/** `.large-braille-cell`s for the current word, shrinking so long words still fit. */
@Composable
private fun LargeCells(word: String) {
    val strings = LocalStrings.current
    val cells = remember(word) { Braille.cellsFor(word) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = strings.brailleFor(word)
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        // A cell is 2 dots + half a dot of gap wide, with 0.8 dot between cells; a little
        // slack keeps pixel rounding from wrapping the last cell onto its own row.
        val fit: Dp = maxWidth * 0.96f / (3.3f * cells.size - 0.8f).coerceAtLeast(2.5f)
        val dot = min(24.dp, fit).coerceAtLeast(10.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(dot * 0.8f, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(dot),
        ) {
            cells.forEach { cell ->
                BrailleCellView(
                    dots = cell.dots,
                    dotSize = dot,
                    gap = dot / 2,
                    inactiveColor = Brl.Ink700,
                    glow = true,
                )
            }
        }
    }
}

/** `.reading-sentence` with the current word in a highlight that glides between words. */
@Composable
private fun HighlightedSentence(
    sentence: String,
    sentenceIndex: Int,
    start: Int,
    end: Int,
    modifier: Modifier = Modifier,
) {
    var layout by remember(sentenceIndex) { mutableStateOf<TextLayoutResult?>(null) }
    val highlight = remember(sentenceIndex) { Animatable(Rect.Zero, Rect.VectorConverter) }
    val scroll = rememberScrollState()
    var viewport by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val padX = with(density) { 8.dp.toPx() }
    val padY = with(density) { 4.dp.toPx() }

    LaunchedEffect(layout, start, end) {
        val result = layout ?: return@LaunchedEffect
        if (end > result.layoutInput.text.length) return@LaunchedEffect
        val target = result.getPathForRange(start, end).getBounds()
        if (highlight.value == Rect.Zero) {
            highlight.snapTo(target)
        } else {
            highlight.animateTo(target, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    LaunchedEffect(layout, start, end, viewport) {
        val result = layout ?: return@LaunchedEffect
        if (end > result.layoutInput.text.length || viewport <= 0f) return@LaunchedEffect
        val target = result.getPathForRange(start, end).getBounds()
        val top = target.top - padY
        val bottom = target.bottom + padY
        if (top < scroll.value || bottom > scroll.value + viewport) {
            scroll.animateScrollTo((top - viewport / 3).toInt().coerceAtLeast(0))
        }
    }

    val annotated = remember(sentence, start, end) {
        buildAnnotatedString {
            append(sentence.substring(0, start))
            withStyle(SpanStyle(color = Brl.Paper0)) { append(sentence.substring(start, end)) }
            append(sentence.substring(end))
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { viewport = it.height.toFloat() }
            .verticalScroll(scroll),
    ) {
        Text(
            annotated,
            style = BrlText.H2.copy(fontWeight = FontWeight.SemiBold, lineHeight = 38.sp),
            color = Brl.Ink400,
            textAlign = TextAlign.Center,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .drawBehind {
                    val rect = highlight.value
                    if (rect != Rect.Zero) {
                        drawRoundRect(
                            color = Brl.Ink700,
                            topLeft = Offset(rect.left - padX, rect.top - padY),
                            size = Size(rect.width + padX * 2, rect.height + padY * 2),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                        )
                    }
                }
                .semantics { heading() },
        )
    }
}

@Composable
private fun WordSendIndicator(model: OcrViewModel, visible: Boolean) {
    val strings = LocalStrings.current
    val state by model.wordSendState.collectAsState()
    AnimatedVisibility(visible && state != WordSendState.Idle, enter = fadeIn(), exit = fadeOut()) {
        when (val current = state) {
            WordSendState.Sending -> Tag(strings.sendingWord, tone = TagTone.Dark, icon = R.drawable.ic_bluetooth_searching)
            WordSendState.Sent -> Tag(strings.sentWord, tone = TagTone.Dark, icon = R.drawable.ic_bluetooth_connected)
            WordSendState.NotReady -> Tag(strings.padNotReady, tone = TagTone.Vanila, icon = R.drawable.ic_bluetooth_disabled)
            is WordSendState.Failed -> Tag(
                "${strings.sendFailed}: ${strings.sendError(current.error)}".take(80),
                tone = TagTone.Alert,
                icon = R.drawable.ic_error,
            )
            WordSendState.Idle -> Unit
        }
    }
}

@Composable
private fun ReadSettingsSheet(
    reading: AppPrefs.Reading,
    onChange: (AppPrefs.Reading) -> Unit,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brl.Paper0,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = Brl.Gutter, end = Brl.Gutter, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.readSettings, style = BrlText.Title, color = Brl.Ink900, modifier = Modifier.semantics { heading() })
            ToggleRow(
                strings.autoSpeak, reading.autoSpeak,
                { onChange(reading.copy(autoSpeak = it)) },
                description = strings.autoSpeakDesc,
            )
            ToggleRow(
                strings.autoSend, reading.autoSend,
                { onChange(reading.copy(autoSend = it)) },
                description = strings.autoSendDesc,
            )
            // Lives here rather than on a button of its own: the reading screen is driven by
            // swipes, and one more tappable target is one more thing to find by accident.
            BrlButton(
                strings.startOver,
                onStartOver,
                modifier = Modifier.fillMaxWidth(),
                style = BrlButtonStyle.Outline,
            )
        }
    }
}
