@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package id.dotcode.braille.ocr.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(model: OcrViewModel) {
    val strings = LocalStrings.current
    val nav = model.navigator
    val history by model.history.collectAsState()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    val delete: (HistoryEntry) -> Unit = delete@{ entry ->
        if (!model.deleteHistory(entry.id)) return@delete
        // The ViewModel's scope, so the deletion completes even if this screen is left.
        model.viewModelScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(strings.entryDeleted, strings.undo, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) {
                model.undoDeleteHistory(entry.id)
            } else {
                model.commitDeleteHistory(entry.id)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Brl.Paper50),
        contentPadding = screenPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    strings.historyTitle,
                    style = BrlText.H2,
                    color = Brl.Ink900,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (history.isNotEmpty()) {
                    BrlButton(strings.clearAll, { confirmClear = true }, style = BrlButtonStyle.Danger, mini = true)
                }
            }
            if (history.isNotEmpty()) {
                Text(strings.swipeToDelete, style = BrlText.Caption, color = Brl.Ink400)
            }
        }
        if (history.isEmpty()) {
            item(key = "empty") {
                EmptyState(R.drawable.ic_history, strings.historyEmptyTitle, strings.historyEmptyDesc)
            }
        }
        items(history, key = { it.id }) { entry ->
            SwipeableHistoryCard(
                entry = entry,
                onOpen = {
                    scope.launch {
                        if (model.openHistory(entry.id)) nav.push(Route.Result)
                        else snackbar.showSnackbar(strings.openFailed)
                    }
                },
                onDelete = { delete(entry) },
                modifier = Modifier.animateItem(),
            )
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = strings.clearAllTitle,
            text = strings.clearAllText(history.size),
            confirmLabel = strings.delete,
            onConfirm = {
                confirmClear = false
                model.clearHistory()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun SwipeableHistoryCard(
    entry: HistoryEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onDelete()
                true
            } else false
        },
        positionalThreshold = { it * 0.35f },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val active = state.targetValue != SwipeToDismissBoxValue.Settled
            val color by animateColorAsState(if (active) Brl.Alert300 else Brl.Alert100, tween(200), label = "swipe")
            val scale by animateFloatAsState(if (active) 1.15f else 0.9f, tween(200), label = "swipeIcon")
            val alignEnd = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(Brl.CardShape)
                    .background(color)
                    .padding(horizontal = 28.dp),
                contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                BrlIcon(
                    R.drawable.ic_delete,
                    tint = Brl.Alert700,
                    modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                )
            }
        },
    ) {
        BrlCard(
            modifier = Modifier.semantics {
                customActions = listOf(CustomAccessibilityAction(strings.delete) { onDelete(); true })
            },
            onClick = onOpen,
            contentPadding = PaddingValues(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    entry.title.ifBlank { strings.untitled },
                    style = BrlText.Body.copy(fontWeight = FontWeight.ExtraBold),
                    color = Brl.Ink900,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    if (entry.sentToDevice) Tag(strings.tagSent, tone = TagTone.Success)
                    if (entry.sourceKind == SourceKind.DOCUMENT) {
                        Tag(strings.tagDocument, tone = TagTone.Vanila)
                    } else {
                        Tag(strings.tagPhoto, tone = TagTone.Info)
                    }
                }
            }
            if (entry.preview.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    entry.preview,
                    style = BrlText.BodySmall,
                    color = Brl.Ink600,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatWhen(entry.createdAt, strings), style = BrlText.Caption.copy(fontWeight = FontWeight.Normal), color = Brl.Ink400)
                Text(" · ", style = BrlText.Caption, color = Brl.Ink300)
                Text(strings.words(entry.wordCount), style = BrlText.Caption.copy(fontWeight = FontWeight.Normal), color = Brl.Ink400)
                if (entry.corrected) {
                    Spacer(Modifier.width(8.dp))
                    BrlIcon(R.drawable.ic_spellcheck, tint = Brl.Ink300, size = 16.dp, contentDescription = strings.tagCorrected)
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brl.Paper0,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = BrlText.Title, color = Brl.Ink900) },
        text = { Text(text, style = BrlText.Body, color = Brl.Ink600) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = Brl.Alert600, fontFamily = Urbanist, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel, color = Brl.Ink700, fontFamily = Urbanist, fontWeight = FontWeight.Bold)
            }
        },
    )
}
