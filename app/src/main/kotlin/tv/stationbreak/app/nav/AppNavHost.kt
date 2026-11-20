// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpRect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import tv.stationbreak.core.domain.lineup.GuideOpening
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.stan.StanOpening
import tv.stationbreak.feature.channels.ui.ChannelPropertiesDestination
import tv.stationbreak.feature.channels.ui.ChannelsDestination
import tv.stationbreak.feature.guide.ui.GuideDestination
import tv.stationbreak.feature.live.ui.LiveDestination
import tv.stationbreak.feature.onboarding.firstsync.FirstSyncCheerFrame
import tv.stationbreak.feature.onboarding.firstsync.FirstSyncCheerStill
import tv.stationbreak.feature.onboarding.firstsync.FirstSyncDestination
import tv.stationbreak.feature.onboarding.firstsync.rememberFirstSyncCheerFrame
import tv.stationbreak.feature.onboarding.launch.LaunchDestination
import tv.stationbreak.feature.onboarding.libraries.LibrariesDestination
import tv.stationbreak.feature.onboarding.libraries.SignedInDestination
import tv.stationbreak.feature.onboarding.server.ServerDestination
import tv.stationbreak.feature.onboarding.signin.SignInDestination
import tv.stationbreak.feature.onboarding.welcome.WelcomeDestination
import tv.stationbreak.feature.settings.ui.LibrariesDestination as SettingsLibrariesDestination

/**
 * The app's navigation graph, typed routes with the platform's back stack: Back goes up one level and, from the root,
 * leaves the app. The app opens on [LaunchRoute], which shows progress while the stored setup is checked and then
 * replaces itself with the screen the setup calls for, so onboarding never flashes and Back never returns to launch.
 */
@Composable
fun AppNavHost(coldLaunch: Boolean = false) = AppNavGraph(LaunchRoute, coldLaunch = coldLaunch)

/**
 * The graph, with a start destination and a [navController] a test can choose: starting on [SignInRoute] is what a
 * restored back stack looks like after process death, when the in-memory server hand-off is gone.
 *
 * [coldLaunch] is true for a new activity (the first start of an activity, not a restore of a saved one): Stan's
 * opening is then drawn once over the whole graph. It is opaque while launch routing works, so the first destination
 * is already composed and running beneath it, then hands over to that destination and dissolves away over it. It lives
 * here, above the host, so it outlasts the launch screen's replacement; no later visit to launch (a lost session)
 * shows it again.
 *
 * While the opening covers the graph, a hidden destination never receives input, and a screen reader does not read it
 * ([GraphCoverHost]): a fresh press of any key but Back and volume skips it; before routing has a destination Back
 * leaves the app, as the progress screen did, and after it Back skips.
 *
 * Setup's cheer covers the graph the same way when it hands over to live TV ([SetupHandOver], [CheerCover]): its still
 * ([FirstSyncCheerStill]) is drawn over live TV until live TV stops standing by (`onStandByOver`), a key, or
 * [SETUP_HAND_OVER_WAIT], so the first session goes from the cheer to a programme with no stand-by card between. A key
 * or Back under it lifts it and goes no further. Both covers are [GraphCover]s under one [GraphCoverHost].
 *
 * Launch's transitions: nothing crossfades from [LaunchRoute] (no exit, no enter), whatever started the app: a cold
 * launch under the opening, a warm one, or the re-entry after a lost session.
 */
