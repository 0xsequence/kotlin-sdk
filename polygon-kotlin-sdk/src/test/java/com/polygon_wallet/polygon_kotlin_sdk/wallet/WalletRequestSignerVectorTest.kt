package com.polygon_wallet.polygon_kotlin_sdk.wallet

import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.AuthMode
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CommitVerifierRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.CompleteAuthRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.IdentityType
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.SendTransactionRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.SignMessageRequest
import com.polygon_wallet.polygon_kotlin_sdk.generated.waas.WaasWalletApi
import com.polygon_wallet.polygon_kotlin_sdk.models.TransactionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletRequestSignerVectorTest {
    private val privateKeyHex =
        "0x1111111111111111111111111111111111111111111111111111111111111111"
    private val derivedAddress = "0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a"
    private val scope = "@1:test"

    @Test
    fun signMessageVectorMatchesCSdk() {
        val endpoint = WaasWalletApi.SignMessage.path
        val nonce = "1710000000"
        val payload = WaasWalletApi.SignMessage.encodeRequest(
            SignMessageRequest(
                wallet = "0x1234567890123456789012345678901234567890",
                network = "amoy",
                message = "hello",
            ),
        )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, payload)
        val digest = WalletRequestSigner.walletRequestPreimageDigestHex(preimage)
        val signature = WalletRequestSigner.signWalletDigestHexEip191(privateKeyHex, digest)
        val header = WalletRequestSigner.buildWalletAuthorizationHeader(scope, derivedAddress, nonce, signature)

        assertEquals(
            "{\"network\":\"amoy\",\"wallet\":\"0x1234567890123456789012345678901234567890\",\"message\":\"hello\"}",
            payload,
        )
        assertTrue(preimage.startsWith("POST /rpc/Wallet/SignMessage\nnonce: 1710000000\n\n"))
        assertTrue(digest.startsWith("0x"))
        assertEquals(derivedAddress, WalletRequestSigner.walletAddressFromPrivateKeyHex(privateKeyHex))
        assertTrue(signature.startsWith("0x"))
        assertTrue(header.contains("scope=\"@1:test\""))

        val signedRequest = WalletRequestSigner.signWalletRequest(
            endpoint = endpoint,
            nonce = nonce,
            payload = payload,
            scope = scope,
            privateKeyHex = privateKeyHex,
        )
        assertEquals(signature, signedRequest.signature)
        assertEquals(header, signedRequest.authorizationHeader)
    }

    @Test
    fun sendTransactionVectorMatchesCSdk() {
        val endpoint = WaasWalletApi.SendTransaction.path
        val nonce = "1710000001"
        val payload = WaasWalletApi.SendTransaction.encodeRequest(
            SendTransactionRequest(
                wallet = "0x1234567890123456789012345678901234567890",
                network = "amoy",
                to = "0xE5E8B483FfC05967FcFed58cc98D053265af6D99",
                value = "1000",
                mode = TransactionMode.Relayer,
            ),
        )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, payload)
        val digest = WalletRequestSigner.walletRequestPreimageDigestHex(preimage)
        val signature = WalletRequestSigner.signWalletDigestHexEip191(privateKeyHex, digest)
        val header = WalletRequestSigner.buildWalletAuthorizationHeader(scope, derivedAddress, nonce, signature)

        assertEquals(
            "{\"network\":\"amoy\",\"wallet\":\"0x1234567890123456789012345678901234567890\",\"to\":\"0xE5E8B483FfC05967FcFed58cc98D053265af6D99\",\"value\":\"1000\",\"mode\":\"Relayer\"}",
            payload,
        )
        assertTrue(preimage.startsWith("POST /rpc/Wallet/SendTransaction\nnonce: 1710000001\n\n"))
        assertTrue(digest.startsWith("0x"))
        assertTrue(signature.startsWith("0x"))
        assertTrue(header.contains("nonce=1710000001"))
    }

    @Test
    fun completeAuthVectorMatchesCSdk() {
        val endpoint = WaasWalletApi.CompleteAuth.path
        val nonce = "1710000002"
        val payload = WaasWalletApi.CompleteAuth.encodeRequest(
            CompleteAuthRequest(
                identityType = IdentityType.Email,
                authMode = AuthMode.OTP,
                verifier = "verifier-123",
                answer = WalletAuthChallenge.hashAnswer(
                    challenge = "challenge",
                    code = "123456",
                ),
            ),
        )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, payload)
        val digest = WalletRequestSigner.walletRequestPreimageDigestHex(preimage)
        val signature = WalletRequestSigner.signWalletDigestHexEip191(privateKeyHex, digest)
        val header = WalletRequestSigner.buildWalletAuthorizationHeader(scope, derivedAddress, nonce, signature)

        assertEquals(
            "{\"identityType\":\"Email\",\"authMode\":\"OTP\",\"verifier\":\"verifier-123\",\"answer\":\"0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd\"}",
            payload,
        )
        assertTrue(preimage.startsWith("POST /rpc/Wallet/CompleteAuth\nnonce: 1710000002\n\n"))
        assertTrue(digest.startsWith("0x"))
        assertTrue(signature.startsWith("0x"))
        assertTrue(header.contains("nonce=1710000002"))

        val signedRequest = WalletRequestSigner.signWalletRequest(
            endpoint = endpoint,
            nonce = nonce,
            payload = payload,
            scope = scope,
            privateKeyHex = privateKeyHex,
        )
        assertEquals(signature, signedRequest.signature)
        assertEquals(header, signedRequest.authorizationHeader)
    }
}
