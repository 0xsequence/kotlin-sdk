package com.omsclient.kotlin_sdk.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OMSClientIsoTimestampsTest {
    @Test
    fun parsesUtcTimestamps() {
        assertEquals(0L, OMSClientIsoTimestamps.parseEpochMillis("1970-01-01T00:00:00Z"))
        assertEquals(123L, OMSClientIsoTimestamps.parseEpochMillis("1970-01-01T00:00:00.123Z"))
        assertEquals(123L, OMSClientIsoTimestamps.parseEpochMillis("1970-01-01T00:00:00.123456Z"))
    }

    @Test
    fun parsesOffsetTimestamps() {
        assertEquals(0L, OMSClientIsoTimestamps.parseEpochMillis("1970-01-01T02:30:00+02:30"))
        assertEquals(0L, OMSClientIsoTimestamps.parseEpochMillis("1969-12-31T21:30:00-0230"))
    }

    @Test
    fun rejectsInvalidTimestamps() {
        assertNull(OMSClientIsoTimestamps.parseEpochMillis("not-a-timestamp"))
        assertNull(OMSClientIsoTimestamps.parseEpochMillis("1970-02-31T00:00:00Z"))
        assertNull(OMSClientIsoTimestamps.parseEpochMillis("1970-01-01T00:00:00+24:00"))
    }
}
