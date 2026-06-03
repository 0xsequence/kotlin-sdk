# Session Expiry Flow

This note documents the wallet session expiry flow for maintainers. Public API
behavior is covered in `docs/api.md`; this file focuses on how restored, active,
and stale expiry paths are coordinated.

## Behavior Contract

- A valid stored session is restored into memory and gets an expiry task.
- An expired stored session is not restored as active, but its metadata stays in
  storage so `onSessionExpired` can replay after process recreation.
- Active sessions can expire from the scheduled task or from a protected wallet
  operation checking the session before use.
- `signOut()` or a new auth flow clears or replaces stored session metadata,
  which cancels stale expired-session replay.

## Flow

```mermaid
flowchart TD
  A["WalletClient.restorePersistedSession"] --> B{"Stored restorable session?"}
  B -- "No" --> C["Start signed out"]

  B -- "Yes" --> D["Build restored session snapshot"]
  D --> E{"Snapshot expired?"}

  E -- "No" --> F["Restore active in-memory session"]
  F --> G["Schedule active session expiry task"]

  E -- "Yes" --> H["Keep expired metadata in storage"]
  H --> I["Do not restore active in-memory session"]
  I --> J["Clear signer credential"]
  J --> K["Notify onSessionExpired"]

  G --> L["Expiry task fires"]
  L --> M{"In-memory session snapshot still current?"}
  M -- "No" --> N["Ignore stale task"]
  M -- "Yes" --> O{"Session expired now?"}
  O -- "No" --> G
  O -- "Yes" --> P["Clear in-memory session and signer"]
  P --> Q["Keep expired metadata in storage"]
  Q --> K

  R["Protected wallet operation"] --> S{"Current session expired?"}
  S -- "No" --> T["Continue operation"]
  S -- "Yes" --> P

  U["signOut or new auth flow"] --> V["Clear or replace stored session"]
  V --> N
```
