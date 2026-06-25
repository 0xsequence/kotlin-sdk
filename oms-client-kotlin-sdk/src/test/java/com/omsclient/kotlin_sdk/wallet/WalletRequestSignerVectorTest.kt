package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.internal.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.internal.generated.waas.CommitVerifierRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.CompleteAuthRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.internal.generated.waas.PrepareEthereumTransactionRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.SignMessageRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.TransactionMode
import com.omsclient.kotlin_sdk.internal.generated.waas.WaasApi
import com.omsclient.kotlin_sdk.parsePublishableKey
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletRequestSignerVectorTest {
    private val scope = parsePublishableKey("pk_live_project_key").projectId

    @Test
    fun signMessagePayloadAndPreimageMatchParityVector() {
        val endpoint = WaasApi.SignMessage.path
        val nonce = "1710000000"
        val expectedPayload =
            "{\"network\":\"80002\",\"walletId\":\"0x1234567890123456789012345678901234567890\",\"message\":\"hello\"}"
        val expectedPreimage =
            "POST /v1/Waas/SignMessage\nnonce: 1710000000\nscope: prj_project\n\n" +
                expectedPayload
        val expectedHeader = expectedWalletSignatureHeader(nonce = nonce, scope = scope)
        val payload =
            WaasApi.SignMessage.encodeRequest(
                SignMessageRequest(
                    walletId = "0x1234567890123456789012345678901234567890",
                    network = "80002",
                    message = "hello",
                ),
            )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, scope, payload)
        val header =
            WalletRequestSigner.buildWalletSignatureHeader(
                signingAlgorithm = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                scope = scope,
                credentialId = TEST_CREDENTIAL_ID,
                nonce = nonce,
                signature = TEST_SIGNATURE,
            )

        assertEquals(expectedPayload, payload)
        assertEquals(expectedPreimage, preimage)
        assertEquals(expectedHeader, header)
    }

    @Test
    fun prepareEthereumTransactionPayloadAndPreimageMatchParityVector() {
        val endpoint = WaasApi.PrepareEthereumTransaction.path
        val nonce = "1710000001"
        val expectedPayload =
            "{\"network\":\"80002\",\"walletId\":\"0x1234567890123456789012345678901234567890\"," +
                "\"to\":\"0xE5E8B483FfC05967FcFed58cc98D053265af6D99\",\"value\":\"1000\",\"mode\":\"relayer\"}"
        val expectedPreimage =
            "POST /v1/Waas/PrepareEthereumTransaction\nnonce: 1710000001\nscope: prj_project\n\n" +
                expectedPayload
        val expectedHeader = expectedWalletSignatureHeader(nonce = nonce, scope = scope)
        val payload =
            WaasApi.PrepareEthereumTransaction.encodeRequest(
                PrepareEthereumTransactionRequest(
                    walletId = "0x1234567890123456789012345678901234567890",
                    network = "80002",
                    to = "0xE5E8B483FfC05967FcFed58cc98D053265af6D99",
                    value = "1000",
                    mode = TransactionMode.Relayer,
                ),
            )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, scope, payload)
        val header =
            WalletRequestSigner.buildWalletSignatureHeader(
                signingAlgorithm = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                scope = scope,
                credentialId = TEST_CREDENTIAL_ID,
                nonce = nonce,
                signature = TEST_SIGNATURE,
            )

        assertEquals(expectedPayload, payload)
        assertEquals(expectedPreimage, preimage)
        assertEquals(expectedHeader, header)
    }

    @Test
    fun completeAuthPayloadAndPreimageMatchParityVector() {
        val endpoint = WaasApi.CompleteAuth.path
        val nonce = "1710000002"
        val expectedPayload =
            "{\"identityType\":\"email\",\"authMode\":\"otp\",\"verifier\":\"verifier-123\"," +
                "\"answer\":\"2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M\"}"
        val expectedPreimage =
            "POST /v1/Waas/CompleteAuth\nnonce: 1710000002\nscope: prj_project\n\n" +
                expectedPayload
        val expectedHeader = expectedWalletSignatureHeader(nonce = nonce, scope = scope)
        val payload =
            WaasApi.CompleteAuth.encodeRequest(
                CompleteAuthRequest(
                    identityType = IdentityType.Email,
                    authMode = AuthMode.OTP,
                    verifier = "verifier-123",
                    answer =
                        WalletAuthChallenge.hashAnswer(
                            challenge = "challenge",
                            code = "123456",
                        ),
                ),
            )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, scope, payload)
        val header =
            WalletRequestSigner.buildWalletSignatureHeader(
                signingAlgorithm = WalletSigningAlgorithm.ECDSA_P256_SHA256,
                scope = scope,
                credentialId = TEST_CREDENTIAL_ID,
                nonce = nonce,
                signature = TEST_SIGNATURE,
            )

        assertEquals(expectedPayload, payload)
        assertEquals(expectedPreimage, preimage)
        assertEquals(expectedHeader, header)
    }
}
