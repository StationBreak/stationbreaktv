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
