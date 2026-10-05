# ADR-0005: Jellyfin client — official SDK on Ktor/OkHttp

- **Status:** Accepted (subject to spike SP-4)
- **Date:** 2026-10-04
- **Decided by:** Phil Critchfield

## Decision

1. `data:jellyfin` uses **`jellyfin-sdk-kotlin`** (LGPL-3.0, Ktor-based), sealed behind the `MediaServer`
   port. No SDK types leak out of the module.
2. Ktor runs on the **OkHttp engine**. A single pinned OkHttp client (ADR-0001 auth header, S0.5.2 pin)
   is shared by the SDK, Media3 (`OkHttpDataSource.Factory`), and image loading.
3. Retrofit is not used.

## Gate: spike SP-4 (Phase 0, before F0.3 work)

The SDK must:
- work against **Jellyfin 10.x and 12.x**;
- accept an **injected/configured OkHttp client** carrying the per-server trust manager;
- support subpath base URLs;
- surface TLS and connection failures distinctly enough to map to S0.3.4's typed errors.

**If SP-4 fails any check, the decision returns to the owner.** The recommended fallback is a
hand-written Ktor client on the same OkHttp engine, which doesn't change any other module.
