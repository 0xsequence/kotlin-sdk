package com.polygon_wallet.polygon_kotlin_sdk.wallet

import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.AuthMode
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CommitVerifierRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CompleteAuthRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.IdentityType
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.SendTransactionRequest as WaasSendTransactionRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.UseWalletRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.WaasWalletApi
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.WalletType
import com.polygon_wallet.polygon_kotlin_sdk.models.SendTransactionRequest
import com.polygon_wallet.polygon_kotlin_sdk.models.TransactionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletPayloadBuilderTest {
    @Test
    fun commitVerifierPayloadMatchesCSdk() {
        assertEquals(
            "{\"identityType\":\"Email\",\"authMode\":\"OTP\",\"metadata\":{},\"handle\":\"user@example.com\"}",
            WaasWalletApi.CommitVerifier.encodeRequest(
                CommitVerifierRequest(
                    identityType = IdentityType.Email,
                    authMode = AuthMode.OTP,
                    metadata = emptyMap(),
                    handle = "user@example.com",
                ),
            ),
        )
    }

    @Test
    fun completeAuthPayloadMatchesCSdk() {
        assertEquals(
            "{\"identityType\":\"Email\",\"authMode\":\"OTP\",\"verifier\":\"verifier-123\",\"answer\":\"0xdeadbeef\"}",
            WaasWalletApi.CompleteAuth.encodeRequest(
                CompleteAuthRequest(
                    identityType = IdentityType.Email,
                    authMode = AuthMode.OTP,
                    verifier = "verifier-123",
                    answer = "0xdeadbeef",
                ),
            ),
        )
    }

    @Test
    fun completeAuthPayloadFromCodeMatchesParityVector() {
        val answer = WalletAuthChallenge.hashAnswer(
            challenge = "challenge",
            code = "123456",
        )

        assertEquals(
            "0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd",
            answer,
        )
        assertEquals(
            "{\"identityType\":\"Email\",\"authMode\":\"OTP\",\"verifier\":\"verifier-123\",\"answer\":\"0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd\"}",
            WaasWalletApi.CompleteAuth.encodeRequest(
                CompleteAuthRequest(
                    identityType = IdentityType.Email,
                    authMode = AuthMode.OTP,
                    verifier = "verifier-123",
                    answer = answer,
                ),
            ),
        )
    }

    @Test
    fun useWalletPayloadMatchesCSdk() {
        assertEquals(
            "{\"walletType\":\"Ethereum_EOA\",\"walletIndex\":0}",
            WaasWalletApi.UseWallet.encodeRequest(
                UseWalletRequest(
                    walletType = WalletType.Ethereum_EOA,
                    walletIndex = 0.toUByte(),
                ),
            ),
        )
    }

    @Test
    fun createWalletPayloadMatchesCSdk() {
        assertEquals(
            "{\"walletType\":\"Ethereum_EOA\"}",
            WaasWalletApi.CreateWallet.encodeRequest(
                com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CreateWalletRequest(
                    walletType = WalletType.Ethereum_EOA,
                ),
            ),
        )
    }

    @Test
    fun sendTransactionPayloadMatchesWaasRequestShape() {
        assertEquals(
            "{\"network\":\"amoy\",\"wallet\":\"0xwallet\",\"to\":\"0xabc\",\"value\":\"0\",\"data\":\"0x1234\",\"mode\":\"Native\",\"feeCeiling\":\"1000000\",\"nonce\":\"42\"}",
            WaasWalletApi.SendTransaction.encodeRequest(
                WaasSendTransactionRequest(
                    network = "amoy",
                    wallet = "0xwallet",
                    to = "0xabc",
                    value = "0",
                    data = "0x1234",
                    mode = TransactionMode.Native,
                    feeCeiling = "1000000",
                    nonce = "42",
                ),
            ),
        )
    }

    @Test
    fun walletApiEndpointsMatchCSdk() {
        assertEquals("/rpc/Wallet", WaasWalletApi.basePath)
        assertEquals("/CommitVerifier", WaasWalletApi.CommitVerifier.path)
        assertEquals("/CompleteAuth", WaasWalletApi.CompleteAuth.path)
        assertEquals("/UseWallet", WaasWalletApi.UseWallet.path)
        assertEquals("/CreateWallet", WaasWalletApi.CreateWallet.path)
        assertEquals("/SignMessage", WaasWalletApi.SignMessage.path)
        assertEquals("/SendTransaction", WaasWalletApi.SendTransaction.path)
    }
}
