package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.generated.waas.CommitVerifierRequest
import com.omsclient.kotlin_sdk.generated.waas.CompleteAuthRequest
import com.omsclient.kotlin_sdk.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.generated.waas.PrepareEthereumTransactionRequest
import com.omsclient.kotlin_sdk.generated.waas.SignMessageRequest
import com.omsclient.kotlin_sdk.generated.waas.TransactionMode
import com.omsclient.kotlin_sdk.generated.waas.WaasWalletApi
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletRequestSignerVectorTest {
    private val privateKeyHex =
        "0x1111111111111111111111111111111111111111111111111111111111111111"
    private val derivedAddress = "0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a"
    private val scope = "proj_1"

    @Test
    fun signMessageVectorMatchesParityVector() {
        val endpoint = WaasWalletApi.SignMessage.path
        val nonce = "1710000000"
        val expectedPayload =
            "{\"network\":\"80002\",\"walletId\":\"0x1234567890123456789012345678901234567890\",\"message\":\"hello\"}"
        val expectedPreimage =
            "POST /rpc/Wallet/SignMessage\nnonce: 1710000000\nscope: proj_1\n\n" +
                expectedPayload
        val expectedDigest = "0xf362665d89fae6f80db2cade24e0ff1403cb2b49ce7667c9da88398963c2ac3f"
        val expectedSignature =
            "0xc3b653fc996bea6d93865e25b394f2ccb6ff33d395ba0566df5a37bb80b47571" +
                "3a222638d5c53a8a59be3cc19aa69f6c8f061d7573bdd6b958ee8edee8fcc66e1c"
        val expectedHeader =
            "OMS-Wallet-Signature: alg=\"ecdsa-p256k-eip191\",scope=\"proj_1\"," +
                "cred=\"0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a\",nonce=1710000000," +
                "sig=\"$expectedSignature\""
        val payload =
            WaasWalletApi.SignMessage.encodeRequest(
                SignMessageRequest(
                    walletId = "0x1234567890123456789012345678901234567890",
                    network = "80002",
                    message = "hello",
                ),
            )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, scope, payload)
        val digest = WalletRequestSigner.walletRequestPreimageDigestHex(preimage)
        val signature = WalletRequestSigner.signWalletDigestHexEip191(privateKeyHex, digest)
        val header = WalletRequestSigner.buildWalletSignatureHeader(scope, derivedAddress, nonce, signature)

        assertEquals(expectedPayload, payload)
        assertEquals(expectedPreimage, preimage)
        assertEquals(expectedDigest, digest)
        assertEquals(derivedAddress, WalletRequestSigner.walletAddressFromPrivateKeyHex(privateKeyHex))
        assertEquals(expectedSignature, signature)
        assertEquals(expectedHeader, header)

        val signedRequest =
            WalletRequestSigner.signWalletRequest(
                endpoint = endpoint,
                nonce = nonce,
                payload = payload,
                scope = scope,
                privateKeyHex = privateKeyHex,
            )
        assertEquals(expectedPayload, signedRequest.payload)
        assertEquals(expectedPreimage, signedRequest.preimage)
        assertEquals(expectedDigest, signedRequest.digestHex)
        assertEquals(derivedAddress, signedRequest.address)
        assertEquals(expectedSignature, signedRequest.signature)
        assertEquals(expectedHeader, signedRequest.walletSignatureHeader)
    }

    @Test
    fun prepareEthereumTransactionVectorMatchesParityVector() {
        val endpoint = WaasWalletApi.PrepareEthereumTransaction.path
        val nonce = "1710000001"
        val expectedPayload =
            "{\"network\":\"80002\",\"walletId\":\"0x1234567890123456789012345678901234567890\"," +
                "\"to\":\"0xE5E8B483FfC05967FcFed58cc98D053265af6D99\",\"value\":\"1000\",\"mode\":\"relayer\"}"
        val expectedPreimage =
            "POST /rpc/Wallet/PrepareEthereumTransaction\nnonce: 1710000001\nscope: proj_1\n\n" +
                expectedPayload
        val expectedDigest = "0x4cc10881f5378e4245524aea9271046a4ba32051e85c76614a347dd97565b581"
        val expectedSignature =
            "0x1e81022388ac9c5b71e92836ad61718238ff1579e7e5bfd2ae60233af8199422" +
                "76669cd5cd406812b0ccfba0dde2c3ba8b6cd02b3f7beec27ce16f213917c5ff1c"
        val expectedHeader =
            "OMS-Wallet-Signature: alg=\"ecdsa-p256k-eip191\",scope=\"proj_1\"," +
                "cred=\"0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a\",nonce=1710000001," +
                "sig=\"$expectedSignature\""
        val payload =
            WaasWalletApi.PrepareEthereumTransaction.encodeRequest(
                PrepareEthereumTransactionRequest(
                    walletId = "0x1234567890123456789012345678901234567890",
                    network = "80002",
                    to = "0xE5E8B483FfC05967FcFed58cc98D053265af6D99",
                    value = "1000",
                    mode = TransactionMode.Relayer,
                ),
            )
        val preimage = WalletRequestSigner.buildWalletRequestPreimage(endpoint, nonce, scope, payload)
        val digest = WalletRequestSigner.walletRequestPreimageDigestHex(preimage)
        val signature = WalletRequestSigner.signWalletDigestHexEip191(privateKeyHex, digest)
        val header = WalletRequestSigner.buildWalletSignatureHeader(scope, derivedAddress, nonce, signature)

        assertEquals(expectedPayload, payload)
        assertEquals(expectedPreimage, preimage)
        assertEquals(expectedDigest, digest)
        assertEquals(expectedSignature, signature)
        assertEquals(expectedHeader, header)
    }

    @Test
    fun completeAuthVectorMatchesParityVector() {
        val endpoint = WaasWalletApi.CompleteAuth.path
        val nonce = "1710000002"
        val expectedPayload =
            "{\"identityType\":\"email\",\"authMode\":\"otp\",\"verifier\":\"verifier-123\"," +
                "\"answer\":\"2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M\"}"
        val expectedPreimage =
            "POST /rpc/Wallet/CompleteAuth\nnonce: 1710000002\nscope: proj_1\n\n" +
                expectedPayload
        val expectedDigest = "0xd253228ad0adfc9b15087bf5464bc0a5b349f49c12f80b92c7a0e7128937dbd5"
        val expectedSignature =
            "0xa52d30cf69ccd69ce0b10bf0a2059210e7e1283a394628d9ad10e7d6d7bd848b" +
                "7cbe0902ab22a8fd81dbc94099b9e90337086e7edc287ed267e9b4d5210664321c"
        val expectedHeader =
            "OMS-Wallet-Signature: alg=\"ecdsa-p256k-eip191\",scope=\"proj_1\"," +
                "cred=\"0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a\",nonce=1710000002," +
                "sig=\"$expectedSignature\""
        val payload =
            WaasWalletApi.CompleteAuth.encodeRequest(
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
        val digest = WalletRequestSigner.walletRequestPreimageDigestHex(preimage)
        val signature = WalletRequestSigner.signWalletDigestHexEip191(privateKeyHex, digest)
        val header = WalletRequestSigner.buildWalletSignatureHeader(scope, derivedAddress, nonce, signature)

        assertEquals(expectedPayload, payload)
        assertEquals(expectedPreimage, preimage)
        assertEquals(expectedDigest, digest)
        assertEquals(expectedSignature, signature)
        assertEquals(expectedHeader, header)

        val signedRequest =
            WalletRequestSigner.signWalletRequest(
                endpoint = endpoint,
                nonce = nonce,
                payload = payload,
                scope = scope,
                privateKeyHex = privateKeyHex,
            )
        assertEquals(expectedPayload, signedRequest.payload)
        assertEquals(expectedPreimage, signedRequest.preimage)
        assertEquals(expectedDigest, signedRequest.digestHex)
        assertEquals(derivedAddress, signedRequest.address)
        assertEquals(expectedSignature, signedRequest.signature)
        assertEquals(expectedHeader, signedRequest.walletSignatureHeader)
    }
}
