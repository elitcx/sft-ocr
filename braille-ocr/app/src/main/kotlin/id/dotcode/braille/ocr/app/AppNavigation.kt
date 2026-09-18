package id.dotcode.braille.ocr.app

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import id.dotcode.braille.ocr.model.OcrResult
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Route(val dark: Boolean = false, val tab: Boolean = false) {
    Onboarding,
    Setup,
    Tutorial,
    Home(tab = true),
    Scan(dark = true),
    Process(dark = true),
    Result,
    Detail,
    Read(dark = true),
    History(tab = true),
    Settings(tab = true),
    Connect,
}

/** Screens that belong to one scan; a new scan replaces them rather than stacking up. */
private val SCAN_FLOW = setOf(Route.Scan, Route.Process, Route.Result, Route.Detail, Route.Read)

/** A plain back stack. Lives in the ViewModel so it survives configuration changes. */
class Navigator(start: Route) {
    val stack = mutableStateListOf(start)
    val current: Route get() = stack.last()

    fun push(route: Route) {
        if (current != route) stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replaceTop(route: Route) {
        stack[stack.lastIndex] = route
    }

    fun resetTo(route: Route) {
        stack.clear()
        stack.add(route)
    }

    /** Tabs sit directly on Home, so back from any tab lands there. */
    fun selectTab(route: Route) {
        stack.clear()
        stack.add(Route.Home)
        if (route != Route.Home) stack.add(route)
    }

    fun startProcessing() {
        stack.removeAll { it in SCAN_FLOW }
        if (stack.isEmpty()) stack.add(Route.Home)
        stack.add(Route.Process)
    }
}

/** Space a tab screen must leave at the bottom for the navigation bar. */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

private const val PROCESS_SUCCESS_HOLD_MS = 650L
private val BOTTOM_BAR_HEIGHT = 68.dp

@Composable
fun AppRoot(model: OcrViewModel) {
    val language by model.language.collectAsState()
    val strings = stringsFor(language)
    val nav = model.navigator
    val route = nav.current
    val state by model.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // Keeps the screens in step with the scan: working → processing, finished → result.
    LaunchedEffect(state) {
        when (val current = state) {
            is UiState.Working -> if (nav.current != Route.Process) nav.startProcessing()
            is UiState.Done -> if (nav.current == Route.Process) {
                // A success lingers long enough to see the checklist complete.
                val success = current.result is OcrResult.Success
                delay(if (success) PROCESS_SUCCESS_HOLD_MS else 150L)
                if (nav.current == Route.Process) {
                    nav.replaceTop(Route.Result)
                    // "Setelah memindai": go straight to reading, if that is what was chosen.
                    if (success) {
                        when (model.assist.value.afterScan) {
                            AppPrefs.AfterScan.READ_MODE -> nav.push(Route.Read)
                            AppPrefs.AfterScan.SPEAK -> model.speakResult()
                            AppPrefs.AfterScan.RESULT -> Unit
                        }
                    }
                }
            }
            UiState.Idle -> if (nav.current == Route.Process) nav.pop()
        }
    }

    val stateHolder = rememberSaveableStateHolder()
    val stackSnapshot = nav.stack.toList()
    LaunchedEffect(stackSnapshot) {
        // A screen that left the back stack starts fresh next time.
        Route.entries.filterNot { it in stackSnapshot }.forEach { stateHolder.removeState(it.name) }
    }

    SystemBarStyle(dark = route.dark)
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalSnackbar provides snackbar,
    ) {
        ScanAnnouncements(model)

        Box(Modifier.fillMaxSize().background(if (route.dark) Brl.Ink900 else Brl.Paper50)) {
            val slide = with(LocalDensity.current) { 10.dp.roundToPx() }
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    (
                        fadeIn(tween(300, easing = FastOutSlowInEasing)) +
                            slideInVertically(tween(300, easing = FastOutSlowInEasing)) { slide }
                        ) togetherWith fadeOut(tween(120))
                },
                label = "screen",
                modifier = Modifier.fillMaxSize(),
            ) { target ->
                // Coming back to a screen restores its scroll position and other saved state.
                stateHolder.SaveableStateProvider(target.name) {
                    CompositionLocalProvider(
                        LocalBottomBarSpace provides if (target.tab) BOTTOM_BAR_HEIGHT + navBarInset else 0.dp,
                    ) {
                        Screen(target, model)
                    }
                }
            }

