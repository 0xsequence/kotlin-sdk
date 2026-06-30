package com.omsclient.kotlin_sdk.utils

internal object OMSClientTimestamps {
    private val defaultNonceGenerator = MonotonicNonceGenerator()

    fun nowSeconds(): Long = nowMilliseconds() / 1_000L

    fun nowMilliseconds(): Long = System.currentTimeMillis()

    fun nextNonce(): Long = defaultNonceGenerator.nextNonce()

    fun secondsFromNow(secondsFromNow: Long): Long = nowSeconds() + secondsFromNow
}
