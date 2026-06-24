package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.network.OMSClientEnvironment

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
        "${OMSClientEnvironment.walletSignatureHeaderPrefix}alg=\"${signingAlgorithm.wireValue}\"," +
            "scope=\"$scope\",cred=\"$credentialId\",nonce=$nonce,sig=\"$signature\""

    private const val DEFAULT_WALLET_REQUEST_PATH_PREFIX = "/v1/Waas"
}
