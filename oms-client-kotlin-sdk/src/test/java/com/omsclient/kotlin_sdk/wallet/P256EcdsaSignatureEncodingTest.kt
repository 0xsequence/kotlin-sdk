package com.omsclient.kotlin_sdk.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P256EcdsaSignatureEncodingTest {
    @Test
    fun derToRawConvertsP256SignatureToFixedWidthRawSignature() {
        val raw = ByteArray(64) { index -> (index + 1).toByte() }
        raw[0] = 0x80.toByte()
        raw[32] = 0x7f
        val der = P256EcdsaSignatureEncoding.rawToDer(raw)

        val decoded = P256EcdsaSignatureEncoding.derToRaw(der)

        assertArrayEquals(raw, decoded)
    }

    @Test
    fun derToRawRejectsMalformedSignatures() {
        assertTrue(
            runCatching {
                P256EcdsaSignatureEncoding.derToRaw(byteArrayOf(0x31, 0x00))
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                P256EcdsaSignatureEncoding.derToRaw(
                    byteArrayOf(
                        0x30,
                        0x06,
                        0x02,
                        0x02,
                        0x00,
                        0x01,
                        0x02,
                        0x00,
                    ),
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching {
                P256EcdsaSignatureEncoding.derToRaw(
                    byteArrayOf(
                        0x30,
                        0x07,
                        0x02,
                        0x01,
                        0x01,
                        0x02,
                        0x01,
                        0x01,
                        0x00,
                    ),
                )
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }
}
