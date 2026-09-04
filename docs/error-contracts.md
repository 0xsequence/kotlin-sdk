# Public Error Contracts

This matrix is the audit surface for SDK error behavior. It documents which public runtime
surfaces can fail, what error shape users should see, what recovery decision the error supports,
whether `upstreamError` should be present, and which tests own the contract.

## Terms

- `upstreamError` is normalized diagnostic detail from a remote OMS service response or transport
  failure. It is for logging and service-specific troubleshooting. Application logic should usually
  branch on the SDK-level `code`.
- `retryable` is nullable and describes the failed SDK operation when retry semantics apply. A
  retryable status lookup failure does not mean the original transaction write should be blindly
  resent.
- `OMS_TRANSACTION_EXECUTION_UNCONFIRMED` means transaction preparation succeeded, but the execute request
  failed before the SDK could confirm whether the transaction was submitted. Do not blindly resend
  the same write solely because the upstream failure looked temporary.
- `OMS_TRANSACTION_STATUS_LOOKUP_FAILED` means the transaction was submitted, but post-submit status
  polling failed. Retry by checking transaction status with the returned `txnId`.

## Maintenance Approach

- Update this matrix, the centralized `PublicErrorContractsTest`, and public docs together when a
  public SDK method gains, removes, or intentionally changes an error contract.
- Keep backend and upstream mapping tests representative rather than exhaustive per method. Cover
  each transport or response family through real public calls instead of duplicating the same
  matrix across focused tests.
- Do not assert manually constructed `OMSWalletException` subclasses unless the error class or helper
  is the unit under test. Public runtime APIs should own runtime error contract coverage.
- Android storage and Keystore signer classes are internal platform boundaries in this SDK, not
  separate public error surfaces. Cover their failures in focused platform tests unless a failure is
  intentionally normalized through a documented public `OMSWalletException`.
- Serialized contract changes are not automatically regressions. Decide whether the new error shape
  is the intended public contract: if correct, update the assertion and related docs; if accidental,
  fix the implementation. Never update expectations blindly.
- Use `code` and `operation` as the primary compatibility contract. Treat message changes as
  intentional user-visible API/UX changes, even when they do not change recovery behavior.

## SDK Matrix

