package com.photobooth.print

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Turns a print request into the byte stream a given USB printer family expects. */
interface UsbPrinterProtocol {
    val name: String
    fun supports(device: UsbDevice): Boolean
    fun encode(request: PrintRequest, jpeg: ByteArray): List<ByteArray>
}

/**
 * USB-host printing: device discovery, permission, interface claim and chunked bulk-OUT writes. The raster
 * command stream of DNP (DS-RX1HS/QW410/DS620) and Citizen (CY-02/CZ-01) printers is proprietary and needs
 * the vendor specification, so those families are only usable once a matching [UsbPrinterProtocol] is
 * registered; without one the queue keeps the jobs and reports why.
 */
class UsbPrinterBackend(
    private val context: Context,
    private val usbManager: UsbManager,
    private val vendorId: Int,
    private val productId: Int,
    private val protocols: List<UsbPrinterProtocol> = emptyList(),
) : PrintBackend {
    override val label = "USB"

    override suspend fun check(): PrinterReadiness = withContext(Dispatchers.IO) {
        val device = findDevice() ?: return@withContext PrinterReadiness.NotReady("Aucune imprimante USB détectée")
        if (!ensurePermission(device)) return@withContext PrinterReadiness.NotReady("Autorisation USB refusée pour ${describe(device)}")
        if (protocols.none { it.supports(device) }) {
            return@withContext PrinterReadiness.NotReady(
                "${describe(device)} détectée, mais le protocole USB DNP/Citizen n'est pas installé: utilisez l'impression réseau (IPP)",
            )
        }
        PrinterReadiness.Ready
    }

    override suspend fun print(request: PrintRequest, onSheetPrinted: suspend (Int) -> Unit): PrintResult = withContext(Dispatchers.IO) {
        val device = findDevice() ?: return@withContext PrintResult.Blocked("Imprimante USB déconnectée")
        val protocol = protocols.firstOrNull { it.supports(device) }
            ?: return@withContext PrintResult.Blocked("Protocole USB indisponible pour ${describe(device)}")
        val jpeg = request.file.readBytes()
        var printed = 0
        repeat(request.copies) {
            try {
                write(device, protocol.encode(request, jpeg))
            } catch (e: IOException) {
                val reason = "Écriture USB interrompue: ${e.message}"
                return@withContext if (printed > 0) PrintResult.Partial(printed, reason) else PrintResult.Blocked(reason)
            }
            printed++
            onSheetPrinted(1)
        }
        PrintResult.Success
    }

    private fun write(device: UsbDevice, chunks: List<ByteArray>) {
        val connection = usbManager.openDevice(device) ?: throw IOException("openDevice a échoué")
        try {
            val (iface, out) = (0 until device.interfaceCount).map { device.getInterface(it) }.firstNotNullOfOrNull { i ->
                (0 until i.endpointCount).map { i.getEndpoint(it) }
                    .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
                    ?.let { i to it }
            } ?: throw IOException("Aucun endpoint bulk OUT")
            if (!connection.claimInterface(iface, true)) throw IOException("claimInterface a échoué")
            try {
                for (chunk in chunks) writeFully(connection, out, chunk)
            } finally {
                connection.releaseInterface(iface)
            }
        } finally {
            connection.close()
        }
    }

    private fun writeFully(connection: android.hardware.usb.UsbDeviceConnection, endpoint: UsbEndpoint, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val n = connection.bulkTransfer(endpoint, data, offset, minOf(CHUNK, data.size - offset), WRITE_TIMEOUT_MS)
            if (n <= 0) throw IOException("bulkTransfer=$n à l'offset $offset")
            offset += n
        }
    }

    private fun findDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull { d ->
        if (vendorId != 0) d.vendorId == vendorId && (productId == 0 || d.productId == productId)
        else d.vendorId == VENDOR_DNP || d.vendorId == VENDOR_CITIZEN ||
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_PRINTER }
    }

    private suspend fun ensurePermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val d = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (d?.deviceName == device.deviceName) result.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val pi = PendingIntent.getBroadcast(
                context, 1, Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(device, pi)
            return withTimeoutOrNull(30_000) { result.await() } ?: false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun describe(d: UsbDevice) = d.productName ?: "USB %04x:%04x".format(d.vendorId, d.productId)

    private companion object {
        const val ACTION_PERMISSION = "com.photobooth.USB_PRINTER_PERMISSION"
        const val VENDOR_DNP = 0x1343
        const val VENDOR_CITIZEN = 0x1D90
        const val CHUNK = 16 * 1024
        const val WRITE_TIMEOUT_MS = 10_000
    }
}
