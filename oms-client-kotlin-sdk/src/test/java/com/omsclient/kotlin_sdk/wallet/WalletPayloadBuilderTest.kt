package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.internal.generated.waas.AuthMode
import com.omsclient.kotlin_sdk.internal.generated.waas.CommitVerifierRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.CompleteAuthRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.IdentityType
import com.omsclient.kotlin_sdk.internal.generated.waas.PrepareEthereumContractCallRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.PrepareEthereumTransactionRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.TransactionMode
import com.omsclient.kotlin_sdk.internal.generated.waas.UseWalletRequest
import com.omsclient.kotlin_sdk.internal.generated.waas.WaasWalletApi
import com.omsclient.kotlin_sdk.internal.generated.waas.WalletType
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletPayloadBuilderTest {
    @Test
    fun oidcCommitVerifierPayloadMatchesParityVector() {
        val idToken =
            "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJhdWQiOiJkZW1vLXdlYi1jbGllbnQtaWQi" +
                "LCJzdWIiOiJnb29nbGUtc3ViLTEyMyIsImVtYWlsIjoidXNlckBleGFtcGxlLmNvbSIsImV4cCI6MTkxMDAwMDEwMH0." +
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
                    metadata =
                        mapOf(
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
    fun commitVerifierPayloadMatchesParityVector() {
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
    fun completeAuthPayloadMatchesParityVector() {
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
        val answer =
            WalletAuthChallenge.hashAnswer(
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
    fun useWalletPayloadMatchesParityVector() {
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
    fun createWalletPayloadMatchesParityVector() {
        assertEquals(
            "{\"type\":\"ethereum\"}",
            WaasWalletApi.CreateWallet.encodeRequest(
                com.omsclient.kotlin_sdk.internal.generated.waas.CreateWalletRequest(
                    type = WalletType.Ethereum,
                ),
            ),
        )
    }

    @Test
    fun prepareEthereumTransactionPayloadMatchesWaasRequestShape() {
        assertEquals(
            "{\"network\":\"80002\",\"walletId\":\"wallet-0\",\"to\":\"0xabc\",\"value\":\"0\",\"data\":\"0x1234\",\"mode\":\"native\"}",
            WaasWalletApi.PrepareEthereumTransaction.encodeRequest(
                PrepareEthereumTransactionRequest(
                    network = "80002",
                    walletId = "wallet-0",
                    to = "0xabc",
                    value = "0",
                    data = "0x1234",
                    mode = TransactionMode.Native,
                ),
            ),
        )
    }

    @Test
    fun prepareEthereumContractCallPayloadMatchesWaasRequestShape() {
        assertEquals(
            "{\"network\":\"80002\",\"walletId\":\"wallet-0\",\"contract\":\"0xcontract\",\"method\":\"mint()\",\"mode\":\"relayer\"}",
            WaasWalletApi.PrepareEthereumContractCall.encodeRequest(
                PrepareEthereumContractCallRequest(
                    network = "80002",
                    walletId = "wallet-0",
                    contract = "0xcontract",
                    method = "mint()",
                    mode = TransactionMode.Relayer,
                ),
            ),
        )
    }

    @Test
    fun walletApiEndpointsMatchGeneratedSchema() {
        assertEquals("/rpc/Wallet", WaasWalletApi.basePath)
        assertEquals("/CommitVerifier", WaasWalletApi.CommitVerifier.path)
        assertEquals("/CompleteAuth", WaasWalletApi.CompleteAuth.path)
        assertEquals("/UseWallet", WaasWalletApi.UseWallet.path)
        assertEquals("/CreateWallet", WaasWalletApi.CreateWallet.path)
        assertEquals("/SignMessage", WaasWalletApi.SignMessage.path)
        assertEquals("/SignTypedData", WaasWalletApi.SignTypedData.path)
        assertEquals("/PrepareEthereumTransaction", WaasWalletApi.PrepareEthereumTransaction.path)
        assertEquals("/PrepareEthereumContractCall", WaasWalletApi.PrepareEthereumContractCall.path)
        assertEquals("/Execute", WaasWalletApi.Execute.path)
        assertEquals("/TransactionStatus", WaasWalletApi.TransactionStatus.path)
        assertEquals("/ListAccess", WaasWalletApi.ListAccess.path)
        assertEquals("/RevokeAccess", WaasWalletApi.RevokeAccess.path)
    }
}
