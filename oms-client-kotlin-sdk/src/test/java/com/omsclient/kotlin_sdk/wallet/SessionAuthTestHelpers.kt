package com.omsclient.kotlin_sdk.wallet

import com.omsclient.kotlin_sdk.OMSClientEmailSessionAuth
import com.omsclient.kotlin_sdk.OMSClientOidcSessionAuth
import com.omsclient.kotlin_sdk.OMSClientOidcSessionAuthFlow
import com.omsclient.kotlin_sdk.OMSClientSessionAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal fun emailSessionAuth(email: String? = "user@example.com"): OMSClientEmailSessionAuth = OMSClientEmailSessionAuth(email = email)

internal fun googleRedirectSessionAuth(email: String? = "user@example.com"): OMSClientOidcSessionAuth =
    OMSClientOidcSessionAuth(
        flow = OMSClientOidcSessionAuthFlow.Redirect,
        issuer = "https://accounts.google.com",
        provider = "google",
        providerLabel = "Google",
        email = email,
    )

internal fun googleIdTokenSessionAuth(email: String? = "user@example.com"): OMSClientOidcSessionAuth =
    googleRedirectSessionAuth(email = email).copy(flow = OMSClientOidcSessionAuthFlow.IdToken)

internal fun assertEmailSessionAuth(
    auth: OMSClientSessionAuth?,
    email: String? = "user@example.com",
) {
    assertTrue(auth is OMSClientEmailSessionAuth)
    auth as OMSClientEmailSessionAuth
    assertEquals(email, auth.email)
}

internal fun assertOidcSessionAuth(
    auth: OMSClientSessionAuth?,
    flow: OMSClientOidcSessionAuthFlow,
    issuer: String = "https://accounts.google.com",
    provider: String? = "google",
    providerLabel: String? = "Google",
    email: String? = "user@example.com",
) {
    assertTrue(auth is OMSClientOidcSessionAuth)
    auth as OMSClientOidcSessionAuth
    assertEquals(flow, auth.flow)
    assertEquals(issuer, auth.issuer)
    assertEquals(provider, auth.provider)
    assertEquals(providerLabel, auth.providerLabel)
    assertEquals(email, auth.email)
}
