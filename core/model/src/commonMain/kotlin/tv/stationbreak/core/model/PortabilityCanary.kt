// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import java.io.File

// Canary (never merged): java.* in commonMain must fail the linuxX64 compile.
fun canaryPath(name: String): String = File(name).path
