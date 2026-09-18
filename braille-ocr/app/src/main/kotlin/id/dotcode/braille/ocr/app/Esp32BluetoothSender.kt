package id.dotcode.braille.ocr.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Link to the BraillePad (an ESP32): pushes plain text over classic Bluetooth serial (SPP),
 * which the stock `BluetoothSerial` Arduino library speaks. The ESP32 must already be paired
 * with the phone in Android's Bluetooth settings; this never scans or pairs.
 *
 * Wire format: the UTF-8 text followed by a newline. One call = connect, write, disconnect.
 */
class Esp32BluetoothSender(context: Context, private val selectedAddress: () -> String?) {

    private val appContext = context.applicationContext

    /** Runtime permissions [send] needs; the caller requests them before calling it. */
    val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Before Android 12 the install-time BLUETOOTH permission covers connecting.
            emptyArray()
        }

    /** Sends [text] and returns the number of bytes delivered; throws [Esp32SendException]. */
    suspend fun send(text: String): Int = withConnection { socket ->
        val payload = (text + "\n").toByteArray(Charsets.UTF_8)
        try {
            // Writing the whole payload in one call bursts it out faster than the ESP32's
            // BluetoothSerial RX buffer can drain, overflowing it ("RX Full!"). Chunk it with
            // a short pause so the receiver keeps up.
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + CHUNK_SIZE, payload.size)
                socket.outputStream.write(payload, offset, end - offset)
                socket.outputStream.flush()
                offset = end
                if (offset < payload.size) delay(CHUNK_DELAY_MS)
            }
            // Closing straight after write can cut off the tail before it leaves the radio.
            delay(DRAIN_DELAY_MS)
        } catch (e: IOException) {
            throw Esp32SendException(Esp32SendException.Reason.DISCONNECTED)
        }
        payload.size
    }

    /**
     * Connects and asks the BraillePad for its firmware version. Returns null when the
     * connection works but the firmware predates the info command (it never answers).
     * Throws [Esp32SendException] when the device can't be reached.
     */
    suspend fun readFirmware(): String? = withConnection { socket ->
        try {
            socket.outputStream.write(INFO_REQUEST.toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
            val input = socket.inputStream
            val received = StringBuilder()
            val deadline = System.currentTimeMillis() + INFO_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val available = input.available()
                if (available > 0) {
                    val buffer = ByteArray(available)
                    val read = input.read(buffer)
                    if (read > 0) received.append(String(buffer, 0, read, Charsets.UTF_8))
                    parseInfoReply(received.toString())?.let { return@withConnection it }
                } else {
                    delay(INFO_POLL_MS)
                }
            }
            null
        } catch (e: IOException) {
            throw Esp32SendException(Esp32SendException.Reason.DISCONNECTED)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun <T> withConnection(block: suspend (BluetoothSocket) -> T): T = withContext(Dispatchers.IO) {
        try {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw Esp32SendException(Esp32SendException.Reason.UNSUPPORTED)
            if (!adapter.isEnabled) throw Esp32SendException(Esp32SendException.Reason.BLUETOOTH_OFF)

            val paired = adapter.bondedDevices.orEmpty()
            val target = BraillePad.resolveReceiver(
                paired.map { BraillePad.Device(it.name.orEmpty(), it.address) },
                selectedAddress(),
            ) ?: throw Esp32SendException(Esp32SendException.Reason.NOT_PAIRED)
            val device = paired.first { it.address == target.address }

            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                try {
                    socket.connect()
                } catch (e: IOException) {
                    throw Esp32SendException(
                        Esp32SendException.Reason.CONNECT_FAILED,
                        target.name.ifBlank { target.address },
                    )
                }
                block(socket)
            } finally {
                runCatching { socket.close() }
            }
        } catch (e: SecurityException) {
            throw Esp32SendException(Esp32SendException.Reason.PERMISSION)
        }
    }

    companion object {
        /** The name the ESP32 sketch passes to `SerialBT.begin(...)`. */
        const val DEVICE_NAME = "ESP32_Test_Board"

        /** Standard Serial Port Profile UUID. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private const val DRAIN_DELAY_MS = 500L

        /** Bytes per write; paced with [CHUNK_DELAY_MS] so the ESP32's RX buffer isn't overrun. */
        private const val CHUNK_SIZE = 256
        private const val CHUNK_DELAY_MS = 20L

        /**
         * Commands start with SOH (0x01), which scanned text never contains, so the firmware
         * can tell them apart from text to print. See firmware/esp32-ocr-receiver.
         */
        private const val COMMAND_PREFIX = ''
        const val INFO_REQUEST = "${COMMAND_PREFIX}INFO\n"
        private const val INFO_TIMEOUT_MS = 2500L
        private const val INFO_POLL_MS = 50L

        /** Finds `SOH INFO fw=<version>;...` in what the device has sent so far. */
        fun parseInfoReply(received: String): String? {
            val line = received.lineSequence()
                .firstOrNull { it.startsWith("${COMMAND_PREFIX}INFO ") }
                ?: return null
            // Only a complete line counts; the rest may still be on its way.
            if (!received.substringAfter(line, "").startsWith("\n")) return null
            return line.removePrefix("${COMMAND_PREFIX}INFO ")
                .split(';')
                .map { it.trim() }
                .firstOrNull { it.startsWith("fw=") }
                ?.removePrefix("fw=")
                ?.takeIf { it.isNotBlank() }
        }
    }
}

/** Why a send failed; the UI turns [reason] into a message in the current language. */
class Esp32SendException(val reason: Reason, val deviceName: String? = null) :
    Exception("${reason.name}${deviceName?.let { " ($it)" }.orEmpty()}") {
    enum class Reason { UNSUPPORTED, BLUETOOTH_OFF, NOT_PAIRED, CONNECT_FAILED, DISCONNECTED, PERMISSION }
}
