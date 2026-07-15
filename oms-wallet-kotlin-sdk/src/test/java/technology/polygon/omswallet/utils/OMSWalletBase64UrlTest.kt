package technology.polygon.omswallet.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OMSWalletBase64UrlTest {
    @Test
    fun encodesUrlSafeBase64WithoutPadding() {
        assertEquals("", OMSWalletBase64Url.encodeNoPadding(ByteArray(0)))
        assertEquals("Zg", OMSWalletBase64Url.encodeNoPadding("f".toByteArray()))
        assertEquals("Zm8", OMSWalletBase64Url.encodeNoPadding("fo".toByteArray()))
        assertEquals("Zm9v", OMSWalletBase64Url.encodeNoPadding("foo".toByteArray()))
        assertEquals("-_8", OMSWalletBase64Url.encodeNoPadding(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
    }

    @Test
    fun decodesUrlSafeBase64WithOrWithoutPadding() {
        assertArrayEquals("f".toByteArray(), OMSWalletBase64Url.decode("Zg"))
        assertArrayEquals("fo".toByteArray(), OMSWalletBase64Url.decode("Zm8="))
        assertArrayEquals("foo".toByteArray(), OMSWalletBase64Url.decode("Zm9v"))
        assertArrayEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte()), OMSWalletBase64Url.decode("-_8"))
    }
}
