package com.polygon_wallet.polygon_kotlin_sdk.wallet

object WalletApi {
    const val defaultWalletType: String = "Ethereum_EOA"

    object Endpoints {
        const val commitVerifier: String = "/CommitVerifier"
        const val completeAuth: String = "/CompleteAuth"
        const val useWallet: String = "/UseWallet"
        const val createWallet: String = "/CreateWallet"
        const val signMessage: String = "/SignMessage"
        const val sendTransaction: String = "/SendTransaction"
    }
}
