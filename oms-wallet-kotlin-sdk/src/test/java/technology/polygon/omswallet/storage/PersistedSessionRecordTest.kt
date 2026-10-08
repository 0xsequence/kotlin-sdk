package technology.polygon.omswallet.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import technology.polygon.omswallet.OMSWalletEmailSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuthFlow
import technology.polygon.omswallet.models.Wallet
import technology.polygon.omswallet.models.WalletKeyOrigin
import technology.polygon.omswallet.models.WalletType
import technology.polygon.omswallet.session.OMSWalletSessionSnapshot
import technology.polygon.omswallet.wallet.WalletSigningAlgorithm

class PersistedSessionRecordTest {
    @Test
    fun roundTripsTheFullActiveWalletIncludingTron() {
        val snapshot =
            snapshot(
                Wallet(
                    id = "wallet-tron",
                    type = WalletType.Tron,
                    address = "TNPeeaaFB7K9cmo4uQpcU32zGK8G1NYqeL",
                    reference = "imported-key",
                    keyOrigin = WalletKeyOrigin.Imported,
                ),
            ).copy(
                auth =
                    OMSWalletOidcSessionAuth(
                        flow = OMSWalletOidcSessionAuthFlow.Redirect,
                        issuer = "https://accounts.google.com",
                        provider = "google",
                        providerLabel = "Google",
                        email = null,
                    ),
            )

        val encoded = PersistedSessionRecord.encode(snapshot)

        assertEquals(snapshot, PersistedSessionRecord.decode(encoded))
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive(2), root["version"])
        assertEquals(
            mapOf(
                "id" to JsonPrimitive("wallet-tron"),
                "type" to JsonPrimitive("tron"),
                "address" to JsonPrimitive("TNPeeaaFB7K9cmo4uQpcU32zGK8G1NYqeL"),
                "reference" to JsonPrimitive("imported-key"),
                "keyOrigin" to JsonPrimitive("imported"),
            ),
            root["wallet"] as JsonObject,
        )
    }

    @Test
    fun discardsUnversionedRecordsSavedBySdk03x() {
        // The 0.3.x record shape: no version and no wallet type.
        val legacy =
            """
            {"walletId":"wallet-1","walletAddress":"0x1111111111111111111111111111111111111111",
             "signerAddress":"credential","signerKeyType":"ecdsa-p256-sha256",
             "expiresAt":"2099-01-01T00:00:00Z","auth":{"type":"email","email":"user@example.com"}}
            """.trimIndent()

        assertThrows(InvalidSessionMetadataException::class.java) { PersistedSessionRecord.decode(legacy) }
        assertThrows(InvalidSessionMetadataException::class.java) {
            PersistedSessionRecord.decode(validRecord().replace("\"version\":2", "\"version\":1"))
        }
        assertThrows(InvalidSessionMetadataException::class.java) {
            PersistedSessionRecord.decode(validRecord().replace("\"version\":2", "\"version\":\"2\""))
        }
    }

    @Test
    fun discardsRecordsWithMalformedWallets() {
        val malformed =
            listOf(
                validRecord().replace("\"type\":\"ethereum\"", "\"type\":\"bitcoin\""),
                validRecord().replace("\"type\":\"ethereum\"", "\"type\":\"UNKNOWN_DEFAULT\""),
                validRecord().replace("\"keyOrigin\":\"enclave\"", "\"keyOrigin\":\"hardware\""),
                validRecord().replace("0x1111111111111111111111111111111111111111", "0xnothex"),
                validRecord().replace("0x1111111111111111111111111111111111111111", "TNPeeaaFB7K9cmo4uQpcU32zGK8G1NYqeL"),
                validRecord().replace("\"id\":\"wallet-1\",", ""),
                validRecord().replace("\"reference\":\"main\"", "\"reference\":7"),
                validRecord().replace("\"expiresAt\":\"2099-01-01T00:00:00Z\",", ""),
                "not json",
                "[]",
            )

        malformed.forEach { record ->
            assertThrows(record, InvalidSessionMetadataException::class.java) { PersistedSessionRecord.decode(record) }
        }
    }

    @Test
    fun acceptsMixedCaseEthereumHexAddresses() {
        val record = validRecord().replace("0x1111111111111111111111111111111111111111", "0xAbCdEf0000000000000000000000000000000000")

        assertEquals("0xAbCdEf0000000000000000000000000000000000", PersistedSessionRecord.decode(record).wallet?.address)
    }

    @Test
    fun refusesToPersistPendingAuthState() {
        assertThrows(IllegalArgumentException::class.java) {
            PersistedSessionRecord.encode(snapshot(null))
        }
    }

    private fun validRecord(): String =
        PersistedSessionRecord.encode(
            snapshot(
                Wallet(
                    id = "wallet-1",
                    type = WalletType.Ethereum,
                    address = "0x1111111111111111111111111111111111111111",
                    reference = "main",
                    keyOrigin = WalletKeyOrigin.Enclave,
                ),
            ),
        )

    private fun snapshot(wallet: Wallet?): OMSWalletSessionSnapshot =
        OMSWalletSessionSnapshot(
            wallet = wallet,
            signerAddress = "0x04" + "11".repeat(64),
            signerKeyType = WalletSigningAlgorithm.ECDSA_P256_SHA256,
            expiresAt = "2099-01-01T00:00:00Z",
            auth = OMSWalletEmailSessionAuth(email = "user@example.com"),
        )
}
