# Request Signing Parity

This document summarizes the Kotlin SDK request-signing parity coverage against
the canonical C SDK vectors.

The canonical full vector set lives in:

- `https://github.com/0xsequence/c-sdk/blob/master/tests/request_signing_vectors.md`

The matching C test coverage lives in:

- `https://github.com/0xsequence/c-sdk/blob/master/tests/sequence_request_signing_test.c`

The matching Kotlin test coverage lives in:

- `oms-wallet-kotlin-sdk/src/test/java/com/omswallet/kotlin_sdk/wallet/WalletRequestSignerVectorTest.kt`
- `oms-wallet-kotlin-sdk/src/test/java/com/omswallet/kotlin_sdk/wallet/WalletPayloadBuilderTest.kt`

Current Kotlin parity coverage:

- `SignMessage`
  - full payload / preimage / digest / signature / authorization header vector
  - tested in `WalletRequestSignerVectorTest.signMessageVectorMatchesCSdk()`
- `SendTransaction`
  - full payload / preimage / digest / signature / authorization header vector
  - tested in `WalletRequestSignerVectorTest.sendTransactionVectorMatchesCSdk()`
- `CompleteAuth`
  - full payload / preimage / digest / signature / authorization header vector
  - tested in `WalletRequestSignerVectorTest.completeAuthVectorMatchesCSdk()`
  - answer-hash parity vector for `challenge + code -> sha256 -> base64url(no padding) -> params.answer`
  - tested in `WalletPayloadBuilderTest.completeAuthPayloadFromCodeMatchesParityVector()`

The `CompleteAuth` sections below stay documented here because they include the
non-obvious answer-hash step that is easy to break independently of the generic
request-signing flow.

Reference C SDK behavior:

- `https://github.com/0xsequence/c-sdk/blob/master/lib/wallet/sequence_connector.c`
- current logic:
  1. concatenate `challenge + code`
  2. compute `sha256` over the UTF-8 bytes
  3. URL-safe base64-encode the digest without padding
  4. pass that value as `params.answer` into `CompleteAuth`

## CompleteAuth Answer Hash Vector

- challenge: `challenge`
- code: `123456`
- verifier: `verifier-123`

- expected answer hash:
  `2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M`

- expected payload:

```json
{"params":{"identityType":"Email","authMode":"OTP","verifier":"verifier-123","answer":"2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M"}}
```

## CompleteAuth Full Request-Signing Vector

- endpoint: `/CompleteAuth`
- nonce: `1710000002`
- challenge: `challenge`
- code: `123456`
- verifier: `verifier-123`

- expected payload:

```json
{"params":{"identityType":"Email","authMode":"OTP","verifier":"verifier-123","answer":"2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M"}}
```

- expected preimage:

```text
POST /rpc/Wallet/CompleteAuth
nonce: 1710000002

{"params":{"identityType":"Email","authMode":"OTP","verifier":"verifier-123","answer":"2oXiHHjzvN3XzdxGxWTK_c9hZf7pom0OovssPvI7q3M"}}
```

- expected digest / signature / authorization header:
  derived by `WalletRequestSignerVectorTest.completeAuthVectorMatchesCSdk()`
