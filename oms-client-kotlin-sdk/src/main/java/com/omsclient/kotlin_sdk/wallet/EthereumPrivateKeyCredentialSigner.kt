package com.omsclient.kotlin_sdk.wallet

internal class EthereumPrivateKeyCredentialSigner(
    private val privateKeyFactory: () -> ByteArray = WalletRequestSigner::generatePrivateKeyBytes,
    private val nonceGenerator: () -> String,
) : CredentialSigner {
    override val signingAlgorithm: WalletSigningAlgorithm = WalletSigningAlgorithm.ECDSA_P256K_EIP191

    private var privateKey: ByteArray? = null
    private var cleared: Boolean = false

    override suspend fun credentialId(): String = WalletRequestSigner.walletAddressFromPrivateKey(requirePrivateKey())

    override suspend fun nextNonce(): String = nonceGenerator()

    override suspend fun sign(preimage: String): String = WalletRequestSigner.signWalletRequestPreimage(requirePrivateKey(), preimage)

    override fun hasCredential(): Boolean = !cleared

    override fun clear() {
        privateKey?.fill(0)
        privateKey = null
        cleared = true
    }

    private fun requirePrivateKey(): ByteArray {
        val existing = privateKey
        if (existing != null) {
            return existing
        }
        cleared = false
        return privateKeyFactory().also { privateKey = it }
    }
}
