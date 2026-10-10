# ADR-0009: Keystore-backed secret storage on Fire OS (spike SP-3 result)

- **Status:** Accepted
- **Date:** 2026-10-10
- **Decided by:** Phil Critchfield
- **Resolves:** spike SP-3 ([ROADMAP.md](../ROADMAP.md))
- **Informs:** R-SEC-2, F0.4 (S0.4.1)

## Context

S0.4.1 will store the Jellyfin access token encrypted with an AndroidKeyStore AES-256-GCM key, with no user
authentication (TVs have no lock screen), and will fail closed to "sign in again". Risk R-SEC-2 asked whether
Keystore works reliably on budget Fire OS firmware. SP-3 tested the design on the reference device
(Insignia, Fire OS 6.7.0.8, API 25), with a Fire TV Stick 4K (2nd gen, API 30) as a comparison only.

## Spike

A throwaway app (branch `spike/sp-3`) implemented the S0.4.1 design with a dummy secret. It used no network
and no real credential:
- the key spec is AES-256, GCM, no padding, `setUserAuthenticationRequired(false)`;
- the Keystore generates the IV;
- the stored blob is a version byte, an IV-length byte, the IV, then the ciphertext and tag;
- `android:allowBackup="false"`.

It tested key properties, timing, persistence across a restart, an app update and three reboots, and the
fail-closed paths.

### Results

| | Insignia (API 25, reference) | Firestick (API 30, comparison) |
| --- | --- | --- |
| Hardware-backed key | **Yes**, as self-reported by `KeyInfo.isInsideSecureHardware` (TEE keymaster; not attested) | Yes, self-reported (TEE; no StrongBox) |
| Key generation | 22 ms | 25 ms |
| Warm encrypt / decrypt, median | 5.8 / 5.2 ms | 7.8 / 7.3 ms |
| Force-stop and relaunch | Pass (4 of 4) | Pass (3 of 3) |
| App update (same signing key) | Pass | Pass |
| Reboot, then decrypt with no interaction | **Pass (3 of 3)**: decrypt from a `BOOT_COMPLETED` receiver about 96 s after boot, 19–47 ms (`getKey` 14–45 ms) | Pass (3 of 3); the profile picker on screen doesn't block it |

**Fail-closed paths.** On the Insignia every injected fault ended with the key and the blob gone, the "sign in
again" state, no crash and no plaintext. The Firestick raised the same exceptions. The table lists what the
platform does for each fault:

| Fault | What the platform does |
| --- | --- |
| `pm clear` (clear app data) | Deletes the Keystore key as well as the files. No exception. |
| Key deleted, blob kept | `getKey` returns **null**. No exception. |
| One ciphertext byte flipped | `AEADBadTagException` |
| Blob truncated by a few bytes | `AEADBadTagException` |
| Ciphertext shorter than the tag | `IllegalBlockSizeException` |
| Blob truncated inside the IV | The app's own blob-format check |

**Hygiene** (both devices): the app's data directory held only the 79-byte blob (mode 0600). The plaintext secret appeared
neither there nor in logcat.

**Backup:** `adb backup` produced an empty file on both devices, because Fire OS has no active backup service.
So this spike is **not** evidence that `allowBackup="false"` works. It only shows backups aren't possible
through that route on these devices.

## Decision

1. **SP-3 passes.** The core of the S0.4.1 design stands: an AndroidKeyStore AES-256-GCM key, no user
   authentication, a Keystore-generated IV, and a versioned blob.
2. **Fail-closed is broadened and made precise.** S0.4.1 listed three exceptions, but the spike found two more
   failure shapes: a `null` key with no exception, and `IllegalBlockSizeException`. Any failure routes to "sign
   in again", and there is never a plaintext fallback.
   - **Definitive failures wipe the key and the blob:** a missing key, `KeyPermanentlyInvalidatedException`,
     `UnrecoverableKeyException`, `AEADBadTagException`, `IllegalBlockSizeException`, or an invalid blob version
     or length.
   - **Transient failures don't wipe:** `KeyStoreException`, `ProviderException` and `IOException` keep the key
     and the blob, so a Keystore that isn't ready yet just after boot doesn't destroy a valid key.
   - `CancellationException` is rethrown and never wipes.
   - The blob's version and IV-length header is authenticated as GCM AAD. The key has one fixed, namespaced alias,
     and key creation and saving run under a single mutex.
3. `allowBackup="false"` and `dataExtractionRules` stay, as defence in depth, even though Fire OS currently has
   no backup service to test them against.

## Consequences

- R-SEC-2 drops from 🟠 to 🟢 for the reference device. That's justified because every Keystore fault fails
  closed to re-login, so the impact is availability, not token exposure. The key reports itself as
  hardware-backed there, so the "software-only Keystore" limitation in S0.4.1 doesn't apply to the Insignia. It
  stays documented for other devices. **Not tested:** a Fire OS update, and any device beyond the one unit of
  each model tested.
- Clearing app data also removes the key, so "clear data" always means "sign in again", with nothing left
  behind.
- **Repository hygiene:** `spike/sp-3` is deleted before the repository goes public, along with the other
  spike branches (Q-REPO-1).
