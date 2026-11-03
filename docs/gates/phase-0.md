# Phase 0 gate (E0 Foundations)

The gate items are copied from [EPICS.md](../EPICS.md). Reference device: the Insignia (API 25).

| Criterion | Evidence | Result |
| --- | --- | --- |
| On the Insignia Fire TV, entering the owner's real reverse-proxied HTTPS URL connects and lists **all** libraries visible to the user. | The owner completed onboarding at the TV: server address, Quick Connect, first sync, then Setup complete. A force-stop and a reboot both land on Setup complete again. | PASS 2026-10-18 |
| The same works with a bare `IP:port` on the LAN. | No recorded run. An http address was tested against a stand-in server in the #241 re-check (2026-10-18), not the owner's server by IP:port. | NOT RECORDED. Awaiting the owner's ruling. |
| Subpath base URLs (e.g. `https://media.example.com/jellyfin`) are proven by automated tests (S0.3.1). | `SubpathTest` in `data/jellyfin/src/test/kotlin/tv/stationbreak/data/jellyfin/SubpathTest.kt`. | PASS |
| Adapter contract tests pass in CI against the **oldest supported and latest Jellyfin 12.x** containers (S0.3.6, ADR-0006). | The `Jellyfin contract` workflow (`.github/workflows/jellyfin-contract.yml`) runs `:data:jellyfin:contractTest` against 12.1 and 12.2, each pinned by digest, and weekly against the floating `latest` tag. | PASS |
| The app was installed on the Insignia by sideloading a CI-built APK, not by a local build. | The debug APK from main CI run 39121389886 (green), installed 2026-10-18. | PASS |
| CI is green on `main`: build, unit tests, lint, detekt, license header check. | Main CI run 39121389886: success (checks, assemble, verify, emulator). | PASS |

## Device checks on the Insignia (2026-10-18)

Build: main at 89a5236, CI run 39121389886. The owner was present.

| Check | Result |
| --- | --- |
| Upgrade from the previous build (cache schema 2 to 4, account kept) | PASS. First sync again, Setup complete after 54 s, no crash. |
| Cold launch | PASS, 1,613 ms |
| Force-stop and relaunch lands on Setup complete | PASS |
| Reboot lands on Setup complete | PASS |
| StrictMode (logcat, 25 s) | PASS |
| Crash buffer | PASS, empty |
| Focus | PASS, window only |

The Firestick 4K (API 30) ran the same checks as a smoke test only: all passed except reboot, which is run on the Insignia only. It is not gate evidence.

The ServerProbe crash re-check (#241) passed 3 rounds on the Insignia on 2026-10-18. The "Connect without encryption?" dialog opened with Cancel focused, and there was no crash, ANR or StrictMode line. A run against the owner's real HTTPS server then reached Setup complete.

## Limits

- StrictMode was read from logcat only. It cannot see a main-thread network call that is caught and not logged. The empty crash buffer covers an uncaught exception.
- Focus was checked at window level only. It is not a D-pad focus check.
- The upgrade was tested from cache schema 2 only.

## Sign-off

Pending owner sign-off.
