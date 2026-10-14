// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import tv.stationbreak.feature.onboarding.server.ConnectedServer
import tv.stationbreak.feature.onboarding.server.ServerDestination

/**
 * The app's navigation graph, typed routes with the platform's back stack: Back goes up one level and, from the root,
 * leaves the app. Only the server screen exists so far; sign-in, libraries and launch routing follow in spec 05.
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ServerRoute) {
        composable<ServerRoute> {
            ServerDestination(onContinue = ::continueToSignIn)
        }
    }
}

/**
 * Continue after a successful Test. The sign-in screen does not exist yet (spec 05, task 2.0 adds it and this edge),
 * so for now there is nowhere to go.
 */
@Suppress("UNUSED_PARAMETER", "EmptyFunctionBlock")
private fun continueToSignIn(server: ConnectedServer) = Unit
