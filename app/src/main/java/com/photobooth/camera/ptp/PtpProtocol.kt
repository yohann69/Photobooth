package com.photobooth.camera.ptp

import java.io.IOException
import java.nio.ByteBuffer

internal object Ptp {
    const val HEADER_SIZE = 12
    const val CONTAINER_COMMAND = 1
    const val CONTAINER_DATA = 2
    const val CONTAINER_RESPONSE = 3
    const val CONTAINER_EVENT = 4

    const val OC_GET_DEVICE_INFO = 0x1001
    const val OC_OPEN_SESSION = 0x1002
    const val OC_CLOSE_SESSION = 0x1003
    const val OC_GET_OBJECT_HANDLES = 0x1007
    const val OC_GET_OBJECT_INFO = 0x1008
    const val OC_GET_OBJECT = 0x1009
    const val OC_INITIATE_CAPTURE = 0x100E

    const val RC_OK = 0x2001
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_SESSION_ALREADY_OPEN = 0x201E

    const val OFC_EXIF_JPEG = 0x3801
}

internal object CanonEos {
    const val OC_GET_PARTIAL_OBJECT = 0x9107
    const val OC_SET_DEVICE_PROP_VALUE_EX = 0x9110
    const val OC_SET_REMOTE_MODE = 0x9114
    const val OC_SET_EVENT_MODE = 0x9115
    const val OC_GET_EVENT = 0x9116
    const val OC_TRANSFER_COMPLETE = 0x9117
    const val OC_PC_HDD_CAPACITY = 0x911A
    const val OC_KEEP_DEVICE_ON = 0x911D
    const val OC_REMOTE_RELEASE = 0x910F
    const val OC_REMOTE_RELEASE_ON = 0x9128
    const val OC_REMOTE_RELEASE_OFF = 0x9129
    const val OC_INITIATE_VIEWFINDER = 0x9151
    const val OC_GET_VIEWFINDER_DATA = 0x9153
    const val OC_DO_AF = 0x9154

    const val RC_NOT_READY = 0xA102

    const val PROP_EVF_OUTPUT_DEVICE = 0xD1B0
    const val PROP_EVF_MODE = 0xD1B3
    const val PROP_CAPTURE_DESTINATION = 0xD11C

    const val CAPTURE_DEST_HOST = 4
    const val EVF_OUTPUT_PC = 2
    const val EVF_OUTPUT_OFF = 0

    const val EC_OBJECT_ADDED_EX = 0xC181
    const val EC_REQUEST_OBJECT_TRANSFER = 0xC186
    const val EC_OBJECT_ADDED_EX64 = 0xC1A9

    const val VIEWFINDER_BUFFER_SIZE = 0x00100000
}

internal class PtpException(val responseCode: Int, message: String) : IOException(message)

internal class PtpResponse(val code: Int, val params: IntArray) {
    val isOk get() = code == Ptp.RC_OK
}

/** Receives data-phase payload; must consume [ByteBuffer.remaining] bytes (the buffer is reused afterwards). */
internal fun interface PtpDataSink {
    fun write(buffer: ByteBuffer)
}

internal class GrowableBuffer(initialCapacity: Int) : PtpDataSink {
    var array = ByteArray(initialCapacity)
        private set
    var size = 0
        private set

    fun reset() {
        size = 0
    }

    override fun write(buffer: ByteBuffer) {
        val n = buffer.remaining()
        if (size + n > array.size) array = array.copyOf(maxOf(array.size * 2, size + n))
        buffer.get(array, size, n)
        size += n
    }
}

