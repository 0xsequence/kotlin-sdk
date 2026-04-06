# Request Signing Parity

This document summarizes the Kotlin SDK request-signing parity coverage against
the canonical C SDK vectors.

The canonical full vector set lives in:

- `https://github.com/0xsequence/c-sdk/blob/master/tests/request_signing_vectors.md`

The matching C test coverage lives in:

- `https://github.com/0xsequence/c-sdk/blob/master/tests/sequence_request_signing_test.c`

The matching Kotlin test coverage lives in:

- `polygon-kotlin-sdk/src/test/java/com/polygon_wallet/polygon_kotlin_sdk/wallet/WalletRequestSignerVectorTest.kt`
- `polygon-kotlin-sdk/src/test/java/com/polygon_wallet/polygon_kotlin_sdk/wallet/WalletPayloadBuilderTest.kt`

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
  - answer-hash parity vector for `challenge + code -> keccak256 -> params.answer`
  - tested in `WalletPayloadBuilderTest.completeAuthPayloadFromCodeMatchesParityVector()`

The `CompleteAuth` sections below stay documented here because they include the
non-obvious answer-hash step that is easy to break independently of the generic
request-signing flow.

Reference C SDK behavior:

- `https://github.com/0xsequence/c-sdk/blob/master/lib/wallet/sequence_connector.c`
- current logic:
  1. concatenate `challenge + code`
  2. compute `keccak256` over the UTF-8 bytes
  3. lowercase hex-encode the digest as `0x...`
  4. pass that value as `params.answer` into `CompleteAuth`

Relevant C code:

```c
const char* preHashAnswer = concat_malloc(cur_challenge, code);
keccak256((const uint8_t*)preHashAnswer, strlen(preHashAnswer), hashed_to_sign);
const char* hashedAnswerHex = bytes_to_hex(hashed_to_sign, 32);
const char* complete_auth_json = sequence_build_complete_auth_json(cur_verifier, hashedAnswerHex);
```

## CompleteAuth Answer Hash Vector

- challenge: `challenge`
- code: `123456`
- verifier: `verifier-123`

- expected answer hash:
  `0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd`

- expected payload:

```json
{"params":{"identityType":"Email","authMode":"OTP","verifier":"verifier-123","answer":"0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd"}}
```

## CompleteAuth Full Request-Signing Vector

- endpoint: `/CompleteAuth`
- nonce: `1710000002`
- challenge: `challenge`
- code: `123456`
- verifier: `verifier-123`

- expected payload:

```json
{"params":{"identityType":"Email","authMode":"OTP","verifier":"verifier-123","answer":"0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd"}}
```

- expected preimage:

```text
POST /rpc/Wallet/CompleteAuth
nonce: 1710000002

{"params":{"identityType":"Email","authMode":"OTP","verifier":"verifier-123","answer":"0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd"}}
```

- expected digest hex:
  `0x6fe84a6372290cd1e3b68276e1822dbb6021d7576bd6845387c62ee938e1274c`

- expected signature:
  `0x051552b05b0ab8b4cf948803519e2dc63e8d7d0bc9a5637e59253d52eb6b1ca3301234e34441d67963f58015b40e8c43710a5edb1f2db451abbaa90b51a8c7871c`

- expected authorization header:

```text
Authorization: Ethereum_Secp256k1 scope="@1:test",cred="0x19e7e376e7c213b7e7e7e46cc70a5dd086daff2a",nonce=1710000002,sig="0x051552b05b0ab8b4cf948803519e2dc63e8d7d0bc9a5637e59253d52eb6b1ca3301234e34441d67963f58015b40e8c43710a5edb1f2db451abbaa90b51a8c7871c"
```
