# ADR-0010: Application ID `tv.stationbreak.app`

- **Status:** Accepted
- **Date:** 2026-10-10
- **Decided by:** Phil Critchfield
- **Informs:** F0.1, F0.2 (S0.2.2)

## Context

The Android application ID identifies the app on every device and store. Once a build is published under it,
it can't change without the app becoming a different app: existing installs don't upgrade, and stored settings
and sign-in don't carry over. The ID has to be chosen before the module skeleton (F0.2) is built.

The options considered were:
- `tv.stationbreak.app`
- `tv.stationbreak`
- `io.github.stationbreak.tv`

## Decision

1. The application ID is **`tv.stationbreak.app`**. It's based on the reversed project domain, `stationbreak.tv`,
   which the owner has registered and will keep for as long as the app exists.
2. The Kotlin package root and Gradle `namespace` use the same prefix, `tv.stationbreak`.
3. **Every release flavor uses the same ID** (decided 2026-10-10), including the `github` build. Builds from
   different channels may end up signed with different keys, because a store or F-Droid can sign with its own.
   Switching between those copies then means uninstalling first. That's accepted: most people install from one
   source only. Debug builds are a separate question (Q-8).

## Consequences

- The ID is tied to the domain staying in the project's hands. If the domain ever lapsed, the ID would still
  work, but would no longer prove who publishes the app.
- `tv.stationbreak.app` leaves the rest of the `tv.stationbreak` namespace free for any later companion apps or
  libraries.
- `io.github.stationbreak.tv` was rejected because it ties the identity to the code host.
