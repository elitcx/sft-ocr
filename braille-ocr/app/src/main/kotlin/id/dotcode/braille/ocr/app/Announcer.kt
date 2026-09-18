package id.dotcode.braille.ocr.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import id.dotcode.braille.ocr.model.OcrResult
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Says what the app is doing, so a student who cannot see the screen still knows. With
 * TalkBack running the text goes through TalkBack itself - talking over it would be worse
 * than useless - otherwise the app speaks it in its own voice.
 */
class Announcer(
    private val speakAloud: (String) -> Unit,
    private val announceToScreenReader: (String) -> Unit,
    private val screenReaderOn: () -> Boolean,
    private val enabled: () -> Boolean,
) {
    private var last: String? = null

    fun say(text: String) {
        if (!enabled() || text.isBlank() || text == last) return
        last = text
        if (screenReaderOn()) announceToScreenReader(text) else speakAloud(text)
    }

    fun reset() {
        last = null
    }
}

private const val SPEECH_KEY = "announcer"

/** How far apart spoken progress updates are: every quarter, and never within 5 seconds. */
private const val PROGRESS_STEP = 0.25f
private const val PROGRESS_MIN_GAP_MS = 5_000L

/**
 * Watches the scan and speaks its milestones - started, progress, outcome - and marks
 * success and failure with vibration. Lives at the root so it follows the scan across screens.
 */
@Composable
fun ScanAnnouncements(model: OcrViewModel) {
    val strings = LocalStrings.current
    val view = LocalView.current
    val assist by model.assist.collectAsState()
    val reading by model.reading.collectAsState()
    val readerOn = screenReaderOn()

    val currentAssist by rememberUpdatedState(assist)
    val currentReading by rememberUpdatedState(reading)
    val currentReader by rememberUpdatedState(readerOn)

    val announcer = remember {
        Announcer(
            speakAloud = { text ->
                model.speech.speak(
                    SPEECH_KEY, text, voiceLocale(currentReading.voiceLanguage), currentReading.speechRate,
                )
            },
            announceToScreenReader = { text -> view.announceForAccessibility(text) },
            screenReaderOn = { currentReader },
            enabled = { currentAssist.spokenStatus },
        )
    }
    DisposableEffect(Unit) { onDispose { model.speech.stop(SPEECH_KEY) } }

    LaunchedEffect(strings) {
        var spokenFraction = 0f
        var spokenAt = 0L
        combine(model.state, model.progress) { state, progress -> state to progress }
            .distinctUntilChanged()
            .collect { (state, progress) ->
                when (state) {
                    is UiState.Working -> {
                        val fraction = progress?.fraction ?: 0f
                        val step = (fraction / PROGRESS_STEP).toInt() * PROGRESS_STEP
                        val now = System.currentTimeMillis()
                        when {
                            step < PROGRESS_STEP -> {
                                announcer.say(strings.procTitle)
                                spokenFraction = 0f
                                spokenAt = now
                            }
                            step > spokenFraction && now - spokenAt >= PROGRESS_MIN_GAP_MS -> {
                                spokenFraction = step
                                spokenAt = now
                                val seconds = (((progress?.remainingMs ?: 0L) + 999) / 1000).toInt()
                                val left = if (seconds > 1) strings.timeLeft(seconds) else strings.almostDone
                                announcer.say("${(step * 100).toInt()}%. $left")
                            }
                        }
                    }
                    is UiState.Done -> {
                        spokenFraction = 0f
                        when (val result = state.result) {
                            is OcrResult.Success -> {
                                model.haptics.success()
                                val words = wordCount(TextExport.toPlainText(result.document))
                                announcer.say("${strings.procDone} ${strings.words(words)}.")
                            }
                            is OcrResult.Failure -> {
                                model.haptics.failure()
                                val message = if (state.sourceKind == SourceKind.DOCUMENT) {
                                    strings.documentFailure(result.reason)
                                } else {
                                    strings.failure(result.reason)
                                }
                                announcer.say("${strings.failureTitle}. $message")
                            }
                        }
                    }
                    UiState.Idle -> announcer.reset()
                }
            }
    }
}