@Composable
internal fun AppNavGraph(
    startDestination: Any,
    navController: NavHostController = rememberNavController(),
    coldLaunch: Boolean = false,
) {
    var phase by remember { mutableStateOf(if (coldLaunch) OpeningPhase.Playing else OpeningPhase.Gone) }
    var launchReady by remember { mutableStateOf(false) }
    var skip by remember { mutableStateOf(false) }
    val handOver = remember { SetupHandOver() }
    // The cheer's frame (its Stan renderer), held from the cheer's end only until the still lifts: before that the
    // first-sync entry owns it, and after it nothing does, so it is not kept for the rest of the session.
    var heldCheer by remember { mutableStateOf<FirstSyncCheerFrame?>(null) }
    LaunchedEffect(handOver.covering) { if (!handOver.covering) heldCheer = null }
    val activity = LocalActivity.current
    val opening = phase == OpeningPhase.Playing
    // Cosmetic and asynchronous: the navigation itself is already done when the still starts waiting.
    LaunchedEffect(handOver.covering) { if (handOver.covering) handOver.hold() }
    val covers =
        listOf(
            OpeningCover(
                active = opening,
                skip = { skip = true },
                // Before routing has a destination Back leaves the app, as the progress screen did; after it, it skips.
                back = { if (launchReady) skip = true else activity?.finish() },
            ),
            remember(handOver) { CheerCover(handOver) },
        )
    GraphCoverHost(
        covers,
        overlays = {
            heldCheer?.takeIf { handOver.covering }?.let { FirstSyncCheerStill(it) }
            if (phase != OpeningPhase.Gone) {
                StanOpening(
                    appReady = launchReady,
                    skip = skip,
                    onHandOver = { phase = OpeningPhase.Dissolving },
                    onDissolved = { phase = OpeningPhase.Gone },
                )
            }
        },
    ) {
        AppNavHostContent(
            navController,
            startDestination,
            remember(opening, handOver) {
                GraphParts(opening, handOver) { frame ->
                    heldCheer = frame
                    handOver.begin()
                }
            },
            onLaunchReady = { launchReady = true },
            onLaunchNeedsScreen = {
                launchReady = true
                skip = true
            },
        )
    }
}

private enum class OpeningPhase { Playing, Dissolving, Gone }

/**
 * Stan's opening as a cover: it holds the focus (the destination beneath may have none yet while launch routing works),
 * and owns its [skip] alone; Back is [back].
 */
private class OpeningCover(
    override val active: Boolean,
    private val skip: () -> Unit,
    private val back: () -> Unit,
) : GraphCover {
    override val holdsFocus: Boolean = true

    override fun onKey() = skip()

    override fun onBack() = back()
}

/**
 * What the graph above shares with its routes: whether the opening covers them, setup's hand-over to live TV, and
 * [holdCheer], which holds the cheer's frame over live TV and begins the hand-over.
 */
private class GraphParts(
    val openingCovers: Boolean,
    val handOver: SetupHandOver,
    val holdCheer: (FirstSyncCheerFrame) -> Unit,
)

/** True when this entry is live TV entered from setup's cheer: decided once, in the navigation call. */
private fun NavBackStackEntry.isLiveFromCheer(): Boolean {
    val live = destination.hasRoute<LiveRoute>()
    return live && toRoute<LiveRoute>().fromCheer
}

// The navigation library's default transition between screens, so nothing else changes.
private const val DEFAULT_FADE_MILLIS = 700

