// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import kotlinx.serialization.Serializable

/**
 * The sign-in screen. A route with no arguments on purpose: the server address and the cleartext acknowledgement must
 * not travel in a route, because route arguments are saved in the instance-state Bundle (Angua). The server screen
 * hands them over in memory instead (`ServerHandOff`).
 */
@Serializable
data object SignInRoute
