package technology.polygon.omswallet.wallet

import technology.polygon.omswallet.network.OMSWalletEnvironment

internal object WalletRequestSigner {
    fun buildWalletRequestPreimage(
        endpoint: String,
        nonce: String,
        scope: String,
        payload: String,
        requestPathPrefix: String = DEFAULT_WALLET_REQUEST_PATH_PREFIX,
    ): String = "POST ${requestPathPrefix.trimEnd('/')}$endpoint\nnonce: $nonce\nscope: $scope\n\n$payload"

    fun buildWalletSignatureHeader(
        signingAlgorithm: WalletSigningAlgorithm,
        scope: String,
        credentialId: String,
        nonce: String,
        signature: String,
    ): String =
        "${OMSWalletEnvironment.walletSignatureHeaderPrefix}alg=\"${signingAlgorithm.wireValue}\"," +
            " scope=\"$scope\", cred=\"$credentialId\", nonce=$nonce, sig=\"$signature\""

    private const val DEFAULT_WALLET_REQUEST_PATH_PREFIX = "/v1/Waas"
}
