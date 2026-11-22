// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import tv.stationbreak.core.model.ItemId

/**
 * Test tags for the Settings screens (Libraries and the sign-out question), public so `app`'s instrumented tests can
 * find their parts.
 */
object SettingsTags {
    /** The whole Libraries screen. */
    const val LIBRARIES = "settings:libraries"
    const val TITLE = "settings:libraries:title"
    const val LIST = "settings:libraries:list"

    /** The line under the title while the server is asked. */
    const val LOADING = "settings:libraries:loading"

    /** The line under the title when the server could not be asked. */
    const val UNAVAILABLE = "settings:libraries:unavailable"

    /** "Signed in to …", under the Libraries title. */
    const val LIBRARIES_SIGNED_IN = "settings:libraries:signedIn"

    /** The line under the title after a change, or after an add was given up. */
    const val NOTICE = "settings:libraries:notice"

    /** The sign-out question over the guide's Settings sheet (#336): the whole of it, its title and its hint row. */
    const val SIGN_OUT = "settings:signOut"
    const val SIGN_OUT_TITLE = "settings:signOut:title"
    const val SIGN_OUT_HINT = "settings:signOut:hint"

    /** "Signed in to …", under the question's title. */
    const val SIGNED_IN = "settings:signOut:signedIn"

    /** The note that the TV will need a new sign-in, or, once it ran, that the server could not be told. */
    const val SIGN_OUT_NOTE = "settings:signOut:note"

    /** The line saying what happened after a choice (signing out, not revoked, a failure): the one live region. */
    const val SIGN_OUT_STATUS = "settings:signOut:status"

    /** The two answers together: after a move, a live region saying the one under the D-pad. */
    const val SIGN_OUT_ANSWERS = "settings:signOut:answers"

    /** The two answers: Stay signed in (the focus starts here) and Sign out. */
    const val SIGN_OUT_STAY = "settings:signOut:stay"
    const val SIGN_OUT_CONFIRM = "settings:signOut:confirm"

    /** The Pro screen (#99): the whole of it, its title and its hint row. */
    const val PRO = "settings:pro"
    const val PRO_TITLE = "settings:pro:title"
    const val PRO_HINT = "settings:pro:hint"

    /** What Pro adds, the store's price (or that it has none), and "you have Pro" once it is owned. */
    const val PRO_ADDS = "settings:pro:adds"
    const val PRO_PRICE = "settings:pro:price"
    const val PRO_OWNED = "settings:pro:owned"

    /** The line saying what happened after Buy or Restore: the one live region. */
    const val PRO_STATUS = "settings:pro:status"
    const val PRO_BUY = "settings:pro:buy"
    const val PRO_RESTORE = "settings:pro:restore"

    /** The GitHub build's Support StationBreak page: its words, the address as text and the note under it. */
    const val SUPPORT_BODY = "settings:support:body"
    const val SUPPORT_URL = "settings:support:url"
    const val SUPPORT_NOTE = "settings:support:note"

    fun row(id: ItemId) = "settings:libraries:row:${id.value}"

    /** The "Adding…" line under a row being added. */
    fun adding(id: ItemId) = "settings:libraries:adding:${id.value}"

    /** The "Saving…" line under a row ticked and not stored yet. */
    fun saving(id: ItemId) = "settings:libraries:saving:${id.value}"

    /** The "Removing…" line under a row unticked and not stored yet. */
    fun removing(id: ItemId) = "settings:libraries:removing:${id.value}"
}
