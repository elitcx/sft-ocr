package id.dotcode.braille.ocr.app

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.util.regex.Pattern

/**
 * Pairing through Android's companion-device chooser: a system sheet over the app that lists
 * only nearby devices whose name looks like a BraillePad. Needs no scan or location
 * permission. Returns a function that starts it; [onUnsupported] runs where the chooser isn't
 * available (the caller falls back to Bluetooth settings).
 */
@Composable
fun rememberCompanionPairing(
    onDevice: (BluetoothDevice) -> Unit,
    onFailed: () -> Unit,
    onUnsupported: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentOnDevice = rememberUpdatedState(onDevice)
    val currentOnFailed = rememberUpdatedState(onFailed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // Backing out of the chooser is not a failure worth reporting.
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val device = chosenDevice(result.data)
        if (device != null) currentOnDevice.value(device) else currentOnFailed.value()
    }
    return start@{
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        if (manager == null ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
        ) {
            onUnsupported()
            return@start
        }
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setNamePattern(NAME_PATTERN).build())
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            // Android 13+ reaches this through onAssociationPending's default implementation.
            @Deprecated("Deprecated in Java")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                launcher.launch(IntentSenderRequest.Builder(chooserLauncher).build())
            }

            override fun onFailure(error: CharSequence?) {
                currentOnFailed.value()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(request, context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.associate(request, callback, null)
            }
        } catch (e: RuntimeException) {
            onUnsupported()
        }
    }
}

/** Nearby classic-Bluetooth devices the chooser offers: the ESP32 sketch or a named BraillePad. */
private val NAME_PATTERN: Pattern = Pattern.compile("(?i).*(esp32|braille).*")

private fun chosenDevice(data: Intent?): BluetoothDevice? {
    data ?: return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            ?.associatedDevice?.bluetoothDevice
            ?.let { return it }
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
    }
}
