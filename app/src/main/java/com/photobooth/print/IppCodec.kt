package com.photobooth.print

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

/** Minimal IPP 1.1 (RFC 8010) encoder/decoder covering Print-Job and Get-Printer-Attributes. */
object IppCodec {
    const val OP_PRINT_JOB = 0x0002
    const val OP_GET_PRINTER_ATTRIBUTES = 0x000B

    private const val TAG_OPERATION_ATTRIBUTES = 0x01
    private const val TAG_JOB_ATTRIBUTES = 0x02
    private const val TAG_END = 0x03
    private const val TAG_PRINTER_ATTRIBUTES = 0x04

    private const val TAG_INTEGER = 0x21
    private const val TAG_BOOLEAN = 0x22
    private const val TAG_ENUM = 0x23
    private const val TAG_NAME = 0x42
    private const val TAG_KEYWORD = 0x44
    private const val TAG_URI = 0x45
    private const val TAG_CHARSET = 0x47
    private const val TAG_LANGUAGE = 0x48
    private const val TAG_MIME = 0x49

    const val STATE_IDLE = 3
    const val STATE_PROCESSING = 4
    const val STATE_STOPPED = 5

    sealed interface Value {
        data class Text(val value: String) : Value
        data class Number(val value: Int) : Value
    }

    class Response(val statusCode: Int, val requestId: Int, val attributes: Map<String, List<Value>>) {
        val isSuccess get() = statusCode in 0x0000..0x00FF
        fun int(name: String): Int? = (attributes[name]?.firstOrNull() as? Value.Number)?.value
        fun texts(name: String): List<String> = attributes[name].orEmpty().mapNotNull { (it as? Value.Text)?.value }
    }

    fun printJobRequest(
        requestId: Int,
        printerUri: String,
        jobName: String,
        user: String,
        mime: String,
        copies: Int,
        media: String?,
    ): ByteArray = ByteArrayOutputStream().apply {
        header(OP_PRINT_JOB, requestId)
        write(TAG_OPERATION_ATTRIBUTES)
        attr(TAG_CHARSET, "attributes-charset", "utf-8")
        attr(TAG_LANGUAGE, "attributes-natural-language", "en")
        attr(TAG_URI, "printer-uri", printerUri)
        attr(TAG_NAME, "requesting-user-name", user)
        attr(TAG_NAME, "job-name", jobName)
        attr(TAG_MIME, "document-format", mime)
        write(TAG_JOB_ATTRIBUTES)
        intAttr(TAG_INTEGER, "copies", copies)
        if (media != null) attr(TAG_KEYWORD, "media", media)
        intAttr(TAG_ENUM, "print-quality", 5)
        write(TAG_END)
    }.toByteArray()

    fun getPrinterAttributesRequest(requestId: Int, printerUri: String, requested: List<String>): ByteArray =
        ByteArrayOutputStream().apply {
            header(OP_GET_PRINTER_ATTRIBUTES, requestId)
            write(TAG_OPERATION_ATTRIBUTES)
            attr(TAG_CHARSET, "attributes-charset", "utf-8")
            attr(TAG_LANGUAGE, "attributes-natural-language", "en")
            attr(TAG_URI, "printer-uri", printerUri)
            requested.forEachIndexed { i, name -> attr(TAG_KEYWORD, if (i == 0) "requested-attributes" else "", name) }
            write(TAG_END)
        }.toByteArray()

    fun parseResponse(bytes: ByteArray): Response {
        val input = DataInputStream(bytes.inputStream())
        input.readUnsignedShort() // version
        val status = input.readUnsignedShort()
        val requestId = input.readInt()
        val attributes = LinkedHashMap<String, MutableList<Value>>()
        var current: String? = null
        while (true) {
            val tag = input.read()
            if (tag < 0) throw IOException("Truncated IPP response")
            if (tag == TAG_END) break
            if (tag <= 0x0F) {
                current = null
                continue
            }
            val nameLength = input.readUnsignedShort()
            val name = if (nameLength == 0) current else ByteArray(nameLength).also(input::readFully).toString(Charsets.UTF_8)
            val value = ByteArray(input.readUnsignedShort()).also(input::readFully)
            if (name == null) continue
            current = name
            val parsed = when (tag) {
                TAG_INTEGER, TAG_ENUM -> Value.Number(value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) })
                TAG_BOOLEAN -> Value.Number(if (value.firstOrNull()?.toInt() == 1) 1 else 0)
                else -> Value.Text(value.toString(Charsets.UTF_8))
            }
            attributes.getOrPut(name) { mutableListOf() }.add(parsed)
        }
        return Response(status, requestId, attributes)
    }

    private fun ByteArrayOutputStream.header(operation: Int, requestId: Int) {
        write(0x01)
        write(0x01)
        short(operation)
        int(requestId)
    }

    private fun ByteArrayOutputStream.attr(tag: Int, name: String, value: String) {
        write(tag)
        val n = name.toByteArray(Charsets.UTF_8)
        short(n.size)
        write(n)
        val v = value.toByteArray(Charsets.UTF_8)
        short(v.size)
        write(v)
    }

    private fun ByteArrayOutputStream.intAttr(tag: Int, name: String, value: Int) {
        write(tag)
        val n = name.toByteArray(Charsets.UTF_8)
        short(n.size)
        write(n)
        short(4)
        int(value)
    }

    private fun ByteArrayOutputStream.short(v: Int) {
        write((v shr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.int(v: Int) {
        short(v ushr 16)
        short(v and 0xFFFF)
    }
}
