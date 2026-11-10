package com.photobooth.print.dnp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.print.PrintResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What the printer last reported; [remaining] is the paper left on the roll in 10x15 prints. */
data class DnpTelemetry(
    val statusCode: Int,
    val statusText: String,
    val mediaCode: Int?,
    val mediaName: String?,
    val remaining: Int?,
    val capacity: Int?,
    val firmware: String?,
    val serial: String?,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class DnpLinkState(val telemetry: DnpTelemetry? = null, val problem: String? = null)

/** One open USB session with the printer: blocking bulk transfers, so call it from an IO dispatcher. */
internal class DnpSession(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) {
    fun send(group: String, name: String, payload: ByteArray? = null) {
        write(DnpProtocol.header(group, name, payload?.size ?: 0))
        if (payload != null && payload.isNotEmpty()) write(payload)
    }

    fun send(group: String, name: String, text: String) = send(group, name, text.toByteArray(Charsets.ISO_8859_1))

    /** Sends a query and returns the reply text up to the first carriage return. */
    fun query(group: String, name: String): String {
        write(DnpProtocol.header(group, name))
        val length = String(readExactly(8), Charsets.ISO_8859_1).trim().toIntOrNull()
            ?: throw IOException("Réponse imprimante illisible")
        if (length !in 0..MAX_REPLY) throw IOException("Réponse imprimante inattendue ($length octets)")
        val body = String(readExactly(length), Charsets.ISO_8859_1)
        return body.substringBefore('\r').trimEnd()
    }

    fun status(): Int = DnpProtocol.numberAt(query("STATUS", ""), 0) ?: 9999
    fun freeBuffers(): Int = DnpProtocol.numberAt(query("INFO", "FREE_PBUFFER"), 3) ?: 0

    private fun write(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val n = connection.bulkTransfer(bulkOut, data, offset, minOf(CHUNK, data.size - offset), WRITE_TIMEOUT_MS)
            if (n <= 0) throw IOException("Écriture USB interrompue ($n à l'octet $offset)")
            offset += n
        }
    }

    // A reply may arrive as one packet even though it is read in two steps, so surplus bytes are kept.
    private var carry = ByteArray(0)

    private fun readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var got = minOf(carry.size, count)
        System.arraycopy(carry, 0, out, 0, got)
        carry = carry.copyOfRange(got, carry.size)
        while (got < count) {
            val buf = ByteArray(maxOf(count - got, bulkIn.maxPacketSize))
            val n = connection.bulkTransfer(bulkIn, buf, buf.size, READ_TIMEOUT_MS)
            if (n < 0) throw IOException("Lecture USB sans réponse")
            val take = minOf(n, count - got)
            System.arraycopy(buf, 0, out, got, take)
            got += take
            if (n > take) carry += buf.copyOfRange(take, n)
        }
        return out
    }

    private companion object {
        const val CHUNK = 16 * 1024
        const val WRITE_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 5_000
        const val MAX_REPLY = 4096
    }
}

/**
 * The only door to the DNP DS-RX1HS: serialises USB access, keeps the live paper/status readout and prints.
 */
