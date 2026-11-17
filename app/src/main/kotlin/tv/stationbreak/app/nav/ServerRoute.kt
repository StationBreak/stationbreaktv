// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import kotlinx.serialization.Serializable

/**
 * The server screen. [firstRun] is true when the flow started at the welcome screen: the three first-run screens then
 * show "Step n of 3". It is a nav argument, so it is saved with the back stack and survives process death.
 */
@Serializable
data class ServerRoute(
    val firstRun: Boolean = false,
)
