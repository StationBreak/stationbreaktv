// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import tv.stationbreak.core.model.ItemId

/** Test tags for the library picker, the signed-in screen and the setup-complete screen, public for `app`'s tests. */
object LibrariesTags {
    const val SCREEN = "libraries:screen"
    const val LOADING = "libraries:loading"
    const val LIST = "libraries:list"
    const val DONE = "libraries:done"
    const val REASON = "libraries:reason"
    const val ERROR_PANEL = "libraries:errorPanel"
    const val RETRY = "libraries:retry"

    /** The row for the library whose ID is [id]. */
    fun row(id: String): String = "libraries:row:$id"

    internal fun row(id: ItemId): String = row(id.value)
}

/** Test tags for "Signed in as", reached by Back from the library picker. */
object SignedInTags {
    const val SCREEN = "signedIn:screen"
    const val HEADLINE = "signedIn:headline"
    const val CONTINUE = "signedIn:continue"
    const val SWITCH_USER = "signedIn:switchUser"
    const val SWITCHING = "signedIn:switching"
}
