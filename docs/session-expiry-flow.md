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
- An expired stored session is not restored as active, but its metadata stays in
  storage so `onSessionExpired` can replay after process recreation.
- Active sessions can expire from the scheduled task or from a protected wallet
  operation checking the session before use. Both refresh first: an extended
  expiry keeps the session, and only a session WaaS cannot extend expires.
- A request signed with the session's credential that WaaS rejects with 7202
  `KeyNotFound` or 7203 `KeyExpired` ends the session like local expiry and fails
  with `OMS_SESSION_EXPIRED`. Unsigned `WaasPublic` calls never end the session.
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
| Refreshed | `GetCredential` succeeded | A parseable `expiresAt` replaces the session expiry (later or earlier), is saved to the session store, and reschedules the expiry task |
| Ended | 7202 or 7203 | The transport already ended the session and notified listeners |
| Unsupported | `WebrpcBadRoute` (WaaS without `GetCredential`) | Remembered for the scope's lifetime; local expiry stays exact |
| Unavailable | Network or any other error | Session unchanged |
| Busy | A signed session request was in flight when a background refresh tried to start | Skipped until the next trigger |

Triggers:

- Restore (`restorePersistedSession`): background refresh after a session is
  restored.
- Expiry task: refresh first. Refreshed reschedules; Ended or Unsupported expire
  as before; Unavailable or Busy keep the session and recheck after 60 seconds,
  doubling up to 15 minutes.
- Protected operation on a locally expired session: awaits the refresh.
  Refreshed continues the operation; Ended or Unsupported throw
  `OMS_SESSION_EXPIRED`; Unavailable throws the network error and keeps the
  session.
- After a successful signed call (not `GetCredential`): background refresh when
  the last refresh attempt or sign-in was at least 6 hours ago.

WaaS requires strictly increasing nonces per credential, so a refresh never
overlaps another signed session request: background refreshes start only when
no signed request is in flight, the awaited refresh waits for in-flight requests
to finish, and signed requests started during a refresh wait for it before
signing.

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
  O -- "Yes" --> RF{"Refresh credential"}
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
