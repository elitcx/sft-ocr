package id.dotcode.braille.ocr.app

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(model: OcrViewModel) {
    val strings = LocalStrings.current
    val nav = model.navigator
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val status by model.braillePad.status.collectAsState()
    val test by model.connectionTest.collectAsState()
    val lastSend by model.lastSend.collectAsState()
    val pairing by model.braillePad.pairing.collectAsState()

    LifecycleResumeEffect(Unit) {
        model.braillePad.refresh()
        onPauseOrDispose { }
    }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { model.braillePad.refresh() }
    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { model.braillePad.refresh() }

    val openBluetoothSettings: () -> Unit = {
        try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(strings.statusUnsupportedDesc) }
        }
    }

    val startPairing = rememberCompanionPairing(
        onDevice = model.braillePad::pair,
        onFailed = model.braillePad::pairingFailed,
        onUnsupported = openBluetoothSettings,
    )
    DisposableEffect(Unit) { onDispose { model.braillePad.clearPairing() } }

    val readyAddress = (status as? BraillePad.Status.Ready)?.device?.address
    // Opening this screen with a device ready checks it (and learns its firmware) once.
    LaunchedEffect(readyAddress) {
        if (readyAddress != null) model.checkDevice()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brl.Paper50)
            .verticalScroll(rememberScrollState())
            .padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(strings.connectTitle, onBack = { nav.pop() }, backDescription = strings.back)

        StatusHero(
            status = status,
            onGrant = {
                val permissions = model.esp32Sender.requiredPermissions
                if (permissions.isNotEmpty()) requestPermission.launch(permissions) else model.braillePad.refresh()
            },
            onEnable = {
                try {
                    enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: SecurityException) {
                    model.braillePad.refresh()
                } catch (e: ActivityNotFoundException) {
                    openBluetoothSettings()
                }
            },
            onPair = startPairing,
        )

        PairingNotice(pairing)

        val ready = status as? BraillePad.Status.Ready
        // The card keeps its last contents while it animates away.
        val lastReady = remember { arrayOfNulls<BraillePad.Status.Ready>(1) }
        if (ready != null) lastReady[0] = ready
        AnimatedVisibility(
            visible = ready != null,
            enter = expandVertically(tween(300)) + fadeIn(tween(300)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(150)),
        ) {
            val device = lastReady[0]
            if (device != null) {
                DeviceInfoCard(
                    ready = device,
                    lastSend = lastSend,
                    test = test,
                    onCheck = model::checkDevice,
                )
            }
        }

        val paired = when (val s = status) {
            is BraillePad.Status.Ready -> s.paired
            is BraillePad.Status.NotPaired -> s.paired
            else -> null
        }
        if (paired != null) {
            PairedDevices(
                paired = paired,
                selectedAddress = ready?.device?.address,
                onSelect = model::selectDevice,
                onRefresh = model.braillePad::refresh,
            )
            // When not paired, the status card already offers pairing.
            if (ready != null) {
                BrlButton(
                    strings.pairAnother,
                    onClick = startPairing,
                    style = BrlButtonStyle.Outline,
                    icon = R.drawable.ic_bluetooth_searching,
                    loading = pairing is BraillePad.Pairing.Bonding,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextLink(strings.openBluetoothSettings, openBluetoothSettings)
        }

        if (ready?.manual == true) {
            BrlButton(
                strings.resetSelection,
                onClick = { model.selectDevice(null) },
                style = BrlButtonStyle.Danger,
                icon = R.drawable.ic_bluetooth_disabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class HeroStyle(
    val background: Color,
    val border: Color,
    val circle: Color,
    val content: Color,
    val icon: Int,
)

@Composable
private fun StatusHero(
    status: BraillePad.Status,
    onGrant: () -> Unit,
    onEnable: () -> Unit,
    onPair: () -> Unit,
) {
    val strings = LocalStrings.current
    val style = when (status) {
        is BraillePad.Status.Ready ->
            HeroStyle(Brl.Honeydew100, Brl.Honeydew300, Brl.Honeydew200, Brl.Honeydew700, R.drawable.ic_bluetooth_connected)
        is BraillePad.Status.NotPaired ->
            HeroStyle(Brl.Vanila100, Brl.Vanila300, Brl.Vanila200, Brl.Vanila700, R.drawable.ic_bluetooth_searching)
        BraillePad.Status.BluetoothOff ->
            HeroStyle(Brl.Alert100, Brl.Alert300, Brl.Alert300, Brl.Alert700, R.drawable.ic_bluetooth_disabled)
        BraillePad.Status.PermissionNeeded ->
            HeroStyle(Brl.Alice100, Brl.Alice300, Brl.Alice200, Brl.Alice700, R.drawable.ic_lock)
        BraillePad.Status.Unsupported ->
            HeroStyle(Brl.Paper100, Brl.Paper200, Brl.Paper200, Brl.Ink500, R.drawable.ic_bluetooth_disabled)
    }
    val background by animateColorAsState(style.background, tween(300), label = "heroBg")
    val border by animateColorAsState(style.border, tween(300), label = "heroBorder")
    val circle by animateColorAsState(style.circle, tween(300), label = "heroCircle")
    val content by animateColorAsState(style.content, tween(300), label = "heroContent")

    BrlCard(
        color = background,
        border = BorderStroke(2.dp, border),
        elevated = false,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(circle),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = style.icon,
                    transitionSpec = { (fadeIn(tween(250)) + scaleIn(tween(250), 0.7f)) togetherWith fadeOut(tween(120)) },
                    label = "heroIcon",
                ) { icon -> BrlIcon(icon, tint = content, size = 36.dp) }
            }
            Spacer(Modifier.height(16.dp))
            val (title, subtitle) = when (status) {
                is BraillePad.Status.Ready -> strings.statusReady to
                    "${status.device.name.ifBlank { status.device.address }} (ESP32)"
                is BraillePad.Status.NotPaired -> strings.statusNotPaired to strings.statusNotPairedDesc
                BraillePad.Status.BluetoothOff -> strings.statusOff to strings.statusOffDesc
                BraillePad.Status.PermissionNeeded -> strings.statusPermission to strings.statusPermissionDesc
                BraillePad.Status.Unsupported -> strings.statusUnsupported to strings.statusUnsupportedDesc
            }
            Text(title, style = BrlText.H3, color = Brl.Ink900, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = BrlText.BodySmall, color = content, textAlign = TextAlign.Center)
            val action: Pair<String, () -> Unit>? = when (status) {
                BraillePad.Status.PermissionNeeded -> strings.allowAccess to onGrant
                BraillePad.Status.BluetoothOff -> strings.turnOnBluetooth to onEnable
                is BraillePad.Status.NotPaired -> strings.pairPad to onPair
                else -> null
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                BrlButton(
                    action.first, action.second,
                    style = BrlButtonStyle.Dark,
                    mini = true,
                    icon = if (status is BraillePad.Status.NotPaired) R.drawable.ic_bluetooth_searching else null,
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(
    ready: BraillePad.Status.Ready,
    lastSend: LastSend?,
    test: ConnectionTestState,
    onCheck: () -> Unit,
) {
    val strings = LocalStrings.current
    BrlCard(elevated = false, border = BorderStroke(1.dp, Brl.Paper200)) {
        Text(strings.deviceInfo, style = BrlText.Overline, color = Brl.Ink500, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoRow(strings.nameLabel, ready.device.name.ifBlank { "—" })
            InfoRow(strings.addressLabel, ready.device.address)
            InfoRow(strings.typeLabel, strings.typeValue)
            InfoRow(strings.firmwareLabel, ready.firmware ?: strings.firmwareUnknown)
            InfoRow(strings.selectionLabel, if (ready.manual) strings.selectionManual else strings.selectionAuto)
            InfoRow(
                strings.lastSendLabel,
                lastSend?.let { strings.lastSend(it.bytes, formatWhen(it.at, strings)) } ?: strings.never,
            )
        }
        Spacer(Modifier.height(16.dp))
        BrlButton(
            if (test == ConnectionTestState.Testing) strings.checking else strings.checkDevice,
            onClick = onCheck,
            style = BrlButtonStyle.Outline,
            icon = R.drawable.ic_bluetooth_searching,
            loading = test == ConnectionTestState.Testing,
            mini = true,
        )
        AnimatedVisibility(test is ConnectionTestState.Ok) {
            val ok = test as? ConnectionTestState.Ok
            if (ok != null) {
                val firmware = ok.firmware
                if (firmware != null) {
                    Tag(
                        strings.deviceOk(firmware),
                        tone = TagTone.Success,
                        icon = R.drawable.ic_check,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Text(
                        strings.deviceOkOldFirmware,
                        style = BrlText.BodySmall,
                        color = Brl.Vanila700,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        AnimatedVisibility(test is ConnectionTestState.Failed) {
            val error = (test as? ConnectionTestState.Failed)?.error
            if (error != null) {
                Text(
                    strings.sendError(error),
                    style = BrlText.BodySmall,
                    color = Brl.Alert700,
                    modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun PairedDevices(
    paired: List<BraillePad.Device>,
    selectedAddress: String?,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val spin = remember { Animatable(0f) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.pairedDevices, style = BrlText.H3.copy(fontSize = 16.sp), color = Brl.Ink900, modifier = Modifier.weight(1f).semantics { heading() })
            BrlIconButton(
                R.drawable.ic_autorenew,
                strings.refresh,
                onClick = {
                    onRefresh()
                    scope.launch {
                        spin.snapTo(0f)
                        spin.animateTo(360f, tween(700))
                    }
                },
                tint = Brl.Ink500,
                size = 40.dp,
                iconSize = 20.dp,
                modifier = Modifier.rotate(spin.value),
            )
        }
        if (paired.isEmpty()) {
            EmptyState(R.drawable.ic_bluetooth_searching, strings.noPairedDevices, strings.pairHint)
        }
        paired.forEach { device ->
            DeviceRow(device, selected = device.address == selectedAddress, onSelect = { onSelect(device.address) })
        }
    }
}

@Composable
private fun DeviceRow(device: BraillePad.Device, selected: Boolean, onSelect: () -> Unit) {
    val strings = LocalStrings.current
    val background by animateColorAsState(if (selected) Brl.Honeydew100 else Brl.Paper0, tween(300), label = "rowBg")
    val border by animateColorAsState(if (selected) Brl.Honeydew500 else Color.Transparent, tween(300), label = "rowBorder")
    val iconBackground by animateColorAsState(if (selected) Brl.Honeydew200 else Brl.Paper100, tween(300), label = "rowIcon")
    BrlCard(
        color = background,
        border = BorderStroke(2.dp, border),
        onClick = if (selected) null else onSelect,
        onClickLabel = strings.use,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(iconBackground),
                contentAlignment = Alignment.Center,
            ) {
                BrlIcon(
                    if (selected) R.drawable.ic_bluetooth_connected else R.drawable.ic_bluetooth,
                    tint = if (selected) Brl.Honeydew700 else if (device.likelyReceiver) Brl.Ink700 else Brl.Ink400,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name.ifBlank { device.address },
                    style = BrlText.Body.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                    color = Brl.Ink900,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${device.address} · ${if (device.likelyReceiver) strings.likelyReceiver else strings.otherDevice}",
                    style = BrlText.Caption.copy(fontWeight = FontWeight.Normal),
                    color = Brl.Ink400,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (selected) {
                Tag(strings.inUse, tone = TagTone.Success)
            } else {
                BrlButton(strings.use, onSelect, style = BrlButtonStyle.Outline, mini = true)
            }
        }
    }
}

@Composable
private fun PairingNotice(pairing: BraillePad.Pairing) {
    val strings = LocalStrings.current
    AnimatedVisibility(
        visible = pairing !is BraillePad.Pairing.Idle,
        enter = expandVertically(tween(250)) + fadeIn(tween(250)),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
    ) {
        Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (pairing) {
                is BraillePad.Pairing.Bonding -> NoticeCard(
                    title = strings.pairPad,
                    message = strings.pairingInProgress(pairing.name),
                    tone = TagTone.Info,
                )
                is BraillePad.Pairing.Paired -> NoticeCard(
                    title = strings.pairPad,
                    message = strings.pairedOk(pairing.name),
                    tone = TagTone.Success,
                )
                is BraillePad.Pairing.Failed -> NoticeCard(
                    title = strings.pairPad,
                    message = strings.pairFailed,
                )
                BraillePad.Pairing.Idle -> Unit
            }
        }
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Brl.Pill)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrlIcon(R.drawable.ic_open_in_new, tint = Brl.Ink500, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = BrlText.Label, color = Brl.Ink500)
    }
}