| Public surface | Failure family | User-facing error | Recovery meaning | `upstreamError` | Covering test |
|---|---|---|---|---|---|
| `client.wallet.startEmailAuth`, representative WaaS methods | WaaS transport failure | `OMSWalletRequestException`, `OMS_REQUEST_FAILED`, operation-specific, retryable when transport/5xx | Retry the same read/auth request when appropriate | Present | `PublicErrorContractsTest` |
| `client.wallet.completeEmailAuth` | WaaS domain error | SDK-specific code such as `OMS_AUTH_COMMITMENT_CONSUMED` | Follow the SDK code; for consumed commitments, restart auth | Present | `PublicErrorContractsTest` |
| `client.wallet.*`, representative WaaS methods | WaaS HTTP error | `OMSWalletRequestException`, `OMS_HTTP_ERROR`, status, retryable for 5xx | Use SDK code/status for branching; log upstream detail | Present | `PublicErrorContractsTest` |
| `client.wallet.completeEmailAuth` and pending wallet selection actions | Local auth/session/selection state | `OMSWalletSessionException` or `OMSWalletSelectionException` | Fix local flow state or restart auth; do not look for backend diagnostics | Absent | `PublicErrorContractsTest` |
| OIDC redirect/id-token auth methods | Local OIDC config, callback, or state mismatch | `OMSWalletSessionException` or `OMSWalletValidationException` | Fix redirect config/state or restart OIDC flow | Absent | `PublicErrorContractsTest` |
| `client.wallet.startOidcRedirectAuth` | Local OIDC redirect-state persistence failure | `OMSWalletStorageException`, `OMS_STORAGE_ERROR` | Retry starting OIDC auth after the local storage issue is resolved | Absent | `PublicErrorContractsTest` |
| `client.wallet.signOut` | Persistent session, redirect-state, or signer cleanup failure | `OMSWalletStorageException`, `OMS_STORAGE_ERROR`; in-memory session is already cleared | Keep the user signed out locally; report or retry persistent cleanup as appropriate | Absent | `WalletSessionTest` |
| Protected wallet methods: `getIdToken`, signing and transaction methods, wallet import, `getTransactionStatus`, access inspection/authorization/usage/listing/revocation | Missing, expired, or stale local session | `OMSWalletSessionException` | Authenticate again or recover local session; no remote request was made | Absent | `PublicErrorContractsTest` |
| Wallet auth, signing, transactions, import, and owner access methods | SDK-local validation or fee-selection failure | `OMSWalletValidationException` | Correct parameters or local fee selection; do not retry as an upstream outage | Absent | `PublicErrorContractsTest`, `WalletImportCryptoTest`, `WalletAccessTest` |
| `client.wallet.getWalletImportRecipientKey`, `importWallet`, `importEncryptedWallet` | Recipient-key attestation is missing, stale, malformed, untrusted, or does not match the request/response | `OMSWalletAttestationException`, `OMS_ATTESTATION_VERIFICATION_FAILED` | Do not encrypt or submit key material; retry only after confirming the configured PCR0 allowlist and WaaS environment | Absent | `WalletImportCryptoTest` |
| `client.wallet.isValidMessageSignature`, `isValidTypedDataSignature` | WaaS validation backend failure | `OMSWalletRequestException` or `OMSWalletResponseException` with validation operation | Retry based on SDK code/status; log upstream detail | Present | `PublicErrorContractsTest` |
| `client.wallet.sendTransaction`, `callContract` | Execute request fails after prepare | `OMSWalletTransactionException`, `OMS_TRANSACTION_EXECUTION_UNCONFIRMED`, `retryable = false`, `txnId` | Do not blindly resend the write; preserve `txnId` and upstream detail for diagnostics | Present when execute crossed transport/upstream boundary | `PublicErrorContractsTest` |
| `client.wallet.sendTransaction`, `callContract` | Submitted transaction status polling fails | `OMSWalletTransactionException`, `OMS_TRANSACTION_STATUS_LOOKUP_FAILED`, `retryable = true`, `txnId` | Retry status lookup, not the original write | Present when polling crossed transport/upstream boundary | `PublicErrorContractsTest` |
| `client.wallet.getTransactionStatus` | Direct status lookup backend failure | `OMSWalletRequestException` or `OMSWalletResponseException` with status operation | Retry status lookup or surface backend status to the user | Present | `PublicErrorContractsTest` |
| `client.wallet.inspectRemoteCredential`, `authorizeRemoteAccess`, `getRemoteAccessSession`, `getRemoteAccessSessionUsage`, access listing and revocation | WaaS owner-access backend failure | `OMSWalletRequestException` or `OMSWalletResponseException` with access operation | Retry based on SDK code/status; log upstream detail | Present | `PublicErrorContractsTest`, `WalletAccessTest` |
| `client.indexer.getBalances`, `getTransactionHistory`, `getSolanaBalances` | IndexerGateway backend, transport, malformed JSON, or malformed payload | `OMSWalletRequestException` or `OMSWalletResponseException` with indexer operation | Retry based on SDK code/status; log upstream detail | Present for remote/transport response failures | `PublicErrorContractsTest`, `ServiceClientsTest` |
| `client.indexer.getBalances`, `getTransactionHistory`, `getSolanaBalances` | IndexerGateway non-JSON HTTP body | `OMSWalletRequestException`, `OMS_HTTP_ERROR`, sanitized message | Do not expose raw upstream HTML/text bodies; log normalized detail | Present, sanitized | `PublicErrorContractsTest` |
| Public `OMSWalletException` classes and upstream fields | Error class field contract | Stable public fields on constructed errors | Use only when the error class/helper is the unit under test | As constructed | `PublicErrorContractsTest` |
