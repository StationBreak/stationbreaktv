// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.signin

import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.server.ErrorAction
import tv.stationbreak.feature.onboarding.server.ErrorCopy
import tv.stationbreak.feature.onboarding.server.ErrorGroup

/** Sign-in's own copy: what isn't a `MediaServerError`, so it has no entry in the error-copy table. */
internal object SignInCopy {
    /** A blank username. No request was sent. */
    val blankUsername =
        ErrorCopy(
            R.string.signin_blank_username_message,
            R.string.signin_blank_username_details,
            ErrorGroup.Address,
            ErrorAction.EditAddress,
        )
}
