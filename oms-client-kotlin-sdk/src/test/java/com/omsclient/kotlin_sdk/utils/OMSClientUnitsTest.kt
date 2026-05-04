package com.omsclient.kotlin_sdk.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class OMSClientUnitsTest {
    @Test
    fun formatUnitsFormatsWholeValues() {
        assertEquals("420", formatUnits(BigInteger("420000000000"), 9))
    }

    @Test
    fun formatUnitsFormatsFractionalValuesAndTrimsTrailingZeros() {
        assertEquals("0.0000000000000001", formatUnits(BigInteger("100"), 18))
        assertEquals("0.002", formatUnits(BigInteger("2000"), 6))
        assertEquals("123.45", formatUnits(BigInteger("123450000"), 6))
        assertEquals("0", formatUnits(BigInteger.ZERO, 18))
    }

    @Test
    fun formatUnitsHandlesZeroDecimalsAndNegativeValues() {
        assertEquals("420", formatUnits(BigInteger("420"), 0))
        assertEquals("-12.345", formatUnits(BigInteger("-12345"), 3))
        assertEquals("-420", formatUnits(BigInteger("-420"), 0))
    }

    @Test
    fun parseUnitsParsesWholeAndFractionalValues() {
        assertEquals(BigInteger("420000000000"), parseUnits("420", 9))
        assertEquals(BigInteger("2000"), parseUnits("0.002", 6))
        assertEquals(BigInteger("500000000000000000"), parseUnits(".5", 18))
        assertEquals(BigInteger("1000000"), parseUnits("1.", 6))
    }

    @Test
    fun parseUnitsRoundsExtraPrecisionToNearestBaseUnit() {
        assertEquals(BigInteger("123"), parseUnits("1.2345", 2))
        assertEquals(BigInteger("124"), parseUnits("1.235", 2))
        assertEquals(BigInteger("200"), parseUnits("1.995", 2))
        assertEquals(BigInteger("2"), parseUnits("1.5", 0))
        assertEquals(BigInteger("-2"), parseUnits("-1.5", 0))
        assertEquals(BigInteger.ONE, parseUnits("0.0000000000000000005", 18))
    }

    @Test
    fun parseUnitsRejectsInvalidDecimalStrings() {
        listOf("", "-", ".", "1.2.3", " 1", "+1").forEach { value ->
            assertTrue(
                runCatching { parseUnits(value, 18) }.exceptionOrNull() is IllegalArgumentException,
            )
        }
    }

    @Test
    fun helpersRejectNegativeDecimals() {
        assertTrue(
            runCatching { formatUnits(BigInteger.ONE, -1) }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { parseUnits("1", -1) }.exceptionOrNull() is IllegalArgumentException,
        )
    }
}
