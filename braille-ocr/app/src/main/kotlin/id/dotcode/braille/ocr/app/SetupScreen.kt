package id.dotcode.braille.ocr.app

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Whether a screen reader (TalkBack) is driving the phone. Used to pick accessibility
 * defaults and to decide whether the app speaks status itself or leaves it to TalkBack.
 */
@Composable
fun screenReaderOn(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * The first-run setup: language and the help a student wants. Everything useful is on by
 * default, and anything that sends data anywhere stays off and lives in Pengaturan.
 */
@Composable
fun SetupScreen(model: OcrViewModel) {
    val strings = LocalStrings.current
    val language by model.language.collectAsState()
    val assist by model.assist.collectAsState()
    val readerOn = screenReaderOn()

    // With TalkBack running, a student is reading by ear: start scans in Read Mode.
    var applied by rememberSaveable { mutableStateOf(false) }
    if (readerOn && !applied) {
        applied = true
        if (assist.afterScan == AppPrefs.AfterScan.RESULT) {
            model.setAssist(assist.copy(afterScan = AppPrefs.AfterScan.READ_MODE))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brl.Paper50)
            .verticalScroll(rememberScrollState())
            .padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            Text(strings.setupTitle, style = BrlText.H2, color = Brl.Ink900, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(strings.setupSubtitle, style = BrlText.Body, color = Brl.Ink600)
        }

        BrlCard {
            SectionTitle(strings.languageCard)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("🇮🇩 Indonesia", language == AppPrefs.LANG_ID, Modifier.weight(1f)) {
                    model.setLanguage(AppPrefs.LANG_ID)
                }
                ChoiceChip("🇬🇧 English", language == AppPrefs.LANG_EN, Modifier.weight(1f)) {
                    model.setLanguage(AppPrefs.LANG_EN)
                }
            }
        }

        BrlCard {
            SectionTitle(strings.setupHelpTitle)
            Spacer(Modifier.height(4.dp))
            Text(strings.setupHelpDesc, style = BrlText.BodySmall, color = Brl.Ink500)
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                strings.guidedCapture, assist.guidedCapture,
                { model.setAssist(assist.copy(guidedCapture = it)) },
                description = strings.guidedCaptureDesc,
            )
            ToggleRow(
                strings.spokenStatus, assist.spokenStatus,
                { model.setAssist(assist.copy(spokenStatus = it)) },
                description = strings.spokenStatusDesc,
            )
            ToggleRow(
                strings.hapticsTitle, assist.haptics,
                { model.setAssist(assist.copy(haptics = it)) },
                description = strings.hapticsDesc,
            )
        }

        BrlCard {
            SectionTitle(strings.afterScanTitle)
            Spacer(Modifier.height(4.dp))
            Text(strings.afterScanDesc, style = BrlText.BodySmall, color = Brl.Ink500)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppPrefs.AfterScan.entries.forEach { option ->
                    ChoiceChip(
                        strings.afterScanOption(option),
                        assist.afterScan == option,
                        Modifier.fillMaxWidth(),
                    ) { model.setAssist(assist.copy(afterScan = option)) }
                }
            }
        }

        Text(strings.setupAdvancedNote, style = BrlText.BodySmall, color = Brl.Ink500)

        BrlButton(strings.setupContinue, model::completeSetup, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}
