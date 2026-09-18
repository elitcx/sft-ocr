package id.dotcode.braille.ocr.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the app knows about the BraillePad without connecting to it: whether Bluetooth is
 * usable, which devices are paired, and which of them text would be sent to. Kept current by
 * Bluetooth state and pairing broadcasts, plus [refresh] when a screen comes back into view.
 */
class BraillePad(context: Context, private val prefs: AppPrefs, private val permissions: Array<String>) {

    data class Device(val name: String, val address: String) {
        val likelyReceiver: Boolean get() = isLikelyReceiver(name)
    }

    sealed interface Status {
        data object Unsupported : Status
        data object PermissionNeeded : Status
        data object BluetoothOff : Status
        data class NotPaired(val paired: List<Device>) : Status
        data class Ready(
            val device: Device,
            val paired: List<Device>,
            val manual: Boolean,
            /** Last version the device reported, or null if never asked or too old to answer. */
            val firmware: String?,
        ) : Status
    }

    /** An in-app pairing started from Koneksi. */
    sealed interface Pairing {
        data object Idle : Pairing
        data class Bonding(val name: String) : Pairing
        data class Paired(val name: String) : Pairing
        data class Failed(val name: String?) : Pairing
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _pairing = MutableStateFlow<Pairing>(Pairing.Idle)
    val pairing: StateFlow<Pairing> = _pairing.asStateFlow()
    private var bondingAddress: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) onBondChanged(intent)
            refresh()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun refresh() {
        _status.value = readStatus()
    }

    /**
     * Pairs with a device the companion chooser returned. Android shows its own pairing
     * confirmation; the outcome arrives as a bond-state broadcast.
     */
    @SuppressLint("MissingPermission")
    fun pair(device: BluetoothDevice) {
        val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { device.address }
        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                select(device.address)
                _pairing.value = Pairing.Paired(name)
                return
            }
            bondingAddress = device.address
            _pairing.value = Pairing.Bonding(name)
            if (!device.createBond()) {
                bondingAddress = null
                _pairing.value = Pairing.Failed(name)
            }
        } catch (e: SecurityException) {
            bondingAddress = null
            _pairing.value = Pairing.Failed(name)
            refresh()
        }
    }

    fun pairingFailed() {
        _pairing.value = Pairing.Failed(null)
    }

    fun clearPairing() {
        if (_pairing.value !is Pairing.Bonding) _pairing.value = Pairing.Idle
    }

    fun rememberFirmware(address: String, version: String) {
        prefs.saveFirmware(address, version)
        refresh()
    }

    @SuppressLint("MissingPermission")
    private fun onBondChanged(intent: Intent) {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (device.address != bondingAddress) return
        val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { device.address }
        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
            BluetoothDevice.BOND_BONDED -> {
                bondingAddress = null
                // A device the user just paired from here is clearly the one they mean.
                prefs.selectedDeviceAddress = device.address
                _pairing.value = Pairing.Paired(name)
            }
            BluetoothDevice.BOND_NONE -> {
                bondingAddress = null
                _pairing.value = Pairing.Failed(name)
            }
        }
    }

    fun select(address: String?) {
        prefs.selectedDeviceAddress = address
        refresh()
    }

    fun close() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    private fun readStatus(): Status {
        val adapter = adapter ?: return Status.Unsupported
        val granted = permissions.all {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return Status.PermissionNeeded
        return try {
            if (!adapter.isEnabled) return Status.BluetoothOff
            val paired = adapter.bondedDevices.orEmpty()
                .map { Device(it.name.orEmpty(), it.address) }
                .sortedWith(compareByDescending<Device> { it.likelyReceiver }.thenBy { it.name.lowercase() })
            val selected = prefs.selectedDeviceAddress
            val target = resolveReceiver(paired, selected)
            if (target == null) {
                Status.NotPaired(paired)
            } else {
                Status.Ready(
                    target, paired,
                    manual = target.address == selected,
                    firmware = prefs.firmwareFor(target.address),
                )
            }
        } catch (e: SecurityException) {
            Status.PermissionNeeded
        }
    }

    companion object {
        fun isLikelyReceiver(name: String): Boolean =
            name == Esp32BluetoothSender.DEVICE_NAME || name.contains("ESP32", ignoreCase = true)

        /**
         * The device text goes to: the one chosen in Koneksi if it is still paired, otherwise
         * the sketch's own name, otherwise anything that calls itself an ESP32.
         */
        fun resolveReceiver(paired: List<Device>, selectedAddress: String?): Device? =
            paired.firstOrNull { selectedAddress != null && it.address == selectedAddress }
                ?: paired.firstOrNull { it.name == Esp32BluetoothSender.DEVICE_NAME }
                ?: paired.firstOrNull { it.name.contains("ESP32", ignoreCase = true) }
    }
}
