// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore

// TestKit fixture: core:scheduler may not depend on core:engine (MODULES.md).
rootProject.name = "bad-edge"

include(":core:model", ":core:rules", ":core:engine", ":core:scheduler")
