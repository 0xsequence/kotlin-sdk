package com.omsclient.kotlin_sdk.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.web3j.utils.Numeric
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature

class AndroidKeystoreP256CredentialSignerInstrumentedTest {
    private val alias = "oms-client-test-p256-${System.nanoTime()}"
    private val nonceStoreName = "oms-client-test-nonces-$alias"
    private lateinit var signer: AndroidKeystoreP256CredentialSigner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        signer =
            AndroidKeystoreP256CredentialSigner(
                context = context,
                alias = alias,
                nonceStoreName = nonceStoreName,
            )
        signer.clear()
    }

    @After
    fun tearDown() {
        signer.clear()
    }

    @Test
    fun createsNonExtractableP256CredentialAndSignsRawWebCryptoSignature() =
        runBlocking {
            val preimage = "POST /rpc/Wallet/CommitVerifier\nnonce: 1\n\n{}"

            val credentialId = signer.credentialId()
            val signature = signer.sign(preimage)
            val rawSignature = Numeric.hexStringToByteArray(signature)

            assertTrue(credentialId.matches(Regex("^0x04[0-9a-f]{128}$")))
            assertTrue(signature.matches(Regex("^0x[0-9a-f]{128}$")))
            assertTrue(signer.hasCredential())
            assertNull(privateKey().encoded)
            assertTrue(verifySignature(preimage, rawSignature))

            signer.clear()

            assertFalse(signer.hasCredential())
        }

    @Test
    fun nextNonceIsMonotonic() =
        runBlocking {
            signer.credentialId()

            val first = signer.nextNonce().toLong()
            val second = signer.nextNonce().toLong()

            assertTrue(second > first)
        }

    @Test
    fun nextNonceIsUniqueAcrossSignerInstances() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val secondSigner =
                AndroidKeystoreP256CredentialSigner(
                    context = context,
                    alias = alias,
                    nonceStoreName = nonceStoreName,
                )
            try {
                signer.credentialId()
                secondSigner.credentialId()

                val nonces =
                    coroutineScope {
                        (0 until 40)
                            .map { index ->
                                async {
                                    if (index % 2 == 0) {
                                        signer.nextNonce()
                                    } else {
                                        secondSigner.nextNonce()
                                    }
                                }
                            }.awaitAll()
                    }.map(String::toLong)

                assertEquals(nonces.size, nonces.toSet().size)
            } finally {
                secondSigner.clear()
            }
        }

    private fun privateKey(): PrivateKey = requireNotNull(keyStore().getKey(alias, null) as? PrivateKey)

    private fun verifySignature(
        preimage: String,
        rawSignature: ByteArray,
    ): Boolean {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(requireNotNull(keyStore().getCertificate(alias)).publicKey)
        verifier.update(preimage.toByteArray(Charsets.UTF_8))
        return verifier.verify(P256EcdsaSignatureEncoding.rawToDer(rawSignature))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
