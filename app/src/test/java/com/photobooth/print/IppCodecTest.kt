package com.photobooth.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class IppCodecTest {
    @Test
    fun printJobRequestCarriesCopiesAndOperation() {
        val bytes = IppCodec.printJobRequest(7, "ipp://10.0.0.5:631/ipp/print", "job", "photobooth", "image/jpeg", 3, "na_index-4x6_4x6in")
        assertEquals(0x01, bytes[0].toInt())
        assertEquals(0x01, bytes[1].toInt())
        assertEquals(IppCodec.OP_PRINT_JOB, ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF))
        assertEquals(7L, bytes.copyOfRange(4, 8).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) })
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("copies"))
        assertTrue(text.contains("document-format"))
        assertTrue(text.contains("image/jpeg"))
        assertEquals(0x03, bytes.last().toInt())
    }

    @Test
    fun parsesPrinterStateAndMultiValuedReasons() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x09))
        out.write(0x04)
        enumAttr(out, "printer-state", 5)
        keyword(out, "printer-state-reasons", "media-empty-error")
        keyword(out, "", "door-open")
        out.write(0x03)

        val response = IppCodec.parseResponse(out.toByteArray())
        assertTrue(response.isSuccess)
        assertEquals(9, response.requestId)
        assertEquals(IppCodec.STATE_STOPPED, response.int("printer-state"))
        assertEquals(listOf("media-empty-error", "door-open"), response.texts("printer-state-reasons"))
    }

    @Test
    fun clientErrorIsNotSuccess() {
        val bytes = byteArrayOf(0x01, 0x01, 0x04, 0x00, 0, 0, 0, 1, 0x03)
        assertTrue(!IppCodec.parseResponse(bytes).isSuccess)
    }

    private fun enumAttr(out: ByteArrayOutputStream, name: String, value: Int) {
        out.write(0x23)
        out.write(0)
        out.write(name.length)
        out.write(name.toByteArray())
        out.write(0)
        out.write(4)
        out.write(byteArrayOf(0, 0, 0, value.toByte()))
    }

    private fun keyword(out: ByteArrayOutputStream, name: String, value: String) {
        out.write(0x44)
        out.write(0)
        out.write(name.length)
        out.write(name.toByteArray())
        out.write(0)
        out.write(value.length)
        out.write(value.toByteArray())
    }
}
