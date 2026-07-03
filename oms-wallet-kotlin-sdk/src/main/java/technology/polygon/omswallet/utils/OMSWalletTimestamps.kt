package technology.polygon.omswallet.utils

internal object OMSWalletTimestamps {
    private val defaultNonceGenerator = MonotonicNonceGenerator()

    fun nowSeconds(): Long = nowMilliseconds() / 1_000L

    fun nowMilliseconds(): Long = System.currentTimeMillis()

    fun nextNonce(): Long = defaultNonceGenerator.nextNonce()

    fun secondsFromNow(secondsFromNow: Long): Long = nowSeconds() + secondsFromNow
}
