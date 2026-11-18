// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import tv.stationbreak.core.ui.stan.StanMood
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.StanCue

/** Stan's cue on "Signed in as", reached by Back from the picker (spec 2026-11-18, mapping table). */
internal val signedInCue = StanCue(StanMood.Idle, R.string.stan_signed_in)
