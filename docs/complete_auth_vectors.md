# CompleteAuth Parity Vector

This vector captures the `CompleteAuth` answer hashing behavior used by the
current C SDK so other SDKs can verify they produce the same intermediate
payload input before signing the `/CompleteAuth` request.

Reference C SDK behavior:

- `../c-sdk/lib/wallet/sequence_connector.c`
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

The source-of-truth vector is now also documented and tested in:

- `../c-sdk/tests/request_signing_vectors.md`
- `../c-sdk/tests/sequence_request_signing_test.c`

## Answer Hash Vector

- challenge: `challenge`
- code: `123456`
- verifier: `verifier-123`

- expected answer hash:
  `0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd`

- expected payload:

```json
{"params":{"identityType":"Email","authMode":"OTP","verifier":"verifier-123","answer":"0x752c0acc530a06ddbccae9295f7fd287037f7e2c19272c7506adce3175075fdd"}}
```

## Full Request-Signing Vector

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
