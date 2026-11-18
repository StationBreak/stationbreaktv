// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.firstsync

/** Test tags for the first-sync progress screen, public so `app`'s instrumented tests can find its parts. */
object FirstSyncTags {
    const val SCREEN = "firstSync:screen"
    const val HEADLINE = "firstSync:headline"
    const val SECOND_LINE = "firstSync:secondLine"
    const val BAR = "firstSync:bar"
    const val COUNT = "firstSync:count"
    const val ANNOUNCE = "firstSync:announce"
    const val ERROR_PANEL = "firstSync:errorPanel"
    const val RETRY = "firstSync:retry"

    /** The headline while the first lineup is built, after the library is in. */
    const val BUILDING = "firstSync:building"

    /** The headline of the moment Stan cheers us on the air, before live TV. */
    const val DONE = "firstSync:done"
}
