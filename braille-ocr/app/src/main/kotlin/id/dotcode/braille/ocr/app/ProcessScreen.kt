package id.dotcode.braille.ocr.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private enum class StepState { PENDING, ACTIVE, DONE }

private data class Step(val label: String, val state: StepState)

@Composable
fun ProcessScreen(model: OcrViewModel) {
    val strings = LocalStrings.current
    val nav = model.navigator
    val state by model.state.collectAsState()

    // The last working state is kept so the checklist can finish on screen once the result is in.
    val lastWork = remember { mutableRef<UiState.Working?>(null) }
    (state as? UiState.Working)?.let { lastWork.value = it }
    val work = lastWork.value
    val finished = (state as? UiState.Done)?.result is id.dotcode.braille.ocr.model.OcrResult.Success
    val working = state is UiState.Working

    val steps = work?.let { stepsFor(it, finished, strings) }.orEmpty()

    val cancel = {
        val kind = (model.state.value as? UiState.Working)?.kind
        model.cancelWork()
        if (model.state.value is UiState.Idle) {
            if (kind == SourceKind.CAMERA) nav.replaceTop(Route.Scan) else nav.pop()
        }
    }
    // Once the result is in, back is swallowed for the brief hold before the result appears.
    BackHandler { if (working) cancel() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brl.Ink900)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Brl.Gutter)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrailleLoadingGrid(finished = finished)
            Spacer(Modifier.height(32.dp))
            AnimatedContent(
                targetState = finished,
                transitionSpec = { (fadeIn(tween(250)) + scaleIn(tween(250), 0.92f)) togetherWith fadeOut(tween(150)) },
                label = "title",
            ) { done ->
                Text(
                    if (done) strings.procDone else strings.procTitle,
                    style = BrlText.H2,
                    color = Brl.Paper0,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
            val progress by model.progress.collectAsState()
            ProgressSection(progress, finished)
            Spacer(Modifier.height(36.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                steps.forEach { StepRow(it) }
            }
        }
        BrlButton(
            strings.cancel,
            onClick = cancel,
            style = BrlButtonStyle.OutlineOnDark,
            enabled = working,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A real percentage and a time estimate, learned from how long this phone's scans take. */
@Composable
private fun ProgressSection(progress: WorkProgress?, finished: Boolean) {
    val strings = LocalStrings.current
    val target = if (finished) 1f else progress?.fraction ?: 0f
    val fraction by androidx.compose.animation.core.animateFloatAsState(
        target, tween(if (finished) 300 else 120), label = "progress",
    )
    val percent = (fraction * 100).toInt()
    val remainingSeconds = ((progress?.remainingMs ?: 0L) + 999) / 1000
    val status = when {
        finished -> strings.procDone
        progress == null -> strings.procHint
        remainingSeconds <= 1 -> strings.almostDone
        else -> strings.timeLeft(remainingSeconds.toInt())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(fraction, 0f..1f)
            },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                status,
                style = BrlText.BodySmall,
                color = Brl.Ink300,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$percent%",
                style = BrlText.H3,
                color = if (finished) Brl.Honeydew300 else Brl.Vanila200,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(Brl.Pill)
                .background(Brl.Ink700),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .clip(Brl.Pill)
                    .background(if (finished) SolidColor(Brl.Honeydew300) else Brl.GradAccent),
            )
        }
    }
}

private class Ref<T>(var value: T)

private fun <T> mutableRef(initial: T) = Ref(initial)

private fun stepsFor(work: UiState.Working, finished: Boolean, strings: Strings): List<Step> {
    fun state(order: Int, activeOrder: Int) = when {
        finished || order < activeOrder -> StepState.DONE
        order == activeOrder -> StepState.ACTIVE
        else -> StepState.PENDING
    }
    return if (work.kind == SourceKind.DOCUMENT) {
        listOf(
            Step(strings.stepOpenDocument, StepState.DONE),
            Step(strings.stepExtract, state(1, 1)),
            Step(strings.stepBraille, state(2, 1)),
        )
    } else {
        val active = when (work.stage) {
            WorkStage.RECOGNIZING, WorkStage.EXTRACTING -> 1
            WorkStage.CORRECTING -> 2
        }
        buildList {
            add(Step(strings.stepPrepareImage, StepState.DONE))
            add(Step(strings.stepOcr, state(1, active)))
            if (work.geminiPlanned) add(Step(strings.stepGemini, state(2, active)))
            add(Step(strings.stepBraille, state(3, active)))
        }
    }
}

@Composable
private fun StepRow(step: Step) {
    val color by animateColorAsState(
        if (step.state == StepState.PENDING) Brl.Ink300 else Brl.Paper0, tween(300), label = "stepText",
    )
    val iconColor by animateColorAsState(
        when (step.state) {
            StepState.PENDING -> Brl.Ink300
            StepState.ACTIVE -> Brl.Vanila300
            StepState.DONE -> Brl.Honeydew300
        },
        tween(300), label = "stepIcon",
    )
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        0.5f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "pulseAlpha",
    )
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = step.state,
            transitionSpec = { (fadeIn(tween(200)) + scaleIn(tween(250), 0.6f)) togetherWith fadeOut(tween(100)) },
            label = "stepIconSwap",
        ) { stepState ->
            BrlIcon(
                when (stepState) {
                    StepState.PENDING -> R.drawable.ic_radio_button_unchecked
                    StepState.ACTIVE -> R.drawable.ic_hourglass_empty
                    StepState.DONE -> R.drawable.ic_check_circle
                },
                tint = iconColor,
                modifier = Modifier.graphicsLayer { alpha = if (stepState == StepState.ACTIVE) pulseAlpha else 1f },
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            step.label,
            style = BrlText.Body.copy(
                fontWeight = if (step.state == StepState.ACTIVE) FontWeight.Bold else FontWeight.Normal,
            ),
            color = color,
        )
    }
}
