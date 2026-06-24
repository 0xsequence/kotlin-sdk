# Request Signing Parity

This document summarizes the Kotlin SDK request-signing parity coverage against
the canonical wallet request-signing vectors.

The Kotlin test coverage lives in:

- `oms-client-kotlin-sdk/src/test/java/com/omsclient/kotlin_sdk/wallet/WalletRequestSignerVectorTest.kt`
- `oms-client-kotlin-sdk/src/test/java/com/omsclient/kotlin_sdk/wallet/WalletPayloadBuilderTest.kt`

Kotlin parity coverage:

- `CommitVerifier` (`OIDC`)
  - full payload parity for ID-token metadata plus `handle = base64url(sha256(full JWT string))`
  - tested in `WalletPayloadBuilderTest.oidcCommitVerifierPayloadMatchesParityVector()`
- `SignMessage`
  - full payload / preimage / wallet signature header assembly vector
  - request scope is derived from fixture publishable key `pk_live_project_key`
    as `prj_project`, matching SDK runtime behavior
  - tested in `WalletRequestSignerVectorTest.signMessagePayloadAndPreimageMatchParityVector()`
- `PrepareEthereumTransaction`
  - full payload / preimage / wallet signature header assembly vector
  - request scope is derived from fixture publishable key `pk_live_project_key`
    as `prj_project`, matching SDK runtime behavior
  - tested in `WalletRequestSignerVectorTest.prepareEthereumTransactionPayloadAndPreimageMatchParityVector()`
- `CompleteAuth`
  - full payload / preimage / wallet signature header assembly vector
  - request scope is derived from fixture publishable key `pk_live_project_key`
    as `prj_project`, matching SDK runtime behavior
  - tested in `WalletRequestSignerVectorTest.completeAuthPayloadAndPreimageMatchParityVector()`
  - answer-hash parity vector for `challenge + code -> sha256 -> base64url(no padding) -> answer`
  - tested in `WalletPayloadBuilderTest.completeAuthPayloadFromCodeMatchesParityVector()`

The `CompleteAuth` sections below stay documented here because they include the
non-obvious answer-hash step that is easy to break independently of the generic
request-signing flow.

## CommitVerifier OIDC Handle Hash Vector

- id token (dummy fixture used only for deterministic hashing/parity tests):
  `eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJhdWQiOiJkZW1vLXdlYi1jbGllbnQtaWQiLCJzdWIiOiJnb29nbGUtc3ViLTEyMyIsImVtYWlsIjoidXNlckBleGFtcGxlLmNvbSIsImV4cCI6MTkxMDAwMDEwMH0.signature`
- expected handle hash:
  `nyaQb_2b6gSthzvKxcPn2oWZfRoUxQSFZS89_EwbYwY`

- expected payload:

```json
{"identityType":"oidc","authMode":"id-token","metadata":{"iss":"https://accounts.google.com","aud":"demo-web-client-id","exp":"1910000100"},"handle":"nyaQb_2b6gSthzvKxcPn2oWZfRoUxQSFZS89_EwbYwY"}
```

CompleteAuth answer behavior:

1. Concatenate `challenge + code`.
2. Compute `sha256` over the UTF-8 bytes.
3. URL-safe base64-encode the digest without padding.
4. Pass that value as `answer` into `CompleteAuth`.

## CompleteAuth Answer Hash Vector

- challenge: `challenge`
- code: `123456`
- verifier: `verifier-123`

- expected answer hash:
  `2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M`

- expected payload:

```json
{"identityType":"email","authMode":"otp","verifier":"verifier-123","answer":"2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M"}
```

## CompleteAuth Full Request-Signing Vector

- endpoint: `/CompleteAuth`
- nonce: `1710000002`
- challenge: `challenge`
- code: `123456`
- verifier: `verifier-123`

- expected payload:

```json
{"identityType":"email","authMode":"otp","verifier":"verifier-123","answer":"2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M"}
```

- expected preimage:

```text
POST /v1/Waas/CompleteAuth
nonce: 1710000002
scope: prj_project

{"identityType":"email","authMode":"otp","verifier":"verifier-123","answer":"2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M"}
```

- expected wallet signature header:
  assembled by `WalletRequestSignerVectorTest.completeAuthPayloadAndPreimageMatchParityVector()`
  from the deterministic test credential id, nonce, SDK-derived `prj_project`
  scope, and injected P-256 test signature. Production wallet request
  signatures come from the Android Keystore P-256 credential signer.