            // Light screens scroll under a transparent status bar; keep the clock and icons legible.
            if (!route.dark) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(Brl.Paper50.copy(alpha = 0.94f)),
                )
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                SnackbarHost(snackbar) { BrlSnackbar(it) }
                AnimatedVisibility(
                    visible = route.tab,
                    enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)),
                    exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
                ) {
                    BottomBar(current = route, onSelect = { tab ->
                        if (tab == Route.Scan) nav.push(Route.Scan) else nav.selectTab(tab)
                    })
                }
            }
        }
    }
}

@Composable
private fun Screen(route: Route, model: OcrViewModel) {
    val nav = model.navigator
    when (route) {
        Route.Onboarding -> OnboardingScreen(model)
        Route.Setup -> SetupScreen(model)
        Route.Tutorial -> TutorialScreen(model)
        Route.Home -> HomeScreen(model)
        Route.Scan -> CaptureScreen(model, onClose = { nav.pop() })
        Route.Process -> ProcessScreen(model)
        Route.Result -> ResultScreen(model)
        Route.Detail -> DetailScreen(model)
        Route.Read -> ReadScreen(model)
        Route.History -> HistoryScreen(model)
        Route.Settings -> SettingsScreen(model)
        Route.Connect -> ConnectScreen(model)
    }
}

@Composable
private fun SystemBarStyle(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private data class Tab(val route: Route, @DrawableRes val icon: Int, val label: (Strings) -> String)

private val TABS = listOf(
    Tab(Route.Home, R.drawable.ic_home) { it.navHome },
    Tab(Route.Scan, R.drawable.ic_document_scanner) { it.navScan },
    Tab(Route.History, R.drawable.ic_history) { it.navHistory },
    Tab(Route.Settings, R.drawable.ic_settings) { it.navSettings },
)

@Composable
private fun BottomBar(current: Route, onSelect: (Route) -> Unit) {
    val strings = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brl.Paper0)
            .drawBehind { drawLine(Brl.Paper100, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding()
            .height(BOTTOM_BAR_HEIGHT)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TABS.forEach { tab ->
            val selected = tab.route == current
            val color by animateColorAsState(if (selected) Brl.Ink900 else Brl.Ink400, tween(200), label = "tab")
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = selected,
                        onClick = { onSelect(tab.route) },
                        role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 36.dp),
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BrlIcon(tab.icon, tint = color)
                Spacer(Modifier.height(4.dp))
                Text(
                    tab.label(strings),
                    color = color,
                    fontFamily = Urbanist,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** "Hari ini, 14:30" / "Kemarin, 14:30" / "12 Sep 2026". */
fun formatWhen(millis: Long, strings: Strings, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val moment = Instant.ofEpochMilli(millis).atZone(zone)
    val time = moment.format(DateTimeFormatter.ofPattern("HH:mm", strings.locale))
    return when (moment.toLocalDate()) {
        today -> strings.today(time)
        today.minusDays(1) -> strings.yesterday(time)
        else -> moment.format(DateTimeFormatter.ofPattern("d MMM yyyy", strings.locale))
    }
}

/** Standard padding for a scrolling light screen: status bar on top, bottom bar/nav bar below. */
@Composable
fun screenPadding(extraBottom: Dp = 24.dp): androidx.compose.foundation.layout.PaddingValues {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomBar = LocalBottomBarSpace.current
    val bottom = if (bottomBar > 0.dp) bottomBar else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return androidx.compose.foundation.layout.PaddingValues(
        start = Brl.Gutter, end = Brl.Gutter, top = top + 16.dp, bottom = bottom + extraBottom,
    )
}
