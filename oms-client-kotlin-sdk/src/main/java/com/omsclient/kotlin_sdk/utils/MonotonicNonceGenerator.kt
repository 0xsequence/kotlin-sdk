package com.omsclient.kotlin_sdk.utils

import java.util.concurrent.atomic.AtomicLong

internal class MonotonicNonceGenerator(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val lastNonce = AtomicLong(0)

    fun nextNonce(): Long {
        while (true) {
            val previous = lastNonce.get()
            val now = currentTimeMillis()
            require(now >= 0) { "currentTimeMillis returned a negative value: $now" }

            val next = if (now <= previous) previous + 1 else now
            if (lastNonce.compareAndSet(previous, next)) {
                return next
            }
        }
    }
}