@Suppress("LongMethod") // The graph: one block for each route.
@Composable
private fun AppNavHostContent(
    navController: NavHostController,
    startDestination: Any,
    parts: GraphParts,
    onLaunchReady: () -> Unit,
    onLaunchNeedsScreen: () -> Unit,
) {
    val covered = parts.openingCovers
    val handOver = parts.handOver
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Nothing crossfades from launch: the opening is over it, and the progress screen is never to show through.
        // Nor from the cheer to live TV under its still: a fade there is unseen work on the frames that build live TV.
        // Decided by the route, not by whether the still is still up, so a key that lifts it brings no fade back.
        enterTransition = {
            val fromLaunch = initialState.destination.hasRoute<LaunchRoute>()
            val cut = fromLaunch || targetState.isLiveFromCheer()
            if (cut) EnterTransition.None else fadeIn(tween(DEFAULT_FADE_MILLIS))
        },
    ) {
        composable<LaunchRoute>(exitTransition = { ExitTransition.None }) {
            LaunchDestination(
                covered = covered,
                onReady = onLaunchReady,
                onNeedsScreen = onLaunchNeedsScreen,
                // Nothing stored: Welcome, with nothing beneath it, so Back exits.
                onWelcome = { navController.replaceAll(WelcomeRoute) },
                // A stored http address to confirm again: the server screen, outside the first-run flow (no markers).
                onConfirmAddress = { navController.replaceAll(ServerRoute()) },
                // A cold start mid-flow drops the step markers by design: they show only in a flow started at Welcome.
                onSignIn = { navController.replaceAll(SignInRoute()) },
                onLibraries = { navController.replaceAll(LibrariesRoute()) },
                onFirstSync = { navController.replaceAll(FirstSyncRoute) },
                onSetupComplete = { navController.replaceAll(LiveRoute()) },
                // The stored session and pin stay: the server screen shows the stored address, and only a Continue
                // (or a trust) on another address replaces them.
                onChangeServer = { navController.replaceAll(ServerRoute()) },
            )
        }
        composable<WelcomeRoute> {
            // Pushed, not replaced: Back from the server screen returns here. The step markers start with it.
            // Single top: a second Select can't stack a second server screen.
            WelcomeDestination(
                onGetStarted = { navController.navigate(ServerRoute(firstRun = true)) { launchSingleTop = true } },
                // Under the opening, Stan waits: he greets as it hands over, not unseen beneath it.
                covered = covered,
            )
        }
        composable<ServerRoute> { entry ->
            val firstRun = entry.toRoute<ServerRoute>().firstRun
            // Single top: a second Select can't stack a second sign-in screen.
            ServerDestination(
                onContinue = { navController.navigate(SignInRoute(firstRun)) { launchSingleTop = true } },
                showStep = firstRun,
            )
        }
        composable<SignInRoute> { entry ->
            val firstRun = entry.toRoute<SignInRoute>().firstRun
            // Launch routing opens sign-in on its own, with no server screen beneath it: Back is still "up", to the
            // server screen, where the address can be changed.
            BackHandler(enabled = navController.previousBackStackEntry == null) {
                navController.upToServerScreen(firstRun)
            }
            SignInDestination(
                // Back and Edit address are the same step: up to the server screen, whose address was kept.
                onEditAddress = { navController.upToServerScreen(firstRun) },
                onMissingServer = { navController.backToServerScreen(firstRun) },
                // The session waits in the in-memory sign-in hand-off. Everything before it is replaced, so the
                // stack is the same however the picker was reached, and Back from it goes to "Signed in as".
                onSignedIn = { navController.replaceAll(LibrariesRoute(firstRun)) },
                showStep = firstRun,
            )
        }
        composable<LibrariesRoute> { entry ->
            LibrariesDestination(
                showStep = entry.toRoute<LibrariesRoute>().firstRun,
                // Back is "Signed in as": it never signs out silently.
                onBack = { navController.navigate(SignedInRoute) { launchSingleTop = true } },
                // The first sync starts here (spec 06, owner answer 3). Nothing is left beneath its screen: Back exits,
                // the sync carries on in the background, and onboarding is never re-entered.
                onDone = { navController.replaceAll(FirstSyncRoute) },
                onSessionLost = { navController.replaceAll(LaunchRoute) },
                onMissingSession = { navController.replaceAll(LaunchRoute) },
            )
        }
        composable<SignedInRoute> {
            // Back here leaves the app (Q-UX-2): the picker is only beneath this screen so that Continue can return to
            // it, and Back must not pop to the picker, whose own Back opens this screen again.
            val activity = LocalActivity.current
            BackHandler { activity?.finish() }
            SignedInDestination(
                onContinue = { navController.popBackStack() },
                onSwitchUser = { navController.replaceAll(SignInRoute()) },
                onMissingSession = { navController.replaceAll(LaunchRoute) },
            )
        }
        composable<FirstSyncRoute>(
            exitTransition = {
                if (targetState.isLiveFromCheer()) ExitTransition.None else fadeOut(tween(DEFAULT_FADE_MILLIS))
            },
        ) {
            // The entry's own: dropped with it, unless the cheer hands it to the still.
            val cheerFrame = rememberFirstSyncCheerFrame()
            FirstSyncDestination(
                // No button: when the first sync ends with nothing to cheer, the app moves on to the live channel.
                onFinished = { navController.replaceAll(LiveRoute()) },
                // The server no longer accepts the sign-in: launch routing explains and asks the user to sign in again.
                onSessionLost = { navController.replaceAll(LaunchRoute) },
                // Stan has cheered us on the air (any key, Back included, skips that): live TV, under the cheer's
                // still until it has a picture. Both at once, in this callback: the still is composed above the host
                // in the same frame as live TV's first, heavy one, and is what that frame shows, frozen on the
                // cheer's last drawn frame. Nothing is deferred, so a recreation can never strand the viewer here.
                onCheered = {
                    parts.holdCheer(cheerFrame)
                    navController.replaceAll(LiveRoute(fromCheer = true))
                },
                cheerFrame = cheerFrame,
            )
        }
        composable<LiveRoute> {
            // The ready notice (#776) is drawn over everything live TV shows: the picture, the guide, Channels and the
            // properties sheet, which all render inside this route. It takes no key.
            ReadyNoticeHost {
                // The session ended while watching: the launch router decides where that leads, as for any lost
                // session.
                LiveDestination(
                    onSignedOut = { navController.replaceAll(LaunchRoute) },
                    // The guide is drawn by live TV, over its picture (spec 17); a feature never depends on another, so
                    // the graph hands it over. Live says where its picture is; the guide leaves that hole clear.
                    guide = { opening, pictureHole, onTune ->
                        // A sign-out from Settings, Libraries (#336): the server screen, to choose a server again.
                        GuideWithChannels(opening, pictureHole, onTune) { navController.afterSignOut() }
                    },
                    // The picture is up, or a card says why not: setup's cheer, if it is held over live TV, lifts.
                    onStandByOver = handOver::end,
                )
            }
        }
    }
}

