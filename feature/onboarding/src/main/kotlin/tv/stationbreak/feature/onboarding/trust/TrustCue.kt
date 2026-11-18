// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.StanCue

/**
 * A certificate or cleartext question: it is the viewer's call, so Stan only stands by. The trust dialog itself has no
 * Stan (its fingerprint needs the full width); the screen under it uses this cue.
 */
internal val trustCue = StanCue(StanMood.Idle, R.string.stan_trust)
