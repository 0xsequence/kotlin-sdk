# Session Expiry Flow

This note documents the wallet session expiry flow for maintainers. Public API
behavior is covered in `docs/api.md`; this file focuses on how restored, active,
and stale expiry paths are coordinated.

## Behavior Contract

- `OMSWalletSession.expiresAt` (and `WalletCredential.expiresAt`) is the current
  expiry: WaaS slides it forward with use up to the requested lifetime (at most
  180 days), and the SDK refreshes it with WaaS `GetCredential`.
- A valid stored session is restored into memory, gets an expiry task, and is
  refreshed in the background. Restore itself never waits for or fails on the
  refresh.
- An expired stored session is discarded on restore without asking WaaS: it is
  not restored as active, but its metadata stays in storage so
  `onSessionExpired` can replay after process recreation.
- A session active in memory is checked with WaaS before it is treated as
  ended. It can expire from the scheduled task or from a protected wallet
  operation checking the session before use. Both refresh first: an extended
  expiry keeps the session, and only a session WaaS cannot extend expires.
- WaaS ends a session about 10 days after its last use (the idle window), or at
  the requested lifetime (the ceiling). Every request signed with the session's
  credential, including `GetCredential`, extends the idle window, so the expiry
  task does not read the credential of a session that looks idle until WaaS
  should have ended it. Without SDK activity since the last expiry read, the SDK
  notices an ended idle session up to 5 minutes after WaaS ended it; after later
  requests, WaaS's 6-hour slide dampening can make it notice up to about 6 hours
  later, and after a restore whose read failed, by `min(L + 12 hours, N) + G` (the
  restore bound, while no read or request has completed since the restore). These
  times assume an accurate device clock (the expiry is WaaS time compared with
  device time) and an app running in the foreground; the task cannot fire while
  the app is suspended or not running, and after a restart the restore discards
  a stored session more than 12 hours past its expiry (`L + 6 hours + 6 hours + G`:
  the dampening, plus requests until the next read, which follows the first
  successful request once the last read is 6 hours old), and below the ceiling
  restores a more recently expired one for its read to decide.
- Sign-in records the session's idle window `I` and ceiling `N` from
  `CompleteAuth`: with `C0` the request's send time, `L0` the returned expiry,
  and `R` the requested lifetime, `I = L0 - C0` and `N = max(C0 + R, L0)`
  (unknown when `L0` does not parse or `I <= 0`). They are kept with the
  session and saved in the session store record as `idleWindowMillis` and
  `expiryCeilingEpochMillis`. A stored record without valid bounds (saved by
  an earlier 0.4.0 build, or for a session whose bounds were unknown) is
  discarded on restore like any invalid record, and the user signs in again.
- A request signed with the session's credential that WaaS rejects with 7202
  `KeyNotFound` or 7203 `KeyExpired`, or a `GetCredential` that WaaS answers with
  7207 `Unauthorized` (the credential was revoked), ends the session like local
  expiry and fails with `OMS_SESSION_EXPIRED`. 7207 from any other call stays a
  request error. Unsigned `WaasPublic` calls never end the session.
- `signOut()` or a new auth flow clears or replaces stored session metadata,
  which cancels stale expired-session replay.

## Credential Refresh

`refreshSessionCredential` in `WalletClient` is the only refresh primitive.
Concurrent callers for the same session share one in-flight request, and a
result is applied only while the session revision that started it is current
(expiry updates do not bump the revision, so in-flight operations keep their
ownership). Outcomes:

| Outcome | Cause | Effect |
|---|---|---|
| Refreshed | `GetCredential` succeeded with a parseable `expiresAt` | The expiry replaces the session expiry (later or earlier), is saved to the session store, and reschedules the expiry task; its completion time becomes the last expiry read |
| Ended | 7202 or 7203 (the transport ends the session), or 7207 (the refresh ends it) | The session ended and listeners were notified |
| Unsupported | `WebrpcBadRoute` (WaaS without `GetCredential`) | Refreshes are skipped and local expiry stays exact for 15 minutes; then the next trigger probes `GetCredential` again, and an answer clears it while another `WebrpcBadRoute` restarts the 15 minutes |
| Unavailable | Network or any other error, or an `expiresAt` that does not parse | Session unchanged |
| Busy | A signed session request was in flight when a background refresh tried to start, or the caller that started the shared refresh was cancelled | Skipped until the next trigger; a caller that needs an answer (a protected operation) starts a new refresh |

Triggers:

- Restore (`restorePersistedSession`): background refresh after a session is
  restored. The refresh extends an idle session, so it runs only while the app
  has a started activity (the process lifecycle, `ProcessLifecycleOwner`): a
  process started in the background (a push message, a broadcast, a scheduled
  job, or boot) runs it once an activity starts, and skips it if the session
  changed meanwhile. A restore
  leaves the 6-hour refresh clock unset, and so does a restore refresh that is
  Busy or Unavailable, so until a read reaches WaaS the next successful signed
  call refreshes.