@Singleton
class DnpPrinterLink @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)
    private val lock = Mutex()
    private val _state = MutableStateFlow(DnpLinkState())
    val state: StateFlow<DnpLinkState> = _state.asStateFlow()

    private var mediaOffset: Int? = null
    private val capacityByMedia = HashMap<Int, Int>()
    private var firmware: String? = null
    private var serial: String? = null
    private var lastMatte: Boolean? = null
    private var permissionAsked = false

    fun findDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull { it.vendorId == DnpProtocol.VENDOR_ID }

    /** Reads status, media and paper left. Prompts for USB permission only when [askPermission] is set. */
    suspend fun refresh(askPermission: Boolean): Result<DnpTelemetry> {
        val result = session(askPermission) { readTelemetry(it) }
        _state.value = result.fold(
            onSuccess = { DnpLinkState(it, null) },
            onFailure = { DnpLinkState(_state.value.telemetry.takeIf { findDevice() != null }, it.message) },
        )
        return result
    }

    private fun readTelemetry(s: DnpSession): DnpTelemetry {
        val status = s.status()
        if (firmware == null) firmware = runCatching { s.query("INFO", "FVER") }.getOrNull()?.also { Log.i(TAG, "DnpPrinter: firmware reply '$it'") }
        if (serial == null) serial = runCatching { s.query("INFO", "SERIAL_NUMBER") }.getOrNull()?.drop(2)?.trim()
        val mediaReply = runCatching { s.query("INFO", "MEDIA") }.getOrNull()
        var media = mediaReply?.let { r -> r.drop(4).take(3).toIntOrNull() }
        if (media != null && media and 1 == 1) media-- // odd codes are the "mark" variant of the same size

        val offset = mediaOffset ?: run {
            val major = firmware?.let { Regex("(\\d+)\\.\\d+").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
            val value = if (major >= 2) runCatching { DnpProtocol.numberAt(s.query("INFO", "MEDIA_OFFSET"), 4) }.getOrNull() ?: 0 else 50
            value.also { mediaOffset = it }
        }
        val countReply = runCatching { s.query("INFO", "MQTY") }.getOrNull()
        val remaining = countReply?.let { DnpProtocol.numberAt(it, 4) }?.let { raw -> if (raw > 0) maxOf(0, raw + 1 - offset) else 0 } // the RX1 reports one print less than it holds
        val capacity = media?.let { m ->
            capacityByMedia[m] ?: runCatching { DnpProtocol.numberAt(s.query("INFO", "MQTY_DEFAULT"), 4) }.getOrNull()
                ?.let { it - offset }?.takeIf { it > 0 }?.also { capacityByMedia[m] = it }
        }
        Log.i(TAG, "DnpPrinter: status=$status media=$mediaReply mqty=$countReply offset=$offset -> remaining=$remaining capacity=$capacity")
        return DnpTelemetry(status, DnpProtocol.statusText(status), media, media?.let(DnpProtocol::mediaName), remaining, capacity, firmware, serial)
    }

    suspend fun print(
        file: File,
        copies: Int,
        matte: Boolean,
        orientation: DnpOrientation,
        onSheet: suspend (Int) -> Unit,
    ): PrintResult {
        val planes = try {
            withContext(Dispatchers.Default) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: throw IOException("Image illisible: ${file.name}")
                try { DnpRaster.build(bitmap, orientation) } finally { bitmap.recycle() }
            }
        } catch (e: Exception) {
            return PrintResult.Failed("Préparation de l'image impossible: ${e.message}")
        }
        val outcome = session(askPermission = true) { s -> runJob(s, planes, copies, matte, onSheet) }
        return outcome.getOrElse { PrintResult.Blocked(it.message ?: "Imprimante USB indisponible") }
    }

    private suspend fun runJob(s: DnpSession, planes: DnpPlanes, copies: Int, matte: Boolean, onSheet: suspend (Int) -> Unit): PrintResult {
        val needBuffers = if (lastMatte != null && lastMatte != matte) 2 else 1
        val readyDeadline = SystemClock.elapsedRealtime() + READY_WAIT_MS
        while (true) {
            val status = s.status()
            if (DnpProtocol.isFault(status)) return PrintResult.Blocked(DnpProtocol.statusText(status))
            if (!DnpProtocol.isBusyButFine(status)) {
                val free = s.freeBuffers()
                val late = SystemClock.elapsedRealtime() > readyDeadline - BUFFER_GRACE_MS
                if (free >= needBuffers || (late && free >= 1)) break
            }
            if (SystemClock.elapsedRealtime() > readyDeadline) return PrintResult.Blocked("Imprimante occupée, nouvel essai plus tard")
            delay(1_000)
        }
        val telemetry = readTelemetry(s)
        if (telemetry.mediaCode != null && !DnpProtocol.supportsSheet(telemetry.mediaCode)) {
            return PrintResult.Blocked("Papier chargé (${telemetry.mediaName}) incompatible avec le 10x15")
        }
        if (telemetry.remaining == 0) return PrintResult.Blocked("Plus de papier")

        s.send("CNTRL", "QTY", "%07d\r".format(copies))
        s.send("CNTRL", "OVERCOAT", "%08d".format(if (matte) 1 else 0))
        s.send("IMAGE", "MULTICUT", "%08d".format(DnpProtocol.MULTICUT_6X4))
        s.send("IMAGE", "YPLANE", planes.yellow)
        s.send("IMAGE", "MPLANE", planes.magenta)
        s.send("IMAGE", "CPLANE", planes.cyan)
        s.send("CNTRL", "START")
        lastMatte = matte
        Log.i(TAG, "DnpPrinter: job sent, $copies sheet(s), matte=$matte")

        try {
            return watchJob(s, copies, onSheet)
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { runCatching { s.send("CNTRL", "CANCEL") } }
            throw e
        }
    }

    private suspend fun watchJob(s: DnpSession, copies: Int, onSheet: suspend (Int) -> Unit): PrintResult {
        delay(1_000)
        val deadline = SystemClock.elapsedRealtime() + copies * SHEET_TIMEOUT_MS + 120_000
        var started = false
        var reported = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = s.status()
            if (status != 0) started = true
            val left = runCatching { DnpProtocol.numberAt(s.query("INFO", "PQTY"), 4) }.getOrNull()
            val printed = if (left != null && (started || left == 0)) (copies - left).coerceIn(0, copies) else reported
            if (printed > reported) {
                onSheet(printed - reported)
                reported = printed
            }
            if (DnpProtocol.isFault(status)) {
                runCatching { s.send("CNTRL", "CANCEL") } // otherwise it could resume later and print sheets we resend
                val reason = DnpProtocol.statusText(status)
                return if (reported > 0) PrintResult.Partial(reported, reason) else PrintResult.Blocked(reason)
            }
            if (status == 0 && (started || left == 0)) {
                if (reported < copies && left == 0) onSheet(copies - reported)
                runCatching { _state.value = DnpLinkState(readTelemetry(s), null) }
                return PrintResult.Success
            }
            delay(1_000)
        }
        runCatching { s.send("CNTRL", "CANCEL") }
        return if (reported > 0) PrintResult.Partial(reported, "Délai d'impression dépassé") else PrintResult.Failed("Délai d'impression dépassé")
    }

    private suspend fun <T> session(askPermission: Boolean, block: suspend (DnpSession) -> T): Result<T> = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val device = findDevice() ?: throw IOException("Imprimante DNP non détectée sur l'USB")
                if (!usbManager.hasPermission(device)) {
                    if (!askPermission && permissionAsked) throw IOException("Autorisation USB requise pour ${device.productName ?: "l'imprimante"}")
                    permissionAsked = true
                    if (!requestPermission(device)) throw IOException("Autorisation USB refusée pour ${device.productName ?: "l'imprimante"}")
                }
                val (iface, endpoints) = findBulkInterface(device) ?: throw IOException("Interface d'impression USB introuvable")
                val connection = usbManager.openDevice(device) ?: throw IOException("Ouverture USB impossible")
                try {
                    if (!connection.claimInterface(iface, true)) throw IOException("Interface USB occupée")
                    try {
                        block(DnpSession(connection, endpoints.first, endpoints.second))
                    } finally {
                        connection.releaseInterface(iface)
                    }
                } finally {
                    connection.close()
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                if (it !is IOException) Log.w(TAG, "DnpPrinter: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private fun findBulkInterface(device: UsbDevice): Pair<UsbInterface, Pair<UsbEndpoint, UsbEndpoint>>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val eps = (0 until iface.endpointCount).map { iface.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val output = eps.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            if (input != null && output != null) return iface to (input to output)
        }
        return null
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean {
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
                context, 2, Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(device, pi)
            return withTimeoutOrNull(30_000) { result.await() } ?: false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        const val ACTION_PERMISSION = "com.photobooth.DNP_PERMISSION"
        const val READY_WAIT_MS = 45_000L
        const val BUFFER_GRACE_MS = 30_000L
        const val SHEET_TIMEOUT_MS = 90_000L
    }
}
