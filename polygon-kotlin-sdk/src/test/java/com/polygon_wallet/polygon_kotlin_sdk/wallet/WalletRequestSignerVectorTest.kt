package com.polygon_wallet.polygon_kotlin_sdk.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletRequestSignerVectorTest {
    private val privateKeyHex =
        "0x1111111111111111111111111111111111111111111111111111111111111111"
    private val derivedAddress = "0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a"
    private val scope = "@1:test"

    @Test
    fun signMessageVectorMatchesCSdk() {
        val endpoint = "/SignMessage"
        val nonce = "1710000000"
        val payload = WalletPayloadBuilder.buildSignMessagePayload(
            wallet = "0x1234567890123456789012345678901234567890",
            network = "amoy",
            message = "hello",
        )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, payload)
        val digest = WalletRequestSigner.walletRequestPreimageDigestHex(preimage)
        val signature = WalletRequestSigner.signWalletDigestHexEip191(privateKeyHex, digest)
        val header = WalletRequestSigner.buildWalletAuthorizationHeader(scope, derivedAddress, nonce, signature)

        assertEquals(
            "{\"params\":{\"wallet\":\"0x1234567890123456789012345678901234567890\",\"network\":\"amoy\",\"message\":\"hello\"}}",
            payload,
        )
        assertEquals(
            "POST /rpc/Wallet/SignMessage\nnonce: 1710000000\n\n{\"params\":{\"wallet\":\"0x1234567890123456789012345678901234567890\",\"network\":\"amoy\",\"message\":\"hello\"}}",
            preimage,
        )
        assertEquals(
            "0x24b512b5aad6b77720d929914c135c81fa42879f21c3d1c6e86fa3cac4c18ca3",
            digest,
        )
        assertEquals(derivedAddress, WalletRequestSigner.walletAddressFromPrivateKeyHex(privateKeyHex))
        assertEquals(
            "0x1ed397e17208e21f86bb8b87f00b6e85dc7cf00a999e0f735aafefe75b701f792a60894919590a142e55a4be4aa4fa58d9782702e38795660191080139a3ceda1b",
            signature,
        )
        assertEquals(
            "Authorization: Ethereum_Secp256k1 scope=\"@1:test\",cred=\"0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a\",nonce=1710000000,sig=\"0x1ed397e17208e21f86bb8b87f00b6e85dc7cf00a999e0f735aafefe75b701f792a60894919590a142e55a4be4aa4fa58d9782702e38795660191080139a3ceda1b\"",
            header,
        )

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
        val endpoint = "/SendTransaction"
        val nonce = "1710000001"
        val payload = WalletPayloadBuilder.buildSendTransactionPayload(
            wallet = "0x1234567890123456789012345678901234567890",
            network = "amoy",
            to = "0xE5E8B483FfC05967FcFed58cc98D053265af6D99",
            value = "1000",
        )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, payload)
        val digest = WalletRequestSigner.walletRequestPreimageDigestHex(preimage)
        val signature = WalletRequestSigner.signWalletDigestHexEip191(privateKeyHex, digest)
        val header = WalletRequestSigner.buildWalletAuthorizationHeader(scope, derivedAddress, nonce, signature)

        assertEquals(
            "{\"params\":{\"mode\":\"Relayer\",\"wallet\":\"0x1234567890123456789012345678901234567890\",\"network\":\"amoy\",\"to\":\"0xE5E8B483FfC05967FcFed58cc98D053265af6D99\",\"value\":\"1000\"}}",
            payload,
        )
        assertEquals(
            "POST /rpc/Wallet/SendTransaction\nnonce: 1710000001\n\n{\"params\":{\"mode\":\"Relayer\",\"wallet\":\"0x1234567890123456789012345678901234567890\",\"network\":\"amoy\",\"to\":\"0xE5E8B483FfC05967FcFed58cc98D053265af6D99\",\"value\":\"1000\"}}",
            preimage,
        )
        assertEquals(
            "0xa38ffa5cde4c9830190b7c81c69fe4fbd6519eb7f53c348f2a9829cbfe11cb98",
            digest,
        )
        assertEquals(
            "0xe4b227b6cb3cbd30ac636b06f97b9e44488d966ca0d49a257f9580477720881022085426548aabfc151d7ebfe0ad7271044d145c1c76cef6aeebeb67d520ae3d1c",
            signature,
        )
        assertEquals(
            "Authorization: Ethereum_Secp256k1 scope=\"@1:test\",cred=\"0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a\",nonce=1710000001,sig=\"0xe4b227b6cb3cbd30ac636b06f97b9e44488d966ca0d49a257f9580477720881022085426548aabfc151d7ebfe0ad7271044d145c1c76cef6aeebeb67d520ae3d1c\"",
            header,
        )
    }

    @Test
    fun completeAuthVectorMatchesCSdk() {
        val endpoint = "/CompleteAuth"
        val nonce = "1710000002"
        val payload = WalletPayloadBuilder.buildCompleteAuthPayloadFromCode(
            verifier = "verifier-123",
            challenge = "challenge",
            code = "123456",
        )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, payload)
        val digest = WalletRequestSigner.walletRequestPreimageDigestHex(preimage)
        val signature = WalletRequestSigner.signWalletDigestHexEip191(privateKeyHex, digest)
        val header = WalletRequestSigner.buildWalletAuthorizationHeader(scope, derivedAddress, nonce, signature)

        assertEquals(
            "{\"params\":{\"identityType\":\"Email\",\"authMode\":\"OTP\",\"verifier\":\"verifier-123\",\"answer\":\"0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd\"}}",
            payload,
        )
        assertEquals(
            "POST /rpc/Wallet/CompleteAuth\nnonce: 1710000002\n\n{\"params\":{\"identityType\":\"Email\",\"authMode\":\"OTP\",\"verifier\":\"verifier-123\",\"answer\":\"0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd\"}}",
            preimage,
        )
        assertEquals(
            "0x6fe84a6372290cd1e3b68276e1822dbb6021d7576bd6845387c62ee938e1274c",
            digest,
        )
        assertEquals(
            "0x051552b05b0ab8b4cf948803519e2dc63e8d7d0bc9a5637e59253d52eb6b1ca3301234e34441d67963f58015b40e8c43710a5edb1f2db451abbaa90b51a8c7871c",
            signature,
        )
        assertEquals(
            "Authorization: Ethereum_Secp256k1 scope=\"@1:test\",cred=\"0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a\",nonce=1710000002,sig=\"0x051552b05b0ab8b4cf948803519e2dc63e8d7d0bc9a5637e59253d52eb6b1ca3301234e34441d67963f58015b40e8c43710a5edb1f2db451abbaa90b51a8c7871c\"",
            header,
        )

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
