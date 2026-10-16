// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import kotlinx.serialization.Serializable

/**
 * The library picker. A route with no arguments on purpose: the session it asks with is held in memory by the sign-in
 * hand-off, never in a route (route arguments are saved in the instance-state Bundle).
 */
@Serializable
data object LibrariesRoute

/** "Signed in as", with Continue and Switch user: where Back from the library picker goes. */
@Serializable
data object SignedInRoute

/**
 * The first-sync progress screen (spec 06, Unit 3): shown from the end of onboarding until the signed-in account's
 * first sync ends, then "Setup complete". Nothing is under it on the back stack, so Back leaves the app and the sync
 * carries on.
 */
@Serializable
data object FirstSyncRoute

/** The placeholder shown after setup. Nothing is under it on the back stack, so Back leaves the app. */
@Serializable
data object SetupCompleteRoute
