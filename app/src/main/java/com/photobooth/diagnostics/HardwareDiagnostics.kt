package com.photobooth.diagnostics

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.photobooth.camera.CameraConnectionState
import com.photobooth.camera.CameraStrategy
import com.photobooth.camera.UniversalCanonManager
import com.photobooth.data.repository.PrintQueueRepository
import com.photobooth.data.settings.AppSettings
import com.photobooth.data.settings.SettingsRepository
import com.photobooth.print.dnp.DnpPrinterLink
import com.photobooth.print.dnp.DnpProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

enum class Health { OK, WARN, ERROR, UNKNOWN }

data class HardwareBadge(val title: String, val status: String, val detail: String, val health: Health)

data class DiagnosticsSnapshot(
    val camera: HardwareBadge,
    val printer: HardwareBadge,
    val flash: HardwareBadge,
    val paperRemaining: Int,
    val paperCapacity: Int,
)

@Singleton
class HardwareDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val camera: UniversalCanonManager,
    private val settings: SettingsRepository,
    private val printQueue: PrintQueueRepository,
    private val dnp: DnpPrinterLink,
) {
    private val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)

    fun observe(intervalMs: Long = 3_000): Flow<DiagnosticsSnapshot> = flow {
        while (true) {
            emit(snapshot())
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun snapshot(): DiagnosticsSnapshot = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        val totalPrinted = printQueue.totalPrinted()
        val (remaining, capacity) = paper(s, totalPrinted)
        DiagnosticsSnapshot(
            camera = cameraBadge(),
            printer = printerBadge(remaining, capacity),
            flash = flashBadge(),
            paperRemaining = remaining,
            paperCapacity = capacity,
        )
    }

    suspend fun printerBadge(): HardwareBadge {
        val s = settings.settings.first()
        val (remaining, capacity) = paper(s, printQueue.totalPrinted())
        return withContext(Dispatchers.IO) { printerBadge(remaining, capacity) }
    }

    /** Paper left and roll size: the printer's own live count when it reports one, otherwise the print counter. */
    private fun paper(s: AppSettings, totalPrinted: Int): Pair<Int, Int> {
        val t = dnp.state.value.telemetry
        if (t?.remaining != null && System.currentTimeMillis() - t.updatedAt < FRESH_MS) {
            return t.remaining to maxOf(t.capacity ?: ROLL_CAPACITY, t.remaining)
        }
        return (ROLL_CAPACITY - (totalPrinted - s.rollBaselinePrints)).coerceIn(0, ROLL_CAPACITY) to ROLL_CAPACITY
    }

    private suspend fun cameraBadge(): HardwareBadge {
        val battery = withTimeoutOrNull(1_500) { camera.readBatteryLevel() }
        return when (val state = camera.connectionState.value) {
            is CameraConnectionState.Connected -> {
                val c = state.camera
                val via = when (c.strategy) {
                    CameraStrategy.USB_PTP -> "USB PTP"
                    CameraStrategy.CCAPI -> "CCAPI (network)"
                    CameraStrategy.UVC_PLUS_PTP -> "HDMI capture + USB PTP"
                    CameraStrategy.TABLET_CAMERA -> "Tablet camera"
                }
                val batteryText = battery?.let { " | battery: $it" }.orEmpty()
                if (c.isFallback) {
                    val why = camera.notice.value ?: "No Canon camera detected"
                    HardwareBadge("Camera", "Tablet fallback", "$why | using ${c.name}$batteryText", Health.WARN)
                } else {
                    HardwareBadge("Camera", "Connected", "${c.name} via $via$batteryText", Health.OK)
                }
            }
            is CameraConnectionState.Connecting -> HardwareBadge("Camera", "Connecting", state.detail, Health.UNKNOWN)
            is CameraConnectionState.Failed -> HardwareBadge("Camera", "Error", state.message, Health.ERROR)
            CameraConnectionState.Disconnected -> HardwareBadge("Camera", "Idle", "Camera service not running", Health.UNKNOWN)
        }
    }

    private fun printerBadge(remaining: Int, capacity: Int): HardwareBadge {
        val paper = if (remaining <= 0) "paper roll empty" else "$remaining / $capacity prints left"
        val paperHealth = when {
            remaining <= 0 -> Health.ERROR
            remaining < LOW_PAPER_THRESHOLD -> Health.WARN
            else -> Health.OK
        }
        val found = dnp.findDevice()
        val link = dnp.state.value
        val t = link.telemetry
        return when {
            found == null -> HardwareBadge("Printer", "Not found", "DNP DS-RX1HS: not attached to USB", Health.ERROR)
            t != null && DnpProtocol.isFault(t.statusCode) -> HardwareBadge("Printer", t.statusText, "${describe(found)} | $paper", Health.ERROR)
            t == null -> HardwareBadge("Printer", "Connecting", link.problem ?: "${describe(found)} detected, reading status", Health.WARN)
            else -> HardwareBadge("Printer", if (paperHealth == Health.ERROR) "No paper" else t.statusText, "${describe(found)} | ${t.mediaName ?: "?"} | $paper", paperHealth)
        }
    }

    private fun flashBadge(): HardwareBadge {
        val found = usbManager.deviceList.values.firstOrNull(::isSerialController)
        return if (found != null) HardwareBadge("Flash / LED", "Controller found", describe(found), Health.OK)
        else HardwareBadge("Flash / LED", "Not detected", "No USB serial controller attached", Health.UNKNOWN)
    }

    private fun isSerialController(d: UsbDevice): Boolean =
        d.vendorId in SERIAL_VENDORS ||
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass in setOf(UsbConstants.USB_CLASS_COMM, UsbConstants.USB_CLASS_CDC_DATA) }

    private fun describe(d: UsbDevice): String =
        d.productName ?: "USB %04x:%04x".format(d.vendorId, d.productId)

    private companion object {
        const val LOW_PAPER_THRESHOLD = 50
        const val FRESH_MS = 120_000L
        const val ROLL_CAPACITY = 700
        val SERIAL_VENDORS = setOf(0x0403, 0x1A86, 0x10C4, 0x067B, 0x2341, 0x2A03)
    }
}
