# ADR-0001: Client authentication — user tokens, not API keys

- **Status:** Accepted
- **Date:** 2026-10-04
- **Decided by:** Phil Critchfield, on a veto escalated by Angua (Security)

## Context

The spec listed "API key or user login" for the Jellyfin adapter. Jellyfin dashboard API keys
(Dashboard → API Keys) are **not tied to a user**: requests made with one get the Administrator role.
There's no way to scope a key to a user or make it read-only. That's an open upstream request:
[jellyfin/jellyfin#13992](https://github.com/jellyfin/jellyfin/issues/13992).

Storing an admin-level credential on a TV contradicts the spec's advice to use a dedicated non-admin user.

## Decision

1. **The TV client** authenticates with **username/password** (`AuthenticateByName`) or **Quick Connect**.
   Both yield a **user access token**. The token is scoped to that user's policy and library access,
   long-lived by default, and revocable (Dashboard → Devices, or `POST /Sessions/Logout`).
2. **The app's primary account is a normal, non-admin Jellyfin user** (e.g. `stationbreak`). Reporting
   playback writes that user's own watched state and resume position. Normal users are allowed to do this,
   so no admin rights are needed.
3. **Additional profiles** (Phase 5) are separate Jellyfin users, each added via Quick Connect approved from
   that person's own signed-in device.
4. **Server-side components** (the Phase 5 companion container) authenticate as a non-admin
   **service user** and store its token. A dashboard API key is used only if a feature truly
   requires admin rights, and that needs a new ADR.

## Consequences

- S0.3.2 drops API-key mode. Quick Connect moves into Phase 0.
- If Quick Connect is disabled on the server, the app offers password login.
- Revisit this ADR if Jellyfin ships scoped API keys (#13992).
