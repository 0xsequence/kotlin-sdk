package com.omsclient.kotlin_sdk.chains

internal object OMSClientChains {
    private val bindings = linkedMapOf(
        "137" to "polygon",
        "80002" to "amoy",
    )

    fun chainNameFor(chainId: String): String =
        bindings[chainId] ?: error("Unsupported chain id: $chainId")
}
