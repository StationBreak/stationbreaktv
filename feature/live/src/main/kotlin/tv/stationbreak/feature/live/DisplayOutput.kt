// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the TV can show and sound a picture (#423). While [present] is false (the TV is off or on another input, so
 * the stick's HDMI sink is gone) every tune would only time out after ten seconds, retry and report a play that nobody
 * sees. The live machine holds still instead: it tunes nothing, reports nothing and counts no viewing time, and when
 * the output returns it tunes once.
 */
interface DisplayOutput {
    val present: StateFlow<Boolean>

    /** An output that is always there: for hosts and tests that have no sink to lose. */
    object Present : DisplayOutput {
        override val present: StateFlow<Boolean> = MutableStateFlow(true)
    }
}
