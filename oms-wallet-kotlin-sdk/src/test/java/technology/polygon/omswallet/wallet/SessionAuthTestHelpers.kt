package technology.polygon.omswallet.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import technology.polygon.omswallet.OMSWalletEmailSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuth
import technology.polygon.omswallet.OMSWalletOidcSessionAuthFlow
import technology.polygon.omswallet.OMSWalletSessionAuth

internal fun emailSessionAuth(email: String = "user@example.com"): OMSWalletEmailSessionAuth = OMSWalletEmailSessionAuth(email = email)

internal fun googleRedirectSessionAuth(email: String? = "user@example.com"): OMSWalletOidcSessionAuth =
    OMSWalletOidcSessionAuth(
        flow = OMSWalletOidcSessionAuthFlow.Redirect,
        issuer = "https://accounts.google.com",
        provider = "google",
        providerLabel = "Google",
        email = email,
    )

internal fun googleIdTokenSessionAuth(email: String? = "user@example.com"): OMSWalletOidcSessionAuth =
    googleRedirectSessionAuth(email = email).copy(flow = OMSWalletOidcSessionAuthFlow.IdToken)

internal fun assertEmailSessionAuth(
    auth: OMSWalletSessionAuth?,
    email: String? = "user@example.com",
) {
    assertTrue(auth is OMSWalletEmailSessionAuth)
    auth as OMSWalletEmailSessionAuth
    assertEquals(email, auth.email)
}

internal fun assertOidcSessionAuth(
    auth: OMSWalletSessionAuth?,
    flow: OMSWalletOidcSessionAuthFlow,
    issuer: String = "https://accounts.google.com",
    provider: String? = "google",
    providerLabel: String? = "Google",
    email: String? = "user@example.com",
) {
    assertTrue(auth is OMSWalletOidcSessionAuth)
    auth as OMSWalletOidcSessionAuth
    assertEquals(flow, auth.flow)
    assertEquals(issuer, auth.issuer)
    assertEquals(provider, auth.provider)
    assertEquals(providerLabel, auth.providerLabel)
    assertEquals(email, auth.email)
}
