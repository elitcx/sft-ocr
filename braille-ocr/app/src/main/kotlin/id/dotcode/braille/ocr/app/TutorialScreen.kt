@file:OptIn(ExperimentalLayoutApi::class)

package id.dotcode.braille.ocr.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val SPEECH_KEY = "tutorial"

/**
 * A short, hands-on tutorial: what the app does, how to hold the phone, how Read Mode moves
 * word by word, and what the vibrations mean. Every step can be tried, and the whole thing
 * can be skipped or replayed later from Pengaturan.
 */
@Composable
fun TutorialScreen(model: OcrViewModel) {
    val strings = LocalStrings.current
    val reading by model.reading.collectAsState()
    var step by rememberSaveable { mutableIntStateOf(0) }
    val steps = remember(strings) {
        listOf(
            strings.tutorialOverviewTitle to strings.tutorialOverviewBody,
            strings.tutorialHoldTitle to strings.tutorialHoldBody,
            strings.tutorialReadTitle to strings.tutorialReadBody,
            strings.tutorialFeedbackTitle to strings.tutorialFeedbackBody,
        )
    }
    val (title, body) = steps[step]

    fun speak(text: String) {
        model.speech.speak(SPEECH_KEY, text, voiceLocale(reading.voiceLanguage), reading.speechRate)
    }
    DisposableEffect(Unit) { onDispose { model.speech.stop(SPEECH_KEY) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brl.Paper50)
            .padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.tutorialStep(step + 1, steps.size),
                style = BrlText.Caption,
                color = Brl.Ink500,
                modifier = Modifier.weight(1f),
            )
            BrlButton(strings.skip, model::completeTutorial, style = BrlButtonStyle.Outline, mini = true)
        }
        StepDots(step, steps.size)

        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260))) togetherWith
                        fadeOut(tween(120))
                },
                label = "tutorialStep",
            ) { index ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        steps[index].first,
                        style = BrlText.H2,
                        color = Brl.Ink900,
                        modifier = Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    Text(steps[index].second, style = BrlText.Body, color = Brl.Ink600)
                    when (index) {
                        2 -> ReadModePractice(model)
                        3 -> FeedbackPractice(model)
                        else -> Unit
                    }
                    BrlButton(
                        strings.listen,
                        onClick = { speak("${steps[index].first}. ${steps[index].second}") },
                        style = BrlButtonStyle.Outline,
                        icon = R.drawable.ic_volume_up,
                        mini = true,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) {
                BrlButton(
                    strings.previous,
                    onClick = { step-- },
                    style = BrlButtonStyle.Outline,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            BrlButton(
                if (step == steps.lastIndex) strings.tutorialFinish else strings.next,
                onClick = { if (step == steps.lastIndex) model.completeTutorial() else step++ },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StepDots(step: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .size(width = if (index == step) 24.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (index == step) Brl.Ink900 else Brl.Ink200),
            )
        }
    }
}

/** Step 3: the same controls Read Mode uses, on one sample sentence. */
@Composable
private fun ReadModePractice(model: OcrViewModel) {
    val strings = LocalStrings.current
    val reading by model.reading.collectAsState()
    val words = remember(strings) { ReadingText.of(strings.tutorialSampleSentence).words }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val word = words[index.coerceIn(words.indices)]
    val cells = remember(word.text) { Braille.cellsFor(word.text) }

    BrlCard(color = Brl.Ink800, elevated = false) {
        Text(
            strings.wordOf(index + 1, words.size),
            style = BrlText.Caption,
            color = Brl.Ink400,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            cells.forEach { cell ->
                BrailleCellView(dots = cell.dots, dotSize = 14.dp, gap = 7.dp, inactiveColor = Brl.Ink700, glow = true)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            word.text,
            style = BrlText.Title,
            color = Brl.Paper0,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrlButton(
                strings.previous,
                onClick = {
                    if (index > 0) {
                        index--
                        model.haptics.tick()
                    }
                },
                style = BrlButtonStyle.OutlineOnDark,
                enabled = index > 0,
                mini = true,
                modifier = Modifier.weight(1f),
            )
            BrlButton(
                strings.listen,
                onClick = {
                    model.speech.speak(SPEECH_KEY, word.spoken, voiceLocale(reading.voiceLanguage), reading.speechRate)
                },
                mini = true,
                modifier = Modifier.weight(1f),
            )
            BrlButton(
                strings.next,
                onClick = {
                    if (index < words.lastIndex) {
                        index++
                        model.haptics.tick()
                    }
                },
                style = BrlButtonStyle.OutlineOnDark,
                enabled = index < words.lastIndex,
                mini = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Step 4: play the three patterns so they are recognizable later. */
@Composable
private fun FeedbackPractice(model: OcrViewModel) {
    val strings = LocalStrings.current
    val assist by model.assist.collectAsState()
    BrlCard(elevated = false, contentPadding = PaddingValues(16.dp), shape = RoundedCornerShape(20.dp)) {
        if (model.haptics.level == Haptics.Level.NONE) {
            Text(strings.hapticsUnavailable, style = BrlText.BodySmall, color = Brl.Ink500)
        } else if (!assist.haptics) {
            Text(strings.hapticsOff, style = BrlText.BodySmall, color = Brl.Ink500)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrlButton(strings.trySuccess, model.haptics::success, style = BrlButtonStyle.Success, mini = true)
                BrlButton(strings.tryFailure, model.haptics::failure, style = BrlButtonStyle.Danger, mini = true)
                BrlButton(strings.tryTick, model.haptics::tick, style = BrlButtonStyle.Outline, mini = true)
            }
        }
    }
}
