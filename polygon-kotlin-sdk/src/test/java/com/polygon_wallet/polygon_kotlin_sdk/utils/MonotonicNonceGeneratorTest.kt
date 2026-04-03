package com.polygon_wallet.polygon_kotlin_sdk.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonotonicNonceGeneratorTest {
    @Test
    fun nextNonceMonotonicallyIncreasesWhenClockStalls() {
        var current = 1_710_000_000_000L
        val generator = MonotonicNonceGenerator { current }

        val first = generator.nextNonce()
        val second = generator.nextNonce()
        val third = generator.nextNonce()

        assertEquals(1_710_000_000_000L, first)
        assertEquals(1_710_000_000_001L, second)
        assertEquals(1_710_000_000_002L, third)
    }

    @Test
    fun nextNonceFollowsClockWhenTimeMovesForward() {
        var current = 1_710_000_000_000L
        val generator = MonotonicNonceGenerator { current }

        val first = generator.nextNonce()
        current += 50
        val second = generator.nextNonce()

        assertTrue(second > first)
        assertEquals(1_710_000_000_050L, second)
    }
}
