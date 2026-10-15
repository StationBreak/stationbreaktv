// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import kotlinx.serialization.Serializable

/**
 * Where the app opens (spec 05, Unit 4): a progress screen while launch routing reads the stored setup, then it moves
 * on to the screen the setup calls for. Nothing is ever left beneath it, so Back never returns to it.
 */
@Serializable
data object LaunchRoute
