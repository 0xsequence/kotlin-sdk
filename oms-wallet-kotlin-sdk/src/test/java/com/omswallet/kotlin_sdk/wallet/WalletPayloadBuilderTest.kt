package com.omswallet.kotlin_sdk.wallet

import com.omswallet.kotlin_sdk.generated.waas.AuthMode
import com.omswallet.kotlin_sdk.generated.waas.CommitVerifierRequest
import com.omswallet.kotlin_sdk.generated.waas.CompleteAuthRequest
import com.omswallet.kotlin_sdk.generated.waas.IdentityType
import com.omswallet.kotlin_sdk.generated.waas.SendTransactionRequest as WaasSendTransactionRequest
import com.omswallet.kotlin_sdk.generated.waas.UseWalletRequest
import com.omswallet.kotlin_sdk.generated.waas.WaasWalletApi
import com.omswallet.kotlin_sdk.generated.waas.WalletType
import com.omswallet.kotlin_sdk.models.SendTransactionRequest
import com.omswallet.kotlin_sdk.models.TransactionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletPayloadBuilderTest {
    @Test
    fun oidcCommitVerifierPayloadMatchesParityVector() {
        val idToken =
            "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJhdWQiOiJkZW1vLXdlYi1jbGllbnQtaWQiLCJzdWIiOiJnb29nbGUtc3ViLTEyMyIsImVtYWlsIjoidXNlckBleGFtcGxlLmNvbSIsImV4cCI6MTkxMDAwMDEwMH0." +
                "signature"
        val handle = OidcIdToken.handleHash(idToken)

        assertEquals(
            "nyaQb_2b6gSthzvKxcPn2oWZfRoUxQSFZS89_EwbYwY",
            handle,
        )
        assertEquals(
            "{\"identityType\":\"oidc\",\"authMode\":\"id-token\",\"metadata\":{\"iss\":\"https://accounts.google.com\",\"aud\":\"demo-web-client-id\",\"exp\":\"1910000100\"},\"handle\":\"nyaQb_2b6gSthzvKxcPn2oWZfRoUxQSFZS89_EwbYwY\"}",
            WaasWalletApi.CommitVerifier.encodeRequest(
                CommitVerifierRequest(
                    identityType = IdentityType.OIDC,
                    authMode = AuthMode.IDToken,
                    metadata = mapOf(
                        "iss" to "https://accounts.google.com",
                        "aud" to "demo-web-client-id",
                        "exp" to "1910000100",
                    ),
                    handle = handle,
                ),
            ),
        )
    }

    @Test
    fun commitVerifierPayloadMatchesCSdk() {
        assertEquals(
            "{\"identityType\":\"email\",\"authMode\":\"otp\",\"metadata\":{},\"handle\":\"user@example.com\"}",
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
            "{\"identityType\":\"email\",\"authMode\":\"otp\",\"verifier\":\"verifier-123\",\"answer\":\"0xdeadbeef\"}",
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
            "2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M",
            answer,
        )
        assertEquals(
            "{\"identityType\":\"email\",\"authMode\":\"otp\",\"verifier\":\"verifier-123\",\"answer\":\"2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M\"}",
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
            "{\"walletId\":\"wallet-0\"}",
            WaasWalletApi.UseWallet.encodeRequest(
                UseWalletRequest(
                    walletId = "wallet-0",
                ),
            ),
        )
    }

    @Test
    fun createWalletPayloadMatchesCSdk() {
        assertEquals(
            "{\"type\":\"ethereum\"}",
            WaasWalletApi.CreateWallet.encodeRequest(
                com.omswallet.kotlin_sdk.generated.waas.CreateWalletRequest(
                    type = WalletType.Ethereum,
                ),
            ),
        )
    }

    @Test
    fun sendTransactionPayloadMatchesWaasRequestShape() {
        assertEquals(
            "{\"network\":\"amoy\",\"walletId\":\"wallet-0\",\"to\":\"0xabc\",\"value\":\"0\",\"data\":\"0x1234\",\"mode\":\"native\",\"feeCeiling\":\"1000000\",\"nonce\":\"42\"}",
            WaasWalletApi.SendTransaction.encodeRequest(
                WaasSendTransactionRequest(
                    network = "amoy",
                    walletId = "wallet-0",
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