internal fun ByteArray.u16(i: Int): Int = (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u32(i: Int): Int =
    u16(i) or (u16(i + 2) shl 16)

internal class PtpReader(private val bytes: ByteArray, private var pos: Int = 0) {
    fun u16(): Int = bytes.u16(pos).also { pos += 2 }
    fun u32(): Int = bytes.u32(pos).also { pos += 4 }

    fun string(): String {
        val chars = bytes[pos++].toInt() and 0xFF
        if (chars == 0) return ""
        val text = String(bytes, pos, (chars - 1) * 2, Charsets.UTF_16LE)
        pos += chars * 2
        return text
    }

    fun u16Array(): Set<Int> {
        val count = u32()
        val out = HashSet<Int>(count)
        repeat(count) { out += u16() }
        return out
    }
}

internal class PtpDeviceInfo(
    val manufacturer: String,
    val model: String,
    val vendorExtensionId: Int,
    val operations: Set<Int>,
) {
    companion object {
        fun parse(bytes: ByteArray, length: Int): PtpDeviceInfo {
            val r = PtpReader(bytes.copyOf(length))
            r.u16() // standard version
            val vendorExt = r.u32()
            r.u16() // vendor extension version
            r.string() // vendor extension description
            r.u16() // functional mode
            val ops = r.u16Array()
            r.u16Array() // events
            r.u16Array() // device properties
            r.u16Array() // capture formats
            r.u16Array() // image formats
            val manufacturer = r.string()
            val model = r.string()
            return PtpDeviceInfo(manufacturer, model, vendorExt, ops)
        }
    }
}

internal class EosObject(val oid: Int, val ofc: Int, val size: Long, val name: String) {
    val isJpeg: Boolean
        get() {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext == "jpg" || ext == "jpeg" || (ext.isEmpty() && (ofc == Ptp.OFC_EXIF_JPEG || ofc == 0))
        }
}

/** Parses the record list returned by Canon EOS GetEvent (0x9116); only object-added records are kept. */
internal fun parseEosEvents(data: ByteArray, length: Int): List<EosObject> {
    val out = ArrayList<EosObject>(2)
    var i = 0
    while (i + 8 <= length) {
        val size = data.u32(i)
        val type = data.u32(i + 4)
        if (size < 8 || i + size > length || type == 0) break
        if (type == CanonEos.EC_OBJECT_ADDED_EX64 && size >= 0x1C) {
            // oid@8, ofc@12, 64-bit size@20, name (often empty) at 0x28
            val nameStart = i + 0x28
            var nameEnd = nameStart
            while (nameEnd < i + size && data[nameEnd] != 0.toByte()) nameEnd++
            out += EosObject(
                oid = data.u32(i + 8),
                ofc = data.u16(i + 12),
                size = data.u32(i + 20).toLong() and 0xFFFFFFFFL,
                name = if (nameEnd > nameStart) String(data, nameStart, nameEnd - nameStart, Charsets.US_ASCII) else "",
            )
        } else if ((type == CanonEos.EC_OBJECT_ADDED_EX || type == CanonEos.EC_REQUEST_OBJECT_TRANSFER) && size >= 0x20) {
            val nameStart = i + 0x28
            var nameEnd = nameStart
            while (nameEnd < i + size && data[nameEnd] != 0.toByte()) nameEnd++
            out += EosObject(
                oid = data.u32(i + 8),
                ofc = data.u16(i + 0x10),
                size = data.u32(i + 0x1C).toLong() and 0xFFFFFFFFL,
                name = if (nameEnd > nameStart) String(data, nameStart, nameEnd - nameStart, Charsets.US_ASCII) else "",
            )
        }
        i += size
    }
    return out
}

/** One line per GetEvent record (type, size and the first bytes), for diagnosing events the parser does not know. */
internal fun describeEosRecords(data: ByteArray, length: Int): String {
    val out = StringBuilder()
    var i = 0
    while (i + 8 <= length) {
        val size = data.u32(i)
        val type = data.u32(i + 4)
        if (size < 8 || i + size > length || type == 0) break
        val hex = (i until minOf(i + size, i + 64)).joinToString("") { "%02x".format(data[it]) }
        out.append("\n   type=0x%04x size=%d %s".format(type, size, hex))
        i += size
    }
    return out.toString()
}

/** Compact "prop=value" list of the property-change records (0xC189) in a GetEvent reply. */
internal fun summarizeEosProps(data: ByteArray, length: Int): String {
    val out = ArrayList<String>()
    var i = 0
    while (i + 8 <= length) {
        val size = data.u32(i)
        val type = data.u32(i + 4)
        if (size < 8 || i + size > length || type == 0) break
        when {
            type == 0xC189 && size >= 16 -> out += "%04x=%x".format(data.u32(i + 8), data.u32(i + 12))
            type == 0xC18A && size >= 12 -> out += "avail:%04x".format(data.u32(i + 8))
            else -> out += "ev%04x(%d)".format(type, size)
        }
        i += size
    }
    return out.joinToString(" ")
}
