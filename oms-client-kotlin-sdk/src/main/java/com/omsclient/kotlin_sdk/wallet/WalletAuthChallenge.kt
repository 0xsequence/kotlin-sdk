package com.omsclient.kotlin_sdk.wallet

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal object WalletAuthChallenge {
    fun hashAnswer(
        challenge: String,
        code: String,
    ): String {
        // WAAS expects the exact byte sequence challenge + code before hashing.
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest((challenge + code).toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
