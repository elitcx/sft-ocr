package id.dotcode.braille.ocr.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Pengaturan: device, language and reading preferences, plus the recognition settings and
 * the one place the opt-in training-data toggle and its honest privacy explanation live.
 */
@Composable
fun SettingsScreen(model: OcrViewModel) {
    val strings = LocalStrings.current
    val nav = model.navigator
    val scope = rememberCoroutineScope()
    val language by model.language.collectAsState()
    val reading by model.reading.collectAsState()
    val status by model.braillePad.status.collectAsState()
    val trainingDataEnabled by model.trainingDataEnabled.collectAsState()
    val correctionSettings by model.correctionSettings.collectAsState()
    val geminiSettings by model.geminiSettings.collectAsState()
    val assist by model.assist.collectAsState()

    var count by remember { mutableIntStateOf(0) }
    var showConfirmDelete by remember { mutableStateOf(false) }
    var lastDeletedMessage by remember { mutableStateOf<String?>(null) }

    // Recomputed whenever the toggle or a deletion changes what is actually on disk, not
    // just once at first composition — a stale count would defeat the "inspectable" promise.
    LaunchedEffect(trainingDataEnabled, lastDeletedMessage) {
        count = model.trainingSampleCount()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brl.Paper50)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(strings.settingsTitle, style = BrlText.H2, color = Brl.Ink900, modifier = Modifier.semantics { heading() })

        BrlCard {
            SectionTitle(strings.deviceCard)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val ready = status as? BraillePad.Status.Ready
                    Text(
                        ready?.device?.name?.ifBlank { ready.device.address } ?: strings.noDevice,
                        style = BrlText.Body.copy(fontWeight = FontWeight.SemiBold),
                        color = Brl.Ink900,
                    )
                    Text(statusLabel(status, strings), style = BrlText.BodySmall, color = Brl.Ink600)
                    if (ready != null) {
                        Text(
                            "${strings.firmwareLabel}: ${ready.firmware ?: strings.firmwareUnknown}",
                            style = BrlText.BodySmall,
                            color = Brl.Ink500,
                        )
                    }
                }
                BrlButton(strings.manage, { nav.push(Route.Connect) }, style = BrlButtonStyle.Outline, mini = true)
            }
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

        Column {
            SectionTitle(strings.setupHelpTitle, Modifier.padding(bottom = 8.dp))
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
                enabled = model.haptics.level != Haptics.Level.NONE,
            )
            Spacer(Modifier.height(16.dp))
            Text(strings.afterScanTitle, style = BrlText.Body.copy(fontWeight = FontWeight.SemiBold), color = Brl.Ink900)
            Spacer(Modifier.height(8.dp))
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

        Column {
            SectionTitle(strings.preferences, Modifier.padding(bottom = 8.dp))
            ToggleRow(
                strings.physicalOutput, reading.autoSend,
                { model.setReading(reading.copy(autoSend = it)) },
                description = strings.physicalOutputDesc,
            )
            ToggleRow(
                strings.audioGuide, reading.autoSpeak,
                { model.setReading(reading.copy(autoSpeak = it)) },
                description = strings.audioGuideDesc,
            )
            Spacer(Modifier.height(12.dp))
            SpeechRate(reading.speechRate) { model.setReading(reading.copy(speechRate = it)) }
            Spacer(Modifier.height(16.dp))
            Text(strings.voiceLanguage, style = BrlText.Body.copy(fontWeight = FontWeight.SemiBold), color = Brl.Ink900)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceChip("Indonesia", reading.voiceLanguage == AppPrefs.LANG_ID, Modifier.weight(1f)) {
                    model.setReading(reading.copy(voiceLanguage = AppPrefs.LANG_ID))
                }
                ChoiceChip("English", reading.voiceLanguage == AppPrefs.LANG_EN, Modifier.weight(1f)) {
                    model.setReading(reading.copy(voiceLanguage = AppPrefs.LANG_EN))
                }
            }
            Spacer(Modifier.height(12.dp))
            val sample = if (reading.voiceLanguage == AppPrefs.LANG_EN) StringsEn.testVoiceSample else StringsId.testVoiceSample
            BrlButton(
                strings.testVoice,
                onClick = {
                    model.speech.speak("settings", sample, voiceLocale(reading.voiceLanguage), reading.speechRate)
                },
                style = BrlButtonStyle.Outline,
                icon = R.drawable.ic_volume_up,
                mini = true,
            )
        }

        OfflinePackagesCard(model)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(strings.recognitionTitle)
            BrlCard {
                ToggleRow(
                    strings.offlineTitle, correctionSettings.enabled,
                    { model.setCorrectionSettings(correctionSettings.copy(enabled = it)) },
                )
                Text(strings.offlineDesc, style = BrlText.BodySmall, color = Brl.Ink500)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = correctionSettings.extraWords,
                    onValueChange = { model.setCorrectionSettings(correctionSettings.copy(extraWords = it)) },
                    label = { Text(strings.wordListLabel) },
                    placeholder = { Text(strings.wordListPlaceholder) },
                    minLines = 3,
                    shape = TextFieldShape,
                    colors = brlTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Unlike everything else in the app, this sends data off the device, so the
            // explanation says exactly what goes where and who pays for it.
            BrlCard {
                ToggleRow(
                    strings.geminiTitle, geminiSettings.enabled,
                    { model.setGeminiSettings(geminiSettings.copy(enabled = it)) },
                    enabled = geminiSettings.apiKey.isNotBlank(),
                )
                Text(strings.geminiDesc, style = BrlText.BodySmall, color = Brl.Ink500)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = geminiSettings.apiKey,
                    onValueChange = { key ->
                        // Clearing the key also switches the feature off, so the toggle never lies.
                        model.setGeminiSettings(
                            geminiSettings.copy(apiKey = key.trim(), enabled = geminiSettings.enabled && key.isNotBlank()),
                        )
                    },
                    label = { Text(strings.apiKeyLabel) },
                    placeholder = { Text(strings.apiKeyPlaceholder) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = TextFieldShape,
                    colors = brlTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = geminiSettings.model,
                    onValueChange = { model.setGeminiSettings(geminiSettings.copy(model = it)) },
                    label = { Text(strings.modelLabel) },
                    singleLine = true,
                    shape = TextFieldShape,
                    colors = brlTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (geminiSettings.apiKey.isBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(strings.apiKeyNeeded, style = BrlText.Caption.copy(fontWeight = FontWeight.Normal), color = Brl.Ink500)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(strings.privacyTitle)
            BrlCard {
                ToggleRow(strings.trainingTitle, trainingDataEnabled, model::setTrainingDataEnabled)
                Text(strings.trainingDesc, style = BrlText.BodySmall, color = Brl.Ink500)
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Brl.Paper100)
                Text(strings.storedTitle, style = BrlText.Body.copy(fontWeight = FontWeight.SemiBold), color = Brl.Ink900)
                Text(strings.storedCount(count), style = BrlText.BodySmall, color = Brl.Ink600)
                lastDeletedMessage?.let {
                    Text(it, style = BrlText.Caption, color = Brl.Honeydew700, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                BrlButton(
                    strings.deleteAllSamples,
                    onClick = { showConfirmDelete = true },
                    style = BrlButtonStyle.Danger,
                    icon = R.drawable.ic_delete,
                    enabled = count > 0,
                    mini = true,
                )
            }
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrlButton(strings.openSetup, model::openSetup, style = BrlButtonStyle.Outline, mini = true)
                BrlButton(strings.openTutorial, model::openTutorial, style = BrlButtonStyle.Outline, mini = true)
            }
            Spacer(Modifier.height(8.dp))
            BrlButton(strings.showWelcome, model::showOnboardingAgain, style = BrlButtonStyle.Outline, mini = true)
            Spacer(Modifier.height(24.dp))
            Text(
                strings.version(appVersion()),
                style = BrlText.Caption.copy(fontWeight = FontWeight.Normal),
                color = Brl.Ink300,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showConfirmDelete) {
        ConfirmDialog(
            title = strings.deleteAllSamplesTitle,
            text = strings.deleteAllSamplesText(count),
            confirmLabel = strings.delete,
            onConfirm = {
                showConfirmDelete = false
                scope.launch {
                    val deleted = model.deleteAllTrainingSamples()
                    lastDeletedMessage = strings.samplesDeleted(deleted)
                }
            },
            onDismiss = { showConfirmDelete = false },
        )
    }
}

@Composable
private fun OfflinePackagesCard(model: OcrViewModel) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val status by model.offlinePackages.status.collectAsState()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        model.offlinePackages.ensure()
        onPauseOrDispose { }
    }
    fun label(state: OfflinePackages.State, progress: Float? = null) = when (state) {
        OfflinePackages.State.CHECKING -> strings.stateChecking
        OfflinePackages.State.DOWNLOADING -> strings.stateDownloading(progress?.let { (it * 100).toInt() })
        OfflinePackages.State.READY -> strings.stateReady
        OfflinePackages.State.NEEDS_INTERNET -> strings.stateNeedsInternet
        OfflinePackages.State.UNAVAILABLE -> strings.stateUnavailable
    }
    BrlCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(strings.offlinePackagesTitle, Modifier.weight(1f))
            if (status.allReady) Tag(strings.stateReady, tone = TagTone.Success, icon = R.drawable.ic_check)
        }
        Spacer(Modifier.height(4.dp))
        Text(strings.offlinePackagesDesc, style = BrlText.BodySmall, color = Brl.Ink500)
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoRow(strings.packageScanner, label(status.scanner, status.scannerProgress))
            status.voices.forEach { (language, state) ->
                InfoRow(strings.packageVoice(language), label(state))
            }
        }
        if (!status.allReady) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrlButton(strings.checkAgain, model.offlinePackages::ensure, style = BrlButtonStyle.Outline, mini = true)
                if (status.voices.values.any { it != OfflinePackages.State.READY }) {
                    BrlButton(
                        strings.downloadVoices,
                        onClick = {
                            // The engine's own voice-data screen, for when the automatic fetch can't.
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        style = BrlButtonStyle.Outline,
                        icon = R.drawable.ic_volume_up,
                        mini = true,
                    )
                }
            }
        }
    }
}

