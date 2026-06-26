package com.omsclient.kotlin_sdk.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OMSClientBase64UrlTest {
    @Test
    fun encodesUrlSafeBase64WithoutPadding() {
        assertEquals("", OMSClientBase64Url.encodeNoPadding(ByteArray(0)))
        assertEquals("Zg", OMSClientBase64Url.encodeNoPadding("f".toByteArray()))
        assertEquals("Zm8", OMSClientBase64Url.encodeNoPadding("fo".toByteArray()))
        assertEquals("Zm9v", OMSClientBase64Url.encodeNoPadding("foo".toByteArray()))
        assertEquals("-_8", OMSClientBase64Url.encodeNoPadding(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
    }

    @Test
    fun decodesUrlSafeBase64WithOrWithoutPadding() {
        assertArrayEquals("f".toByteArray(), OMSClientBase64Url.decode("Zg"))
        assertArrayEquals("fo".toByteArray(), OMSClientBase64Url.decode("Zm8="))
        assertArrayEquals("foo".toByteArray(), OMSClientBase64Url.decode("Zm9v"))
        assertArrayEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte()), OMSClientBase64Url.decode("-_8"))
    }
}
