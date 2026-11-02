package com.photobooth.camera.ptp

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
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
    ): PtpResponse {
        val txId = nextTransactionId++
        sendCommand(opCode, txId, params)
        if (outPayload != null) sendData(opCode, txId, outPayload)

        var received = readChunk()
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
                    return PtpResponse(code, IntArray(count) { inBuffer.getInt(Ptp.HEADER_SIZE + it * 4) })
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
    ): PtpResponse {
        val r = execute(opCode, params, outPayload, sink)
        if (!r.isOk) throw PtpException(r.code, "PTP op 0x${opCode.toString(16)} failed: 0x${r.code.toString(16)}")
        return r
    }

    private fun receiveData(containerLength: Long, sink: PtpDataSink?) {
        if (containerLength < Ptp.HEADER_SIZE) throw IOException("Bad PTP data container length $containerLength")
        var remaining = containerLength - Ptp.HEADER_SIZE

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
    private fun readChunk(): Int {
        repeat(MAX_ZLP_SKIPS) {
            inBuffer.clear()
            if (!inRequest.queue(inBuffer)) throw IOException("UsbRequest.queue failed")
            val completed: UsbRequest? = try {
                connection.requestWait(READ_TIMEOUT_MS)
            } catch (e: TimeoutException) {
                inRequest.cancel()
                throw IOException("PTP read timed out", e)
            }
            if (completed == null) throw IOException("USB device disconnected")
            if (completed !== inRequest) throw IOException("Unexpected USB request completion")
            val n = inBuffer.position()
            inBuffer.flip()
            if (n > 0) return n
        }
        throw IOException("Too many zero-length USB packets")
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
        val NO_PARAMS = IntArray(0)
    }
}
