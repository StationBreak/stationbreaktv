// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tv.stationbreak.feature.onboarding.server.ServerDestination
import tv.stationbreak.feature.onboarding.signin.SignInDestination
import tv.stationbreak.feature.onboarding.signin.SignInResult

/**
 * The app's navigation graph, typed routes with the platform's back stack: Back goes up one level and, from the root,
 * leaves the app. The server screen and sign-in exist so far; the library picker and launch routing follow in spec 05.
 *
 * [onSignedIn] is where a finished sign-in goes next. The library picker (task 4.0) does not exist yet, so the default
 * does nothing; a test passes its own to pin what is handed over.
 */
@Composable
fun AppNavHost(onSignedIn: (SignInResult) -> Unit = ::continueToLibraries) = AppNavGraph(onSignedIn, ServerRoute)

/**
 * The graph, with a start destination and a [navController] a test can choose: starting on [SignInRoute] is what a
 * restored back stack looks like after process death, when the in-memory server hand-off is gone.
 */
@Composable
internal fun AppNavGraph(
    onSignedIn: (SignInResult) -> Unit,
    startDestination: Any,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable<ServerRoute> {
            // Single top: a second Select can't stack a second sign-in screen.
            ServerDestination(onContinue = { navController.navigate(SignInRoute) { launchSingleTop = true } })
        }
        composable<SignInRoute> {
            SignInDestination(
                // Back and Edit address are the same step: up to the server screen, whose address was kept.
                onEditAddress = { navController.popBackStack() },
                onMissingServer = { navController.backToServerScreen() },
                onSignedIn = onSignedIn,
            )
        }
    }
}

/**
 * After process death the sign-in screen can be restored without its server: go back to where it is typed. When the
 * server screen is on the stack it is popped back to; when it is not (the restored stack was only sign-in), the stack
 * is replaced by it, so sign-in is never left underneath and Back from the server screen leaves the app.
 */
private fun NavController.backToServerScreen() {
    if (!popBackStack(ServerRoute, inclusive = false)) {
        navigate(ServerRoute) { popUpTo(graph.id) { inclusive = true } }
    }
}

/**
 * A finished sign-in. The library picker (spec 05, task 4.0) will take `result.session` and its `persisted` flag from
 * here; it does not exist yet, so for now there is nowhere to go.
 */
@Suppress("UNUSED_PARAMETER", "EmptyFunctionBlock")
private fun continueToLibraries(result: SignInResult) = Unit
