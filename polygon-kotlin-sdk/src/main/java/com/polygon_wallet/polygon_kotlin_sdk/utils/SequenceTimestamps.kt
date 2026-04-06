package com.polygon_wallet.polygon_kotlin_sdk.utils

import java.time.Instant

internal object SequenceTimestamps {
    private val defaultNonceGenerator = MonotonicNonceGenerator()

    fun nowSeconds(): Long = Instant.now().epochSecond

    fun nowMilliseconds(): Long = System.currentTimeMillis()

    fun nextNonce(): Long = defaultNonceGenerator.nextNonce()

    fun secondsFromNow(secondsFromNow: Long): Long = nowSeconds() + secondsFromNow
}
