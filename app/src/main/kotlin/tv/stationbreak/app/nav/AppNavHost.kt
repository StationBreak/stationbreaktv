// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tv.stationbreak.feature.onboarding.launch.LaunchDestination
import tv.stationbreak.feature.onboarding.libraries.LibrariesDestination
import tv.stationbreak.feature.onboarding.libraries.SetupCompleteDestination
import tv.stationbreak.feature.onboarding.libraries.SignedInDestination
import tv.stationbreak.feature.onboarding.server.ServerDestination
import tv.stationbreak.feature.onboarding.signin.SignInDestination

/**
 * The app's navigation graph, typed routes with the platform's back stack: Back goes up one level and, from the root,
 * leaves the app. The app opens on [LaunchRoute], which shows progress while the stored setup is checked and then
 * replaces itself with the screen the setup calls for, so onboarding never flashes and Back never returns to launch.
 */
@Composable
fun AppNavHost() = AppNavGraph(LaunchRoute)

/**
 * The graph, with a start destination and a [navController] a test can choose: starting on [SignInRoute] is what a
 * restored back stack looks like after process death, when the in-memory server hand-off is gone.
 */
@Composable
internal fun AppNavGraph(
    startDestination: Any,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable<LaunchRoute> {
            LaunchDestination(
                onNoServer = { navController.replaceAll(ServerRoute) },
                onSignIn = { navController.replaceAll(SignInRoute) },
                onLibraries = { navController.replaceAll(LibrariesRoute) },
                onSetupComplete = { navController.replaceAll(SetupCompleteRoute) },
                // The stored session and pin stay: the server screen shows the stored address, and only a Continue
                // (or a trust) on another address replaces them.
                onChangeServer = { navController.replaceAll(ServerRoute) },
            )
        }
        composable<ServerRoute> {
            // Single top: a second Select can't stack a second sign-in screen.
            ServerDestination(onContinue = { navController.navigate(SignInRoute) { launchSingleTop = true } })
        }
        composable<SignInRoute> {
            // Launch routing opens sign-in on its own, with no server screen beneath it: Back is still "up", to the
            // server screen, where the address can be changed.
            BackHandler(enabled = navController.previousBackStackEntry == null) { navController.upToServerScreen() }
            SignInDestination(
                // Back and Edit address are the same step: up to the server screen, whose address was kept.
                onEditAddress = { navController.upToServerScreen() },
                onMissingServer = { navController.backToServerScreen() },
                // The session waits in the in-memory sign-in hand-off. Everything before it is replaced, so the
                // stack is the same however the picker was reached, and Back from it goes to "Signed in as".
                onSignedIn = { navController.replaceAll(LibrariesRoute) },
            )
        }
        composable<LibrariesRoute> {
            LibrariesDestination(
                // Back is "Signed in as": it never signs out silently.
                onBack = { navController.navigate(SignedInRoute) { launchSingleTop = true } },
                // Nothing is left beneath "Setup complete": Back exits, and onboarding is never re-entered.
                onDone = { navController.replaceAll(SetupCompleteRoute) },
                onSessionLost = { navController.replaceAll(LaunchRoute) },
                onMissingSession = { navController.replaceAll(LaunchRoute) },
            )
        }
        composable<SignedInRoute> {
            SignedInDestination(
                onContinue = { navController.popBackStack() },
                onSwitchUser = { navController.replaceAll(SignInRoute) },
                onMissingSession = { navController.replaceAll(LaunchRoute) },
            )
        }
        composable<SetupCompleteRoute> { SetupCompleteDestination() }
    }
}

/** Replaces the whole back stack with [route], so nothing is left to go Back to. */
private fun NavController.replaceAll(route: Any) {
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
}

/**
 * Up from sign-in to the server screen. When the server screen is on the stack it is popped back to; when sign-in is
 * the only screen (launch routing opened it), the stack is replaced by the server screen.
 */
private fun NavController.upToServerScreen() {
    if (previousBackStackEntry == null) replaceAll(ServerRoute) else popBackStack()
}

/**
 * After process death the sign-in screen can be restored without its server: go back to where it is typed. When the
 * server screen is on the stack it is popped back to; when it is not (the restored stack was only sign-in), the stack
 * is replaced by it, so sign-in is never left underneath and Back from the server screen leaves the app.
 */
private fun NavController.backToServerScreen() {
    if (!popBackStack(ServerRoute, inclusive = false)) replaceAll(ServerRoute)
}
