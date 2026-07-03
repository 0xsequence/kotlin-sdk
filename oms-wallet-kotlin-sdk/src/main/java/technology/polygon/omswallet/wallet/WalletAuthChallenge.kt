package technology.polygon.omswallet.wallet

import technology.polygon.omswallet.utils.OMSWalletBase64Url
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
        return OMSWalletBase64Url.encodeNoPadding(digest)
    }
}