fun statusLabel(status: BraillePad.Status, strings: Strings): String = when (status) {
    is BraillePad.Status.Ready -> strings.deviceReady
    is BraillePad.Status.NotPaired -> strings.deviceNotPaired
    BraillePad.Status.BluetoothOff -> strings.deviceBluetoothOff
    BraillePad.Status.PermissionNeeded -> strings.devicePermission
    BraillePad.Status.Unsupported -> strings.deviceUnsupported
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }
}

/** The prototype's language buttons: outlined pills, the active one in vanilla. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) Brl.Vanila100 else Color.Transparent, tween(200), label = "chip")
    val border by animateColorAsState(if (selected) Brl.Vanila300 else Brl.Ink200, tween(200), label = "chipBorder")
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(Brl.Pill)
            .background(background)
            .border(BorderStroke(2.dp, border), Brl.Pill)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Brl.Ink900,
            fontFamily = Urbanist,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SpeechRate(rate: Float, onChange: (Float) -> Unit) {
    val strings = LocalStrings.current
    var value by remember(rate) { mutableFloatStateOf(rate) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.speechRate,
                style = BrlText.Body.copy(fontWeight = FontWeight.SemiBold),
                color = Brl.Ink900,
                modifier = Modifier.weight(1f),
            )
            val formatted = remember(value, strings.locale) {
                java.text.NumberFormat.getNumberInstance(strings.locale).apply {
                    minimumFractionDigits = 1
                    maximumFractionDigits = 2
                }.format(value)
            }
            Tag("$formatted×", tone = TagTone.Neutral)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(value) },
            valueRange = AppPrefs.MIN_RATE..AppPrefs.MAX_RATE,
            steps = 5,
            colors = SliderDefaults.colors(
                thumbColor = Brl.Ink900,
                activeTrackColor = Brl.Ink900,
                inactiveTrackColor = Brl.Ink200,
                activeTickColor = Brl.Vanila200,
                inactiveTickColor = Brl.Ink400,
            ),
        )
        Row {
            Text(strings.slow, style = BrlText.Caption, color = Brl.Ink400, modifier = Modifier.weight(1f))
            Text(strings.fast, style = BrlText.Caption, color = Brl.Ink400)
        }
    }
}
