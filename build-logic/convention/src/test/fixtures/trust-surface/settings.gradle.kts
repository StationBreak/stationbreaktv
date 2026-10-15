// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

// TestKit fixture: the trust API is allowed in :data:jellyfin's trust package and nowhere else.
rootProject.name = "trust-surface"

include(":data:jellyfin", ":feature:bad")
