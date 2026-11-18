// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

/**
 * A boolean intent extra that skips Stan's opening, for the gate harness and the benchmarks
 * (`am start ... --ez stationbreak.skipOpening true`). Honoured by the debug and benchmark builds
 * (`BuildConfig.OPENING_SKIPPABLE`); a release build ignores it.
 */
const val EXTRA_SKIP_OPENING = "stationbreak.skipOpening"

/**
 * Whether a starting activity shows Stan's opening: whenever it is a new activity (nothing saved to restore), so any
 * fresh open of the app, not only a new process. A restore after process death, a configuration change (the manifest
 * keeps the activity) and a return from the background do not show it. A [skippable] build can turn it off with
 * [EXTRA_SKIP_OPENING].
 */
internal fun showsOpening(
    restored: Boolean,
    skippable: Boolean,
    skipExtra: Boolean,
): Boolean = !restored && !(skippable && skipExtra)
