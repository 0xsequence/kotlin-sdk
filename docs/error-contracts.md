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
- `TransactionExecutionUnconfirmed` means transaction preparation succeeded, but the execute request
  failed before the SDK could confirm whether the transaction was submitted. Do not blindly resend
  the same write solely because the upstream failure looked temporary.
- `TransactionStatusLookupFailed` means the transaction was submitted, but post-submit status
  polling failed. Retry by checking transaction status with the returned `txnId`.

## SDK Matrix

| Public surface | Failure family | User-facing error | Recovery meaning | `upstreamError` | Covering test |
|---|---|---|---|---|---|
| `client.wallet.startEmailAuth`, representative WaaS methods | WaaS transport failure | `OmsRequestException`, `RequestFailed`, operation-specific, retryable when transport/5xx | Retry the same read/auth request when appropriate | Present | `PublicErrorContractsTest` |
| `client.wallet.completeEmailAuth` | WaaS domain error | SDK-specific code such as `AuthCommitmentConsumed` | Follow the SDK code; for consumed commitments, restart auth | Present | `PublicErrorContractsTest` |
| `client.wallet.*`, representative WaaS methods | WaaS HTTP error | `OmsRequestException`, `HttpError`, status, retryable for 5xx | Use SDK code/status for branching; log upstream detail | Present | `PublicErrorContractsTest` |
| `client.wallet.completeEmailAuth` and pending wallet selection actions | Local auth/session/selection state | `OmsSessionException` or `OmsWalletSelectionException` | Fix local flow state or restart auth; do not look for backend diagnostics | Absent | `PublicErrorContractsTest` |
| OIDC redirect/id-token auth methods | Local OIDC config, callback, storage, or state mismatch | `OmsSessionException`, `OmsValidationException`, or `OidcRedirectAuthResult.Failed` with `OmsSdkException` | Fix redirect config/state or restart OIDC flow | Absent | `PublicErrorContractsTest` |
| Protected wallet methods: `getIdToken`, `signMessage`, `signTypedData`, `sendTransaction`, `callContract`, `getTransactionStatus`, `listAccess`, `listAccessPages`, `revokeAccess` | Missing, expired, or stale local session | `OmsSessionException` | Authenticate again or recover local session; no remote request was made | Absent | `PublicErrorContractsTest` |
| `client.wallet.signMessage`, `signTypedData`, `getIdToken`, `sendTransaction`, `callContract` | SDK-local validation or fee-selection failure | `OmsValidationException` | Correct parameters or local fee selection; do not retry as an upstream outage | Absent | `PublicErrorContractsTest` |
| `client.wallet.isValidMessageSignature`, `isValidTypedDataSignature` | WaaS validation backend failure | `OmsRequestException` or `OmsResponseException` with validation operation | Retry based on SDK code/status; log upstream detail | Present | `PublicErrorContractsTest` |
| `client.wallet.sendTransaction`, `callContract` | Execute request fails after prepare | `OmsTransactionException`, `TransactionExecutionUnconfirmed`, `retryable = false`, `txnId` | Do not blindly resend the write; preserve `txnId` and upstream detail for diagnostics | Present when execute crossed transport/upstream boundary | `PublicErrorContractsTest` |
| `client.wallet.sendTransaction`, `callContract` | Submitted transaction status polling fails | `OmsTransactionException`, `TransactionStatusLookupFailed`, `retryable = true`, `txnId` | Retry status lookup, not the original write | Present when polling crossed transport/upstream boundary | `PublicErrorContractsTest` |
| `client.wallet.getTransactionStatus` | Direct status lookup backend failure | `OmsRequestException` or `OmsResponseException` with status operation | Retry status lookup or surface backend status to the user | Present | `PublicErrorContractsTest` |
| `client.wallet.listAccess`, `listAccessPages`, `revokeAccess` | WaaS access backend failure | `OmsRequestException` or `OmsResponseException` with access operation | Retry based on SDK code/status; log upstream detail | Present | `PublicErrorContractsTest` |
| `client.indexer.getTokenBalances`, `getNativeTokenBalance` | Indexer backend, transport, malformed JSON, or malformed payload | `OmsRequestException` or `OmsResponseException` with indexer operation | Retry based on SDK code/status; log upstream detail | Present for remote/transport response failures | `PublicErrorContractsTest` |
| `client.indexer.getTokenBalances`, `getNativeTokenBalance` | Indexer non-JSON HTTP body | `OmsRequestException`, `HttpError`, sanitized message | Do not expose raw upstream HTML/text bodies; log normalized detail | Present, sanitized | `PublicErrorContractsTest` |
| Public `OmsSdkException` classes and upstream fields | Error class field contract | Stable public fields on constructed errors | Use only when the error class/helper is the unit under test | As constructed | `PublicErrorContractsTest` |
