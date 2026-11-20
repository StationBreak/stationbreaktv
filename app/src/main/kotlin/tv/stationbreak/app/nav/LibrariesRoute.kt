// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import kotlinx.serialization.Serializable

/**
 * The library picker. The only argument is [firstRun] (see [ServerRoute]): the session it asks with is held in memory
 * by the sign-in hand-off, never in a route (route arguments are saved in the instance-state Bundle).
 */
@Serializable
data class LibrariesRoute(
    val firstRun: Boolean = false,
)

/** "Signed in as", with Continue and Switch user: where Back from the library picker goes. */
@Serializable
data object SignedInRoute

/**
 * The first-sync progress screen (spec 06, Unit 3): shown from the end of onboarding until the signed-in account's
 * first sync ends, then the live channel. Nothing is under it on the back stack, so Back leaves the app and the sync
 * carries on.
 */
@Serializable
data object FirstSyncRoute

/**
 * The live channel (spec 14): where the app opens once setup is finished. Nothing is under it on the back stack, so
 * Back leaves the app. [fromCheer] is true when it was entered straight from setup's cheer, which is then held still
 * over it ([SetupHandOver]). It is only a hint for the transitions, decided once in the navigation call, so they never
 * depend on a key pressed after it. After process death it is restored with the back stack and means nothing then,
 * which is harmless: no transition into the restored entry reads it.
 */
@Serializable
data class LiveRoute(
    val fromCheer: Boolean = false,
)
