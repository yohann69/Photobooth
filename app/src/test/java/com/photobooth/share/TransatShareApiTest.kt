package com.photobooth.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransatShareApiTest {
    /** Same vector as the backend's TestSignatureVectorSharedWithTheBooth: both signers must keep agreeing. */
    @Test
    fun signatureMatchesTheBackend() {
        val credential = BoothCredential.parse("booth-1.$SECRET")!!
        val canonical = TransatShareApi.canonical(
            "post", "/photobooth/booth/sessions/s1/finalize", "1800000000", "nonce-aaaaaaaaaaaaaaaa", """{"photo_count":3}""".toByteArray(),
        )
        assertEquals("ZSBBT88kJFyWXylOzKAQ63XxQga5e_UfZk8s_dPkuVY", TransatShareApi.signature(credential.secret, canonical))
    }

    @Test
    fun credentialNeedsAnIdAndALongSecret() {
        assertNull(BoothCredential.parse("no-dot"))
        assertNull(BoothCredential.parse("booth-1.short"))
        assertNull(BoothCredential.parse("bad*id.$SECRET"))
        assertEquals("booth-1", BoothCredential.parse("  booth-1.$SECRET ")?.keyId)
        assertEquals(32, BoothCredential.parse("booth-1. $SECRET")?.secret?.size)
    }

    private companion object {
        const val SECRET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"
    }
}
