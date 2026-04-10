package com.polygon_wallet.polygon_kotlin_sdk.wallet

import java.nio.charset.StandardCharsets
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

internal object WalletAuthChallenge {
    fun hashAnswer(
        challenge: String,
        code: String,
    ): String = Numeric.toHexString(
        Hash.sha3((challenge + code).toByteArray(StandardCharsets.UTF_8))
    )
}
