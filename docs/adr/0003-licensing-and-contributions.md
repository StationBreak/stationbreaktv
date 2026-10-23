# ADR-0003: Licensing and contributions

- **Status:** Accepted
- **Date:** 2026-10-04
- **Decided by:** Phil Critchfield

## Context

The spec fixes GPL-3.0 for the whole project, with a free app and a one-time Pro unlock in the stores.
Planned platforms include Apple TV, possibly sharing the Kotlin scheduler via Kotlin Multiplatform
(Q-ARCH-1). The FSF's position is that Apple App Store terms conflict with the GPL; VLC was pulled from
the App Store in 2011 for this reason. Without a CLA, each contributor keeps their copyright, so any
permission needed later would require every contributor's consent. A permission that's in the
license *before* they contribute avoids that.

## Decision

1. **License: `GPL-3.0-or-later`.**
2. **Contributions: DCO** (`Signed-off-by` on every commit). There is no CLA.
3. **An app-store additional permission** (GPLv3 §7) is included **from the first commit**:
   [`docs/legal/app-store-exception.md`](../legal/app-store-exception.md). It applies to all recipients,
   including forks.
4. **File header:** `SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore`.
5. **Amazon Appstore:** submit with **no DRM**.

## Consequences

- Anyone, including forks, may ship StationBreak through app stores under the same terms. This is accepted.
- Downstream may remove the permission from their copy (§7). Such code can't be merged back without it.
- **Before the first outside contribution is accepted**, the exception text must be reviewed by a
  qualified party. This is a tracked task (S0.1.2).
- The owner can't relicense contributed code later without contributors' consent. This is accepted
  in favour of contributor trust.

## Update 2026-10-23: bundled third-party assets keep their own licence

The app now bundles two fonts, Archivo Black and Space Mono Bold, unmodified from github.com/google/fonts. Both are under
the SIL Open Font License 1.1 (OFL-1.1). They are aggregated with the app, not part of the GPL-3.0 work.

- **Decision (decided by the owner, 2026-10-23): third-party assets bundled in the app keep their own licence.** This
  covers these fonts and any later fonts, images or similar files. They are not covered by the project's GPL-3.0 licence
  or by the app-store additional permission. The OFL requires this: the fonts must stay under the OFL only, and each
  copy ships with its copyright notice and licence text.
- **Where things are.** The licence texts ship in the APK under `core/ui/src/main/assets/licenses/`
  (`OFL-ArchivoBlack.txt`, `OFL-SpaceMono.txt`). `core/ui/FONTS.md` records the source commit, the file hashes, the
  update steps, and the rule that fonts load only from the APK.
- **Consequence: a new bundled asset needs a hand check.** Licensee checks Maven dependencies only and does not see
  vendored assets. So a new bundled asset has its licence checked by hand at review (security review), and is recorded
  the same way: a provenance file next to it, and its licence text shipped in the APK.
- **Later.** If an in-app open-source licences screen is added, these assets appear on it.
