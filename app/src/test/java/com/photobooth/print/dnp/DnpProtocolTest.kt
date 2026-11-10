package com.photobooth.print.dnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnpProtocolTest {
    @Test
    fun headerIsThirtyTwoBytesWithLengthAtOffset24() {
        val h = DnpProtocol.header("CNTRL", "START", 1234)
        assertEquals(32, h.size)
        assertEquals(0x1B.toByte(), h[0])
        assertEquals('P'.code.toByte(), h[1])
        assertEquals("00001234", String(h, 24, 8, Charsets.US_ASCII))
        assertEquals("START", String(h, 8, 16, Charsets.US_ASCII).trim())
    }

    @Test
    fun numberAtReadsDigitsAndRejectsGarbage() {
        assertEquals(480, DnpProtocol.numberAt("000480", 0))
        assertEquals(null, DnpProtocol.numberAt("abc", 0))
    }

    @Test
    fun faultsAreOnlyCodesFromOneThousand() {
        assertTrue(DnpProtocol.isFault(1000))
        assertFalse(DnpProtocol.isFault(900))
    }
}
