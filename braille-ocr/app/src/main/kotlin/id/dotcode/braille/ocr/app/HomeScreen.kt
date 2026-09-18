package id.dotcode.braille.ocr.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(model: OcrViewModel) {
    val strings = LocalStrings.current
    val language by model.language.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(Brl.Paper50)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Brl.Gutter)
            .padding(top = 16.dp, bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LanguagePill(language, model::setLanguage)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            OnboardingIllustration()
            Spacer(Modifier.height(40.dp))
            Text(
                strings.onboardTitle,
                style = BrlText.H1,
                color = Brl.Ink900,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(16.dp))
            Text(strings.onboardSubtitle, style = BrlText.Body, color = Brl.Ink600, textAlign = TextAlign.Center)
        }
        BrlButton(strings.onboardButton, onClick = model::completeOnboarding, modifier = Modifier.fillMaxWidth())
    }
}

/** The prototype's `.braille-illustration`, spelling out "braille" one cell at a time. */
@Composable
private fun OnboardingIllustration() {
    val word = "braille"
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1100)
            index = (index + 1) % word.length
        }
    }
    val cell = Braille.cellsFor(word[index].toString()).last()
    Box(
        Modifier
            .size(160.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(Brl.Alice100)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        BrailleCellView(
            dots = cell.dots,
            dotSize = 24.dp,
            gap = 12.dp,
            activeColor = Brl.Ink900,
            inactiveColor = Brl.Ink200,
        )
    }
}

@Composable
fun LanguagePill(language: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(Brl.Pill)
            .background(Brl.Paper100)
            .padding(3.dp),
    ) {
        listOf(AppPrefs.LANG_ID to "ID", AppPrefs.LANG_EN to "EN").forEach { (code, label) ->
            val active = code == language
            val background by animateColorAsState(if (active) Brl.Paper0 else Color.Transparent, tween(200), label = "lang")
            val content by animateColorAsState(if (active) Brl.Ink900 else Brl.Ink400, tween(200), label = "langText")
            Box(
                Modifier
                    .then(if (active) Modifier.shadow(2.dp, Brl.Pill) else Modifier)
                    .clip(Brl.Pill)
                    .background(background)
                    .clickable(role = Role.Button) { onChange(code) }
                    .semantics { selected = active }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(label, color = content, fontFamily = Urbanist, fontSize = 12.sp, style = BrlText.Caption)
            }
        }
    }
}

@Composable
fun HomeScreen(model: OcrViewModel) {
    val strings = LocalStrings.current
    val nav = model.navigator
    val language by model.language.collectAsState()
    val history by model.history.collectAsState()
    val status by model.braillePad.status.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    LifecycleResumeEffect(Unit) {
        model.braillePad.refresh()
        onPauseOrDispose { }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importDocument(it, context.contentResolver.getType(it)) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brl.Paper50)
            .verticalScroll(rememberScrollState())
            .padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppLogo()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(strings.appName, style = BrlText.H2, color = Brl.Ink900, modifier = Modifier.semantics { heading() })
                Text(strings.byDotCode, style = BrlText.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Brl.Ink600)
            }
            LanguagePill(language, model::setLanguage)
        }

        DeviceStatusPill(status, onClick = { nav.push(Route.Connect) })

        BrlCard(background = Brl.GradDark) {
            Text(strings.homeScanTitle, style = BrlText.Title, color = Brl.Paper0, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(strings.homeScanDesc, style = BrlText.Body, color = Brl.Ink200)
            Spacer(Modifier.height(20.dp))
            BrlButton(
                strings.homeScanButton,
                onClick = { nav.push(Route.Scan) },
                icon = R.drawable.ic_document_scanner,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            BrlButton(
                strings.homeUploadButton,
                onClick = { pickDocument.launch(arrayOf(DocumentTextExtractor.PDF_MIME, DocumentTextExtractor.DOCX_MIME)) },
                style = BrlButtonStyle.OutlineOnDark,
                icon = R.drawable.ic_upload_file,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatCard(history.size.toString(), strings.statScanned, Modifier.weight(1f))
            StatCard(formatCount(history.sumOf { it.wordCount }, strings), strings.statWords, Modifier.weight(1f))
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.recentTitle, style = BrlText.H3, color = Brl.Ink900, modifier = Modifier.weight(1f).semantics { heading() })
            if (history.isNotEmpty()) {
                BrlButton(
                    strings.seeAll,
                    onClick = { nav.selectTab(Route.History) },
                    style = BrlButtonStyle.Outline,
                    mini = true,
                )
            }
        }
        if (history.isEmpty()) {
            EmptyState(R.drawable.ic_menu_book, strings.recentEmptyTitle, strings.recentEmptyDesc)
        } else {
            history.take(3).forEach { entry ->
                HistoryRowCard(entry, onClick = {
                    scope.launch {
                        if (model.openHistory(entry.id)) nav.push(Route.Result)
                        else snackbar.showSnackbar(strings.openFailed)
                    }
                })
            }
        }
    }
}

