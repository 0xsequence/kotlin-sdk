package com.polygon_wallet.polygon_kotlin_sdk.wallet

import com.polygon_wallet.polygon_kotlin_sdk.models.SendTransactionRequest
import com.polygon_wallet.polygon_kotlin_sdk.models.TransactionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletPayloadBuilderTest {
    @Test
    fun commitVerifierPayloadMatchesCSdk() {
        assertEquals(
            "{\"params\":{\"identityType\":\"Email\",\"authMode\":\"OTP\",\"handle\":\"user@example.com\"}}",
            WalletPayloadBuilder.buildCommitVerifierPayload("user@example.com"),
        )
    }

    @Test
    fun completeAuthPayloadMatchesCSdk() {
        assertEquals(
            "{\"params\":{\"identityType\":\"Email\",\"authMode\":\"OTP\",\"verifier\":\"verifier-123\",\"answer\":\"0xdeadbeef\"}}",
            WalletPayloadBuilder.buildCompleteAuthPayload(
                verifier = "verifier-123",
                answer = "0xdeadbeef",
            ),
        )
    }

    @Test
    fun completeAuthPayloadFromCodeMatchesParityVector() {
        val answer = WalletPayloadBuilder.hashChallengeAnswer(
            challenge = "challenge",
            code = "123456",
        )

        assertEquals(
            "0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd",
            answer,
        )
        assertEquals(
            "{\"params\":{\"identityType\":\"Email\",\"authMode\":\"OTP\",\"verifier\":\"verifier-123\",\"answer\":\"0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd\"}}",
            WalletPayloadBuilder.buildCompleteAuthPayloadFromCode(
                verifier = "verifier-123",
                challenge = "challenge",
                code = "123456",
            ),
        )
    }

    @Test
    fun useWalletPayloadMatchesCSdk() {
        assertEquals(
            "{\"params\":{\"walletType\":\"Ethereum_EOA\",\"walletIndex\":0}}",
            WalletPayloadBuilder.buildUseWalletPayload(WalletApi.defaultWalletType),
        )
    }

    @Test
    fun createWalletPayloadMatchesCSdk() {
        assertEquals(
            "{\"params\":{\"walletType\":\"Ethereum_EOA\"}}",
            WalletPayloadBuilder.buildCreateWalletPayload(WalletApi.defaultWalletType),
        )
    }

    @Test
    fun sendTransactionPayloadMatchesWaasRequestShape() {
        assertEquals(
            "{\"params\":{\"mode\":\"Native\",\"wallet\":\"0xwallet\",\"network\":\"amoy\",\"to\":\"0xabc\",\"value\":\"0\",\"data\":\"0x1234\",\"feeCeiling\":\"1000000\",\"nonce\":\"42\"}}",
            WalletPayloadBuilder.buildSendTransactionPayload(
                wallet = "0xwallet",
                network = "amoy",
                request = SendTransactionRequest(
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
        assertEquals("/CommitVerifier", WalletApi.Endpoints.commitVerifier)
        assertEquals("/CompleteAuth", WalletApi.Endpoints.completeAuth)
        assertEquals("/UseWallet", WalletApi.Endpoints.useWallet)
        assertEquals("/CreateWallet", WalletApi.Endpoints.createWallet)
        assertEquals("/SignMessage", WalletApi.Endpoints.signMessage)
        assertEquals("/SendTransaction", WalletApi.Endpoints.sendTransaction)
    }
}
