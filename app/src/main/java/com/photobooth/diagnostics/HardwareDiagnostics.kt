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
import com.photobooth.data.settings.PrinterConnection
import com.photobooth.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
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
        val remaining = (s.rollCapacity - (totalPrinted - s.rollBaselinePrints)).coerceIn(0, s.rollCapacity)
        DiagnosticsSnapshot(
            camera = cameraBadge(),
            printer = printerBadge(s, remaining),
            flash = flashBadge(),
            paperRemaining = remaining,
            paperCapacity = s.rollCapacity,
        )
    }

    suspend fun printerBadge(): HardwareBadge {
        val s = settings.settings.first()
        val remaining = (s.rollCapacity - (printQueue.totalPrinted() - s.rollBaselinePrints)).coerceIn(0, s.rollCapacity)
        return withContext(Dispatchers.IO) { printerBadge(s, remaining) }
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
                    CameraStrategy.UVC_WEBCAM -> "USB webcam mode (UVC)"
                    CameraStrategy.TABLET_CAMERA -> "Tablet camera"
                }
                val batteryText = battery?.let { " | battery: $it" }.orEmpty()
                if (c.isFallback) {
                    HardwareBadge("Camera", "Tablet fallback", "No Canon camera detected; using ${c.name}$batteryText", Health.WARN)
                } else {
                    HardwareBadge("Camera", "Connected", "${c.name} via $via$batteryText", Health.OK)
                }
            }
            is CameraConnectionState.Connecting -> HardwareBadge("Camera", "Connecting", state.detail, Health.UNKNOWN)
            is CameraConnectionState.Failed -> HardwareBadge("Camera", "Error", state.message, Health.ERROR)
            CameraConnectionState.Disconnected -> HardwareBadge("Camera", "Idle", "Camera service not running", Health.UNKNOWN)
        }
    }

    private fun printerBadge(s: AppSettings, remaining: Int): HardwareBadge {
        val paper = if (remaining <= 0) "paper roll empty" else "$remaining / ${s.rollCapacity} prints left"
        val paperHealth = when {
            remaining <= 0 -> Health.ERROR
            remaining < LOW_PAPER_THRESHOLD -> Health.WARN
            else -> Health.OK
        }
        val model = s.printerModel.label
        return when (s.printerConnection) {
            PrinterConnection.NONE -> {
                val found = usbManager.deviceList.values.firstOrNull(::isPrinter)
                if (found != null) {
                    HardwareBadge("Printer", "Not selected", "USB printer ${describe(found)} detected; choose USB in Settings", Health.WARN)
                } else {
                    HardwareBadge("Printer", "Not configured", "$model | $paper", Health.UNKNOWN)
                }
            }
            PrinterConnection.USB -> {
                val found = usbManager.deviceList.values.firstOrNull { d ->
                    if (s.printerUsbVendorId != 0) d.vendorId == s.printerUsbVendorId && (s.printerUsbProductId == 0 || d.productId == s.printerUsbProductId)
                    else isPrinter(d)
                }
                if (found == null) HardwareBadge("Printer", "Not found", "$model: no USB printer attached", Health.ERROR)
                else HardwareBadge("Printer", if (paperHealth == Health.ERROR) "No paper" else "USB ready", "${describe(found)} | $paper", paperHealth)
            }
            PrinterConnection.NETWORK -> {
                val ipp = reachable(s.printerIp, s.printerIppPort)
                when {
                    ipp -> HardwareBadge("Printer", if (paperHealth == Health.ERROR) "No paper" else "IPP ready", "$model at ${s.printerIp}:${s.printerIppPort} | $paper", paperHealth)
                    else -> HardwareBadge("Printer", "Unreachable", "$model: ${s.printerIp.ifBlank { "no IP set" }} did not answer", Health.ERROR)
                }
            }
            PrinterConnection.NETWORK_RAW -> {
                if (reachable(s.printerIp, s.printerPort)) {
                    HardwareBadge("Printer", "Socket ready", "$model at ${s.printerIp}:${s.printerPort} | $paper", paperHealth)
                } else {
                    HardwareBadge("Printer", "Unreachable", "$model: ${s.printerIp.ifBlank { "no IP set" }} did not answer", Health.ERROR)
                }
            }
        }
    }

    private fun flashBadge(): HardwareBadge {
        val found = usbManager.deviceList.values.firstOrNull(::isSerialController)
        return if (found != null) HardwareBadge("Flash / LED", "Controller found", describe(found), Health.OK)
        else HardwareBadge("Flash / LED", "Not detected", "No USB serial controller attached", Health.UNKNOWN)
    }

    private fun isPrinter(d: UsbDevice): Boolean =
        d.vendorId == VENDOR_DNP || d.vendorId == VENDOR_CITIZEN ||
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER }

    private fun isSerialController(d: UsbDevice): Boolean =
        d.vendorId in SERIAL_VENDORS ||
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass in setOf(UsbConstants.USB_CLASS_COMM, UsbConstants.USB_CLASS_CDC_DATA) }

    private fun describe(d: UsbDevice): String =
        d.productName ?: "USB %04x:%04x".format(d.vendorId, d.productId)

    private fun reachable(host: String, port: Int): Boolean {
        if (host.isBlank()) return false
        return runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 800) } }.isSuccess
    }

    private companion object {
        const val LOW_PAPER_THRESHOLD = 50
        const val VENDOR_DNP = 0x1343
        const val VENDOR_CITIZEN = 0x1D90
        val SERIAL_VENDORS = setOf(0x0403, 0x1A86, 0x10C4, 0x067B, 0x2341, 0x2A03)
    }
}
