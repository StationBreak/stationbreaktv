// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

/** Test tags for the server screen, public so `app`'s instrumented tests can find its parts. */
object ServerTags {
    const val FIELD = "server:field"
    const val FIELD_INPUT = "server:fieldInput"
    const val FIELD_ERROR = "server:fieldError"
    const val TEST = "server:test"
    const val CANCEL = "server:cancel"
    const val TESTING = "server:testing"
    const val NOT_ENCRYPTED = "server:notEncrypted"
    const val CONTINUE = "server:continue"
    const val ERROR_PANEL = "server:errorPanel"
    const val RETRY = "server:retry"
    const val EDIT_ADDRESS = "server:editAddress"
    const val CLEARTEXT_CANCEL = "server:cleartextCancel"
    const val CLEARTEXT_CONFIRM = "server:cleartextConfirm"
}