/**
 * The guide, with channel management (spec 21), a channel's properties sheet (#631) and Settings, Libraries (spec 26)
 * as its slots, filled here because a feature never depends on another.
 */
@Composable
private fun GuideWithChannels(
    opening: GuideOpening,
    pictureHole: DpRect,
    onTune: (ChannelId) -> Unit,
    onSignedOut: () -> Unit,
) {
    GuideDestination(
        opening,
        pictureHole,
        onTune,
        channels = { channel, opened, hole, onDone -> ChannelsDestination(channel, opened, hole, onDone) },
        properties = { channel, current, opened, hole, onDone ->
            ChannelPropertiesDestination(channel, current, opened, hole, onDone)
        },
        libraries = { hole, onDone -> SettingsLibrariesDestination(hole, onDone, onSignedOut) },
    )
}

/**
 * Where a sign-out from Settings, Libraries goes (#336, #864): the server screen, alone on the stack, showing the
 * stored address. The viewer can change server or Continue to sign in on the same one; Back leaves the app. Not
 * launch routing, which would skip straight to sign-in for the stored server.
 */
internal fun NavController.afterSignOut() = replaceAll(ServerRoute())

/** Replaces the whole back stack with [route], so nothing is left to go Back to. */
private fun NavController.replaceAll(route: Any) {
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
}

/**
 * Up from sign-in to the server screen. When the server screen is on the stack it is popped back to; when sign-in is
 * the only screen (launch routing opened it), the stack is replaced by the server screen.
 */
private fun NavController.upToServerScreen(firstRun: Boolean) {
    if (previousBackStackEntry == null) replaceAll(ServerRoute(firstRun)) else popBackStack()
}

/**
 * After process death the sign-in screen can be restored without its server: go back to where it is typed. When the
 * server screen is on the stack it is popped back to; when it is not (the restored stack was only sign-in), the stack
 * is replaced by it, so sign-in is never left underneath and Back from the server screen leaves the app.
 */
private fun NavController.backToServerScreen(firstRun: Boolean) {
    if (!popBackStack<ServerRoute>(inclusive = false)) replaceAll(ServerRoute(firstRun))
}
