// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.welcome

import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.StanCue

/** Stan greets the viewer: a new line, so he talks, then stands by. */
internal val welcomeCue = StanCue(StanMood.Idle, R.string.stan_welcome)
