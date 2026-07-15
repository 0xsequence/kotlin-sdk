package technology.polygon.omswallet.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.HexFormat

class P256EcdsaSignatureEncodingTest {
    @Test
    fun derToRawConvertsP256SignatureToFixedWidthRawSignature() {
        val der =
            hexToBytes(
                """
                30450221008002030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20
                02207f22232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40
                """,
            )
        val raw =
            hexToBytes(
                """
                8002030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20
                7f22232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40
                """,
            )

        val decoded = P256EcdsaSignatureEncoding.derToRaw(der)

        assertArrayEquals(raw, decoded)
    }

    @Test
    fun derToRawLeftPadsShortIntegersToFixedWidthRawSignature() {
        val der = hexToBytes("300702010102020080")
        val raw =
            ByteArray(64).apply {
                this[31] = 0x01
                this[63] = 0x80.toByte()
            }

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

    private fun hexToBytes(source: String): ByteArray =
        HexFormat
            .of()
            .parseHex(source.filterNot { it.isWhitespace() })
}
