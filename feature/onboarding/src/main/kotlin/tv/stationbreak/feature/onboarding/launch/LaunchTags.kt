// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

/** Test tags for the launch screen, public so `app`'s instrumented tests can find its parts. */
object LaunchTags {
    const val SCREEN = "launch:screen"
    const val PROGRESS = "launch:progress"
    const val ERROR_PANEL = "launch:errorPanel"
    const val RETRY = "launch:retry"
    const val CHANGE_SERVER = "launch:changeServer"
}