- Expiry task on a locally expired wallet session at local expiry `L`: let `C` be
  this process's last expiry read (the send time of the sign-in `CompleteAuth`
  or the completion time of the last successful `GetCredential`; a restore
  leaves it unset) and `A` the time the last request signed with the session's
  credential completed (response received or error thrown), `GetCredential`
  included, whatever its outcome. WaaS slides the expiry when it receives a
  request, which is no later than its completion, so a request that stalls on
  the way still counts from when it completed. A restore sets `A` to the restore
  time, which bounds the previous process's requests. The task's own
  `GetCredential` checks are not counted: they are sent only past the bound,
  when WaaS has already ended an idle session and cannot slide it. Refreshes
  never overlap other signed session requests, so a successful read leaves
  `A <= C` until another request completes. The task waits until 5 minutes
  (`G`) past an upper bound on WaaS's expiry:
  - No request since the last read (`A <= C`): WaaS's expiry is `L`, so `L + G`.
  - Otherwise: each request moves WaaS's expiry toward `min(request time + I, N)`
    and never past it (dampening can only leave it lower), so
    `max(L, min(A + I, N)) + G`.
  - Bounds unknown in memory (a sign-in whose expiry was not after its send
    time): `L + max(0, A - C) + 6 hours + G`, or `L + 6 hours + G` while `C` is
    unset, with 6 hours being WaaS's slide dampening.

  Until then it reschedules without a request, re-evaluating each time it fires.
  Then it refreshes: Refreshed reschedules; Ended or Unsupported expire as
  before; Unavailable or Busy keep the session and recheck after 60 seconds,
  doubling up to 15 minutes. A pending manual wallet
  selection, and a WaaS known to lack `GetCredential`, refresh at `L` without
  waiting.
- Protected operation on a locally expired session: awaits the refresh.
  Refreshed continues the operation; Ended or Unsupported throw
  `OMS_SESSION_EXPIRED`; Unavailable throws the network error and keeps the
  session.
- After a successful signed call (not `GetCredential`): background refresh when
  the last `GetCredential` sent or sign-in was at least 6 hours ago, or no
  `GetCredential` has reached WaaS since a restore. A refresh skipped as Busy
  sends nothing, so it does not restart the 6 hours and the next successful
  signed call tries again.
- A signed call other than `GetCredential` answered with 7207 `Unauthorized`:
  background refresh (shared with one already in flight), because 7207 can mean
  a revoked credential or only a wallet access error. A 7207 answer to the
  refresh ends the session; otherwise the call stays a request error and the
  session continues.

WaaS requires strictly increasing nonces per credential, so every request
signed with one credential, refreshes included, takes one turn at a time, first
come first served, across every client of the scope in the process; requests
signed with another credential (a new sign-in's key) do not wait for them. A
background refresh starts only when the credential's queue is empty and is
skipped otherwise; an awaited refresh waits for its turn within its 30-second
deadline. Requests from another process, device, or SDK version with the same
credential are not ordered with these, so one can still reach WaaS after a
request with a later nonce; WaaS then rejects it in its credential middleware,
before running the method, with 7206 `PreconditionFailed` (HTTP 412), and the
signed transport signs it again with a fresh nonce and resends it, at most 2
times.

## Flow

```mermaid
flowchart TD
  A["WalletClient.restorePersistedSession"] --> B{"Stored restorable session?"}
  B -- "No" --> C["Start signed out"]

  B -- "Yes" --> D["Build restored session snapshot"]
  D --> E{"Snapshot expired?"}

  E -- "No" --> F["Restore active in-memory session"]
  F --> G["Schedule active session expiry task"]
  F --> R0["Background credential refresh"]

  E -- "Yes" --> H["Keep expired metadata in storage"]
  H --> I["Do not restore active in-memory session"]
  I --> J["Clear signer credential"]
  J --> K["Notify onSessionExpired"]

  G --> L["Expiry task fires"]
  L --> M{"In-memory session snapshot still current?"}
  M -- "No" --> N["Ignore stale task"]
  M -- "Yes" --> O{"Session expired now?"}
  O -- "No" --> G
  O -- "Yes" --> IW{"Wallet session before bound on WaaS expiry + 5 minutes?"}
  IW -- "Yes" --> IR["Reschedule to that time, no request"]
  IR --> L
  IW -- "No" --> RF{"Refresh credential"}
  RF -- "Refreshed" --> G
  RF -- "Unavailable or Busy" --> RT["Keep session, recheck later"]
  RT --> L
  RF -- "Ended or Unsupported" --> P["Clear in-memory session and signer"]
  P --> Q["Keep expired metadata in storage"]
  Q --> K

  S0["Protected wallet operation"] --> S{"Current session expired?"}
  S -- "No" --> T["Continue operation"]
  S -- "Yes" --> RO{"Refresh credential"}
  RO -- "Refreshed" --> T
  RO -- "Unavailable" --> X["Throw network error, keep session"]
  RO -- "Ended or Unsupported" --> P
  T --> W{"WaaS rejects credential (7202/7203)?"}
  W -- "Yes" --> P

  U["signOut or new auth flow"] --> V["Clear or replace stored session"]
  V --> N
```
