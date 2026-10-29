// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import tv.stationbreak.core.domain.lineup.DaySnapshotEvent

private const val BYTES_PER_KB = 1024

/** The `Lineup` log line of a read or write of the days on disk (#480): numbers and a closed reason only. */
internal fun daySnapshotLine(event: DaySnapshotEvent): String =
    when (event) {
        is DaySnapshotEvent.Hit -> "DaySnapshot hit kb=${event.bytes / BYTES_PER_KB} ms=${event.ms}"
        is DaySnapshotEvent.Miss -> "DaySnapshot miss reason=${event.reason.name}"
        is DaySnapshotEvent.Saved -> "DaySnapshot saved kb=${event.bytes / BYTES_PER_KB} ms=${event.ms}"
        DaySnapshotEvent.SaveFailed -> "DaySnapshot saveFailed"
    }

/**
 * What the days on disk are keyed on for the app's own code: the version code and the time this install was made
 * (`PackageInfo.lastUpdateTime`), since the version code alone does not move between builds. Every install, a
 * reinstall of the same version included, gives a new one. Null when the install time is not known: then no days are
 * kept on disk, rather than days that new code might read.
 */
internal fun appBuildOf(
    versionCode: Int,
    lastUpdateTime: Long?,
): String? = lastUpdateTime?.takeIf { it > 0 }?.let { "$versionCode:$it" }
