package com.photobooth.camera.ptp

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.util.Log
import com.photobooth.core.PhotoboothLog.TAG
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeoutException

/**
 * Blocking PTP transport over a pair of bulk endpoints. Not thread-safe: callers serialize
 * transactions. Inbound data is received into one direct buffer and handed to a [PtpDataSink]
 * without intermediate heap copies.
 */
internal class PtpTransport(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
) : AutoCloseable {

    private var nextTransactionId = 0
    private var lastDataBytes = 0L

    /** Logs every transaction (operation, parameters, response, data size); off by default because live view is chatty. */
    @Volatile var trace = false
    /**
     * Set once a transaction was abandoned half-way (timeout, I/O error): the camera may still answer it later, so
     * every further transaction would read someone else's reply. The owner must reconnect (see [recover]).
     */
    @Volatile var broken = false
        private set
    private val inRequest = UsbRequest()
    private val inBuffer: ByteBuffer = ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    init {
        check(inRequest.initialize(connection, bulkIn)) { "UsbRequest.initialize failed" }
    }

    fun resetTransactionCounter() {
        nextTransactionId = 0
    }

    fun execute(
        opCode: Int,
        params: IntArray = NO_PARAMS,
        outPayload: ByteArray? = null,
        sink: PtpDataSink? = null,
        readTimeoutMs: Long = READ_TIMEOUT_MS,
    ): PtpResponse {
        if (broken) throw IOException("PTP link lost a transaction; reconnect needed")
        try {
            return transact(opCode, params, outPayload, sink, readTimeoutMs)
        } catch (e: PtpException) {
            throw e
        } catch (e: IOException) {
            broken = true
            throw e
        } catch (e: IllegalStateException) {
            broken = true
            throw IOException("USB request state: ${e.message}", e)
        }
    }

    private fun transact(
        opCode: Int,
        params: IntArray,
        outPayload: ByteArray?,
        sink: PtpDataSink?,
        readTimeoutMs: Long,
    ): PtpResponse {
        val txId = nextTransactionId++
        val startedAt = if (trace) System.nanoTime() else 0L
        lastDataBytes = 0
        if (trace) Log.i(TAG, "PTP> tx=$txId op=0x${opCode.toString(16)} params=${params.joinToString(",") { "0x" + it.toUInt().toString(16) }} out=${outPayload?.size ?: 0}B")
        sendCommand(opCode, txId, params)
        if (outPayload != null) sendData(opCode, txId, outPayload)

        var received = readChunk(readTimeoutMs)
        while (true) {
            if (received < Ptp.HEADER_SIZE) throw IOException("Short PTP container ($received bytes)")
            val length = inBuffer.getInt(0).toLong() and 0xFFFFFFFFL
            val type = inBuffer.getShort(4).toInt() and 0xFFFF
            val code = inBuffer.getShort(6).toInt() and 0xFFFF
            when (type) {
                Ptp.CONTAINER_DATA -> {
                    receiveData(length, sink)
                    received = readChunk()
                }
                Ptp.CONTAINER_RESPONSE -> {
                    val count = ((received - Ptp.HEADER_SIZE) / 4).coerceAtMost(5)
                    val response = PtpResponse(code, IntArray(count) { inBuffer.getInt(Ptp.HEADER_SIZE + it * 4) })
                    if (trace) {
                        Log.i(TAG, "PTP< tx=$txId op=0x${opCode.toString(16)} response=0x${code.toString(16)} params=${response.params.joinToString(",") { "0x" + it.toUInt().toString(16) }} data=${lastDataBytes}B in ${(System.nanoTime() - startedAt) / 1_000_000}ms")
                    }
                    return response
                }
                else -> received = readChunk()
            }
        }
    }

    fun executeOk(
        opCode: Int,
        params: IntArray = NO_PARAMS,
        outPayload: ByteArray? = null,
        sink: PtpDataSink? = null,
        readTimeoutMs: Long = READ_TIMEOUT_MS,
    ): PtpResponse {
        val r = execute(opCode, params, outPayload, sink, readTimeoutMs)
        if (!r.isOk) throw PtpException(r.code, "PTP op 0x${opCode.toString(16)} failed: 0x${r.code.toString(16)}")
        return r
    }

    private fun receiveData(containerLength: Long, sink: PtpDataSink?) {
        if (containerLength < Ptp.HEADER_SIZE) throw IOException("Bad PTP data container length $containerLength")
        var remaining = containerLength - Ptp.HEADER_SIZE
        lastDataBytes = remaining

        // First chunk: header already consumed, payload follows at offset 12.
        val firstAvailable = inBuffer.limit() - Ptp.HEADER_SIZE
        val firstTake = minOf(firstAvailable.toLong(), remaining).toInt()
        inBuffer.position(Ptp.HEADER_SIZE)
        inBuffer.limit(Ptp.HEADER_SIZE + firstTake)
        sink?.write(inBuffer)
        remaining -= firstTake

        while (remaining > 0) {
            val n = readChunk()
            val take = minOf(n.toLong(), remaining).toInt()
            inBuffer.limit(take)
            sink?.write(inBuffer)
            remaining -= take
        }
    }

    /** Reads one USB transfer into [inBuffer] (position 0, limit = bytes read); skips zero-length packets. */
    private fun readChunk(timeoutMs: Long = READ_TIMEOUT_MS): Int {
        repeat(MAX_ZLP_SKIPS) {
            inBuffer.clear()
            if (!inRequest.queue(inBuffer)) throw IOException("UsbRequest.queue failed")
            val completed: UsbRequest? = try {
                connection.requestWait(timeoutMs)
            } catch (e: TimeoutException) {
                reapCancelled()
                throw IOException("PTP read timed out after $timeoutMs ms", e)
            }
            if (completed == null) throw IOException("USB device disconnected")
            if (completed !== inRequest) throw IOException("Unexpected USB request completion")
            val n = inBuffer.position()
            inBuffer.flip()
            if (n > 0) return n
        }
        throw IOException("Too many zero-length USB packets")
    }

    /** Cancels the queued read and waits for it to come back, so the request can be queued again. */
    private fun reapCancelled() {
        inRequest.cancel()
        runCatching { connection.requestWait(REAP_TIMEOUT_MS) }
    }

    /**
     * Puts a camera left mid-transaction (by a timeout or a killed app) back into a state where it accepts commands:
     * PTP class reset, endpoint halts cleared, and any late reply still waiting on the bulk-in pipe thrown away.
     */
    fun recover(interfaceId: Int) {
        val reset = connection.controlTransfer(0x21, PTP_DEVICE_RESET, 0, interfaceId, null, 0, CONTROL_TIMEOUT_MS)
        val haltIn = connection.controlTransfer(0x02, USB_CLEAR_FEATURE, ENDPOINT_HALT, bulkIn.address, null, 0, CONTROL_TIMEOUT_MS)
        val haltOut = connection.controlTransfer(0x02, USB_CLEAR_FEATURE, ENDPOINT_HALT, bulkOut.address, null, 0, CONTROL_TIMEOUT_MS)
        val drained = drain()
        Log.i(TAG, "PTP recover: reset=$reset clearHaltIn=$haltIn clearHaltOut=$haltOut drained=${drained}B")
        broken = false
        nextTransactionId = 0
    }

    /** Throws away whatever the camera still had queued for the host; returns the number of bytes dropped. */
    fun drain(): Int {
        var total = 0
        repeat(MAX_DRAIN_READS) {
            inBuffer.clear()
            if (!inRequest.queue(inBuffer)) return total
            val completed = try {
                connection.requestWait(DRAIN_TIMEOUT_MS)
            } catch (e: TimeoutException) {
                reapCancelled()
                return total
            }
            if (completed == null) return total
            total += inBuffer.position()
        }
        return total
    }

    private fun sendCommand(opCode: Int, txId: Int, params: IntArray) {
        val length = Ptp.HEADER_SIZE + params.size * 4
        val b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(length).putShort(Ptp.CONTAINER_COMMAND.toShort()).putShort(opCode.toShort()).putInt(txId)
        params.forEach { b.putInt(it) }
        write(b.array(), length)
    }

    private fun sendData(opCode: Int, txId: Int, payload: ByteArray) {
        val length = Ptp.HEADER_SIZE + payload.size
        val b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(length).putShort(Ptp.CONTAINER_DATA.toShort()).putShort(opCode.toShort()).putInt(txId).put(payload)
        write(b.array(), length)
    }

    private fun write(data: ByteArray, length: Int) {
        val written = connection.bulkTransfer(bulkOut, data, length, WRITE_TIMEOUT_MS)
        if (written != length) throw IOException("USB bulk write failed ($written/$length)")
        if (length % bulkOut.maxPacketSize == 0) connection.bulkTransfer(bulkOut, ByteArray(0), 0, WRITE_TIMEOUT_MS)
    }

    override fun close() {
        runCatching { inRequest.cancel() }
        runCatching { inRequest.close() }
    }

    private companion object {
        const val CHUNK_BYTES = 16 * 1024
        const val READ_TIMEOUT_MS = 5_000L
        const val WRITE_TIMEOUT_MS = 3_000
        const val MAX_ZLP_SKIPS = 8
        const val REAP_TIMEOUT_MS = 500L
        const val DRAIN_TIMEOUT_MS = 150L
        const val MAX_DRAIN_READS = 64
        const val CONTROL_TIMEOUT_MS = 1_000
        const val PTP_DEVICE_RESET = 0x66
        const val USB_CLEAR_FEATURE = 0x01
        const val ENDPOINT_HALT = 0
        val NO_PARAMS = IntArray(0)
    }
}
