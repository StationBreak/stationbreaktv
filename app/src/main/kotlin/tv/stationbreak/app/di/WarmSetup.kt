// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.di

import tv.stationbreak.core.playback.WarmConfig
import tv.stationbreak.core.playback.WarmSwitches

/** The warm standby's tunables and the switches over them: one pair, shared by the player and the tune controller. */
internal class WarmSetup(
    val config: WarmConfig,
    val switches: WarmSwitches,
)

/**
 * The single place that decides the warm setup (spec 24): the default tunables for every build; the `log.tag.SBWARM`
 * and `SBWARMAUD` switches only in a debuggable or benchmark build, none in release (which never reads the setprops).
 */
internal fun warmSetupFor(
    debuggable: Boolean,
    benchmark: Boolean,
): WarmSetup =
    WarmSetup(
        WarmConfig(),
        if (debuggable || benchmark) WarmSwitches.logTags() else WarmSwitches.None,
    )
