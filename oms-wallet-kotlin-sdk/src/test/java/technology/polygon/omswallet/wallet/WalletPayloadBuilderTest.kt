package technology.polygon.omswallet.wallet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import technology.polygon.omswallet.internal.generated.waas.AbiArg
import technology.polygon.omswallet.internal.generated.waas.AuthMode
import technology.polygon.omswallet.internal.generated.waas.CommitVerifierRequest
import technology.polygon.omswallet.internal.generated.waas.IdentityType
import technology.polygon.omswallet.internal.generated.waas.PrepareEthereumContractCallRequest
import technology.polygon.omswallet.internal.generated.waas.TransactionMode
import technology.polygon.omswallet.internal.generated.waas.WaasApi

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
            WaasApi.CommitVerifier.encodeRequest(
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
            WaasApi.CommitVerifier.encodeRequest(
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
    fun prepareEthereumContractCallPayloadMatchesWaasRequestShape() {
        assertEquals(
            "{\"network\":\"80002\",\"walletId\":\"wallet-0\",\"contract\":\"0xcontract\",\"method\":\"mint\"," +
                "\"args\":[{\"type\":\"address\",\"value\":\"0xabc\"},{\"type\":\"uint256\",\"value\":\"1000\"}," +
                "{\"type\":\"uint256[]\",\"value\":[\"1\",\"2\"]}],\"mode\":\"relayer\"}",
            WaasApi.PrepareEthereumContractCall.encodeRequest(
                PrepareEthereumContractCallRequest(
                    network = "80002",
                    walletId = "wallet-0",
                    contract = "0xcontract",
                    method = "mint",
                    args =
                        listOf(
                            AbiArg(type = "address", value = JsonPrimitive("0xabc")),
                            AbiArg(type = "uint256", value = JsonPrimitive("1000")),
                            AbiArg(type = "uint256[]", value = JsonArray(listOf(JsonPrimitive("1"), JsonPrimitive("2")))),
                        ),
                    mode = TransactionMode.Relayer,
                ),
            ),
        )
    }
}
