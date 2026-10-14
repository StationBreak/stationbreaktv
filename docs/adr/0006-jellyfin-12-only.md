# ADR-0006: Support Jellyfin 12.x only (spike SP-4 result)

- **Status:** Accepted
- **Date:** 2026-10-06
- **Decided by:** Phil Critchfield
- **Resolves:** Q-JF-1 in [ASSUMPTIONS.md](../ASSUMPTIONS.md) (previously "support both 10.x and 12.x"); amends the SP-4 gate in [ADR-0005](0005-jellyfin-client.md)

## Context

[ADR-0005](0005-jellyfin-client.md) chose `jellyfin-sdk-kotlin`, subject to spike SP-4 ([ROADMAP.md](../ROADMAP.md)). One SP-4 check was that the SDK works
against both Jellyfin 10.x and 12.x.

SDK **1.9.0** (2026-09-08, [release notes](https://github.com/jellyfin/jellyfin-sdk-kotlin/releases/tag/v1.9.0)) raised its minimum server to **12.0** (`Jellyfin.minimumVersion`). Its release notes say the generated API
is "no longer compatible with Jellyfin 10.11 and earlier". **1.8.12** is the last release that targets
10.11 (`minimumVersion` 10.11.0, API generated from 10.11.11), and it predates 12.x. Both releases use the same package names, so they can't ship side by side.

Before the spike ran, the owner decided: if SDK 1.9 works against 10.11 for the endpoints we use,
keep both majors; if not, support 12.x only.

## Spike

A throwaway JVM harness ran SDK **1.9.2** (`jellyfin-core` + `jellyfin-api-okhttp`) against disposable
**Jellyfin 10.11.11** and **12.2.0** containers, each with a five-item fixture library and a non-admin
user. A local self-signed TLS endpoint and a Caddy subpath proxy covered the network checks.

| Check | 10.11.11 | 12.2.0 | 12.2.0 at `/jf` subpath |
| --- | --- | --- | --- |
| Public system info | ✅ | ✅ | ✅ |
| Login (username/password, non-admin) | ✅ | ✅ | ✅ |
| Quick Connect (initiate, authorize, state, authenticate) | ✅ | ✅ | ✅ |
| Library views | ✅ | ✅ | ✅ |
| Items: recursive, paged, all `ItemFields` decode | ❌ | ✅ | ✅ |
| Single item | ⛔ not run | ✅ | ✅ |
| PlaybackInfo (POST with a device profile) | ⛔ not run | ✅ | ✅ |
| Stream URL carries no credentials | ⛔ not run | ✅ | ✅ |
| Playback reporting (start, progress, stopped) | ⛔ not run | ✅ | ✅ |
| Logout (`/Sessions/Logout`) invalidates the token | ✅ | ✅ | ✅ |
| Connection refused, unknown host, and https-on-http errors are distinguishable | ✅ | ✅ | ✅ |
| Self-signed cert rejected by default trust | ✅ | ✅ | n/a |
| Injected OkHttp client: SPKI pin accepted, wrong pin rejected, interceptor sees every request | ✅ | ✅ | ✅ (interceptor only) |

⛔ **not run** means the check depends on item IDs from the failed items call. Those checks return media
sources, which carry the same `MediaStream` type, so they are expected to fail the same way.

The 10.11 failure is a decoding error, not a missing endpoint:
`MissingFieldException: Field 'IsOriginal' is required for type MediaStream`. Items failed to decode. Single item, PlaybackInfo, the stream URL and playback reporting weren't
exercised on 10.11. They all depend on media sources, which carry the same `MediaStream` type, so
the core of the app is expected to fail the same way.

## Decision

1. StationBreak supports **Jellyfin 12.0 and later**. 10.x isn't supported.
2. `data:jellyfin` uses SDK **1.9.2** (the tested version) via `jellyfin-api-okhttp` (see the ADR-0005
   correction). SDK upgrades land only after the CI contract matrix passes.
3. At connect time, a server below 12.0 produces the typed "unsupported server version" error
   (S0.3.4), with a message telling the user to upgrade Jellyfin.
4. The CI contract-test matrix (S0.3.6) runs the **oldest supported 12.x and the latest 12.x**. The
   oldest supported version is **12.0** unless CI shows otherwise. The spike tested only 12.2.0, so
   S0.3.6 records the exact versions that pass.
   The version matrix stays one line per entry, so a future 13.x can be added the same way.

## Consequences

- One generated API to track instead of two, and no version shim inside `data:jellyfin`.
- Users still on 10.x must upgrade their server first. The trade-off is accepted because the project
  owner runs 12.x, and 10.x can't work with the current SDK without a custom client. If demand for
  10.x appears, the fallback is ADR-0005's hand-written client.
- A new 12.x minor can still add required fields. The CI matrix against the latest 12.x catches that
  before users do.

## Notes for implementation (found during the spike)

- **Errors:** connection refused, unknown host, and timeouts all surface as the SDK's `TimeoutException`.
  The original `ConnectException`, `UnknownHostException`, or `InterruptedIOException` is the `cause`,
  so the S0.3.4 mapper must inspect the cause chain, including suppressed failures (OkHttp attaches the failures of a
  name's other addresses that way). That is enough to detect refused and unreachable. The plaintext case also needs
  the `SSLException` message (S0.3.1, [ADR-0014](0014-scheme-fallback-and-cleartext-policy.md)). TLS failures surface as
  `SecureConnectionException`, with `SSLHandshakeException` (untrusted or pin mismatch) or `SSLException` as the cause.
- **Dependencies:** the SDK exposes `okhttp3.OkHttpClient` in its API (`OkHttpFactory(base)`) but
  doesn't export OkHttp or kotlinx-coroutines transitively, so `data:jellyfin` declares both itself.
  Pin them to the SDK's versions (OkHttp 5.5.0 and coroutines 1.11.0 for SDK 1.9.2). On Android it also
  needs an SLF4J binding: without one, the first request crashes with `NoClassDefFoundError:
  org.slf4j.LoggerFactory` (found in SP-1, [ADR-0007](0007-tune-latency-spike.md)).
- **Device ID:** Jellyfin revokes a device's earlier token when the same device ID logs in again.
  Profiles (ADR-0001) and Quick Connect onboarding must not reuse one device ID across simultaneous
  sessions. The ID is per install: a random UUID kept under its own key in the `SecretStore`, never derived from
  `ANDROID_ID`, and kept across sign-out. The next sign-in on the TV therefore revokes a token that a failed logout
  left on the server (F0.3 Unit 2).
- **Resume position:** short fixture items (< 5 minutes) don't store a resume position, because of
  Jellyfin's minimum resume duration. S1.5.1 tests need fixtures longer than that.
- **Logging:** the SDK logs each request's method and URL (with the token masked) through
  kotlin-logging. StationBreak routes that through its own logger, so S3.6.1 hostname redaction
  still applies.