private fun formatCount(value: Int, strings: Strings): String =
    java.text.NumberFormat.getIntegerInstance(strings.locale).format(value)

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    BrlCard(modifier.semantics(mergeDescendants = true) { }) {
        Text(value, style = BrlText.H1, color = Brl.Ink900, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = BrlText.BodySmall, color = Brl.Ink600)
    }
}

@Composable
fun DeviceStatusPill(status: BraillePad.Status, onClick: () -> Unit) {
    val strings = LocalStrings.current
    val (label, dot) = when (status) {
        is BraillePad.Status.Ready -> strings.deviceReady to Brl.Honeydew500
        is BraillePad.Status.NotPaired -> strings.deviceNotPaired to Brl.Vanila500
        BraillePad.Status.BluetoothOff -> strings.deviceBluetoothOff to Brl.Alert600
        BraillePad.Status.PermissionNeeded -> strings.devicePermission to Brl.Alice500
        BraillePad.Status.Unsupported -> strings.deviceUnsupported to Brl.Ink300
    }
    val dotColor by animateColorAsState(dot, tween(300), label = "statusDot")
    BrlCard(
        onClick = onClick,
        onClickLabel = strings.connectTitle,
        shape = Brl.Pill,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(8.dp))
            Text(label, style = BrlText.Label, color = Brl.Ink700, modifier = Modifier.weight(1f))
            BrlIcon(R.drawable.ic_chevron_right, tint = Brl.Ink300, size = 18.dp)
        }
    }
}

@Composable
fun HistoryRowCard(entry: HistoryEntry, onClick: () -> Unit) {
    val strings = LocalStrings.current
    val isDocument = entry.sourceKind == SourceKind.DOCUMENT
    BrlCard(onClick = onClick, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDocument) Brl.Vanila100 else Brl.Alice100),
                contentAlignment = Alignment.Center,
            ) {
                BrlIcon(
                    if (isDocument) R.drawable.ic_description else R.drawable.ic_menu_book,
                    tint = if (isDocument) Brl.Vanila700 else Brl.Alice700,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title.ifBlank { strings.untitled },
                    style = BrlText.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = Brl.Ink900,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatWhen(entry.createdAt, strings)} · ${strings.words(entry.wordCount)}",
                    style = BrlText.BodySmall,
                    color = Brl.Ink600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BrlIcon(R.drawable.ic_chevron_right, tint = Brl.Ink300)
        }
    }
}

@Composable
fun EmptyState(icon: Int, title: String, description: String, modifier: Modifier = Modifier) {
    BrlCard(
        modifier.fillMaxWidth(),
        elevated = false,
        border = BorderStroke(1.dp, Brl.Paper200),
        contentPadding = PaddingValues(24.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Brl.Paper100),
                contentAlignment = Alignment.Center,
            ) { BrlIcon(icon, tint = Brl.Ink400) }
            Spacer(Modifier.height(12.dp))
            Text(title, style = BrlText.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Brl.Ink900, textAlign = TextAlign.Center)
            Text(description, style = BrlText.BodySmall, color = Brl.Ink500, textAlign = TextAlign.Center)
        }
    }
}
