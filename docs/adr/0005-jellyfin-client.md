# ADR-0005: Jellyfin client — official SDK on OkHttp

- **Status:** Accepted. Spike SP-4 passed on amended terms: Jellyfin 12.x only ([ADR-0006](0006-jellyfin-12-only.md)).
- **Date:** 2026-10-04
- **Decided by:** Phil Critchfield

## Decision

1. `data:jellyfin` uses **`jellyfin-sdk-kotlin`** (LGPL-3.0), sealed behind the `MediaServer`
   port. No SDK types leak out of the module.
2. The SDK runs on its own **`jellyfin-api-okhttp`** module, so Ktor isn't involved. A single pinned
   OkHttp client (ADR-0001 auth header, S0.5.2 pin) is passed in as `OkHttpFactory(base)` and is
   shared by the SDK, Media3 (`OkHttpDataSource.Factory`), and image loading.
3. Retrofit is not used.

## Gate: spike SP-4 (Phase 0, before F0.3 work)

The SDK must:
- work against **Jellyfin 10.x and 12.x** (failed for 10.x, so only 12.x is supported; see ADR-0006);
- accept an **injected/configured OkHttp client** carrying the per-server trust manager;
- support subpath base URLs;
- surface TLS and connection failures distinctly enough to map to S0.3.4's typed errors.

**If SP-4 fails any check, the decision returns to the owner.** The recommended fallback is a
hand-written client on the same OkHttp client, which doesn't change any other module.

*Outcome (2026-10-06):* the 10.x check failed. The owner had agreed in advance that a 10.x failure
means supporting 12.x only, so that rule was applied instead of reopening the decision (ADR-0006).

## Correction (2026-10-06, SP-4)

Earlier drafts said the SDK is Ktor-based. In fact it ships both a native `jellyfin-api-okhttp` client
and a Ktor client, and StationBreak uses the OkHttp one. This changes only the transport: the decision
to use the SDK stands. SP-4 verified SDK 1.9.2's `jellyfin-api-okhttp`:
- an injected base client's interceptors and trust manager are used for every request;
- subpath base URLs work;
- errors carry the underlying exception as their `cause`.

## Note (2026-10-14, F0.3 Unit 1): trust is per server, so the client must be able to change

The adapter derives its clients from one injected `OkHttpClient` at construction. F0.5 (S0.5.2) pins a certificate per
server, so the trust manager the base client carries has to look the pin up per host at connection time (a dynamic
trust manager) rather than be fixed when the adapter is built, or the app would need a new adapter for every server.
The injected client gets a Hilt qualifier when the app wires it (F0.3 Unit 3), so the base client with system trust and
the pinned one cannot be confused. The adapter drops the base client's interceptors, cookie jar and authenticators for
credential-free calls (the probe) and keeps only its trust configuration.

## Amendment (2026-10-14, F0.3 Unit 2): sign-in calls OkHttp directly

Unit 2's calls (`AuthenticateByName`, `QuickConnect/*`, `Users/Me`, `Sessions/Logout`) use the injected OkHttp client
with small hand-written DTOs, not the SDK, because the one-header, no-redirect, cleartext-gate and error-mapping rules
need full control of the client. The SDK stays for Unit 3's item types, and `ErrorMapper` still reads its exception
types. The ADR-0006 `NoClassDefFoundError: org.slf4j.LoggerFactory` proof moves to Unit 3, the first call made through
the SDK; `slf4j-nop` is already in `app`.

## Amendment (2026-10-14, F0.3 Unit 3): the item calls read lenient local DTOs, not the SDK's models

Unit 3's calls (`UserViews`, `Items`, `PlaybackInfo`, `Sessions/Playing*`) also go through the adapter's own transport
(the cleartext gate, the token binding, the no-redirect client and the subpath helper apply to them), and they read
answers into small hand-written DTOs instead of the SDK's models. Reasons: the SDK's models are strict (a value of an
enum that a newer server adds, or a missing required field, fails the whole page: the 10.11 failure in ADR-0006), and
the library mapping must tell an *absent* collection type (mixed content) from an *unknown* one (dropped), which the
SDK's enums can't. The DTOs read only the fields the port needs, so `MediaSource.Path`, `TranscodingUrl` and any other
server-supplied URL never exist in the adapter's memory (R2). `ErrorMapper` still reads the SDK's exception types.

The SDK is therefore not on any production code path that makes a request. Whether to keep the dependency (LGPL-3.0,
with its transitive dependencies) is a later decision; it is left in place for now, for example in case a future call
needs the SDK's generated request types.

The ADR-0006 proof moved here is recorded: `SdkLoggingBindingTest` makes one request through the SDK's own client on
the API 25 emulator. With `slf4j-nop` left out of `app`'s runtime classpath it fails with
`NoClassDefFoundError: Failed resolution of: Lorg/slf4j/LoggerFactory;`; with it, it passes. The base client is injected
with a Hilt qualifier (`@BaseHttpClient`), as the Unit 1 note required.

