// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.annotation.StringRes
import tv.stationbreak.core.ui.stan.StanMood

/**
 * What Stan does on a setup screen: his [mood], and his [line] in the bubble (null: no bubble). The line is decoration;
 * the screen's own text says everything the viewer needs (spec 2026-11-18, "Anti-goals"). Each screen's cue sits next
 * to its phase, with an exhaustive `when`, so a new phase does not compile until it has a cue.
 */
internal data class StanCue(
    val mood: StanMood,
    @param:StringRes val line: Int?,
)
