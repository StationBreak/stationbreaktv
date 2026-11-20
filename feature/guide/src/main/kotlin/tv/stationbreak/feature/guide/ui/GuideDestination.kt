// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpRect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import tv.stationbreak.core.domain.lineup.GuideOpening
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.ChannelChangeStyle
import tv.stationbreak.core.ui.LocalGuideColors
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ProvideGuideColors
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.core.ui.mark.LocalMarkCache
import tv.stationbreak.core.ui.mark.MarkCaches
import tv.stationbreak.core.ui.rememberChannelChangeStore
import tv.stationbreak.core.ui.rememberScreenFitStore
import tv.stationbreak.core.ui.rememberVolumeLevellingStore
import tv.stationbreak.core.ui.remoteKeyToAct
import tv.stationbreak.feature.guide.GuideDirection
import tv.stationbreak.feature.guide.GuideEvent
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.GuideViewModel

/**
 * The program guide (spec 17), drawn by live TV over its picture while the guide is open: live owns whether it shows,
 * this owns what it shows. Each time it appears a guide session opens with the focus on [current]'s program on air
 * now, and it closes when the guide goes. [onTune] is called when the viewer picks the program on air on a channel;
 * live TV closes the guide then.
 *
 * [pictureHole] is where live TV laid its picture while the guide is open, in screen dp: the guide leaves that
 * rectangle clear and lays its panel and grid out around it. [openedAtNanos] is when the key that opened the guide
 * arrived (`System.nanoTime`), for the guide's open-time measurement.
 *
 * Keys: the guide moves its own focus (model-driven, not Compose focus search), with the D-pad and Select; Back walks
 * the focus outwards to the menu sheet (which the Menu key opens and closes from anywhere), and from the guide with no
 * sheet to go to is left to the activity, which leaves live TV. Select on a program tunes its
 * channel, and the details panel carries what an info overlay once did. Select on the Channels
 * button above the grid shows [channels] over the guide (channel management, which `app` hands in); the guide's own
 * keys and Back stand down while it shows. Left from a row's first cell moves the focus to its channel; Select there
 * shows [properties] (the channel's properties sheet, #631) over the guide, which stands down the same way. Select on
 * Libraries in the Settings sheet shows [libraries] the same way; Back from it returns to the sheet, and Back from the
 * sheet closes it. Select on Update library in the sheet starts an update, and the sheet says how it goes. Select on
 * Sign out shows [signOut] (the question whether to sign out) over the sheet; staying returns to the sheet.
 */
@Suppress("LongParameterList") // The guide's own four, its four slots, and the modifier.
@Composable
fun GuideDestination(
    opening: GuideOpening,
    pictureHole: DpRect,
    onTune: (ChannelId) -> Unit,
    channels: ChannelsSlot,
    properties: PropertiesSlot,
    libraries: LibrariesSlot,
    signOut: SignOutSlot,
    modifier: Modifier = Modifier,
) {
    val viewModel: GuideViewModel = hiltViewModel()
    val themes = rememberGuideThemeStore()
    val theme by themes.theme.collectAsStateWithLifecycle()
    // The screen fit is the app's one choice, shared with live TV's picture: the guide only sets it.
    val fits = rememberScreenFitStore()
    // Volume levelling (#498) is the TV's own choice, read by the player; the guide only sets it.
    val levellings = rememberVolumeLevellingStore()
    val levelling by levellings.enabled.collectAsStateWithLifecycle()
    // What live TV shows while the channel changes: the guide only sets it.
    val changes = rememberChannelChangeStore()
    val channelChange by changes.style.collectAsStateWithLifecycle()
    ProvideGuideColors(theme.colors) {
        CompositionLocalProvider(LocalMarkCache provides MarkCaches.get(LocalContext.current)) {
            GuideRoot(
                viewModel,
                opening,
                pictureHole,
                onTune,
                channels,
                modifier,
                viewModel.measure,
                theme,
                themes::choose,
                fits::choose,
                properties,
                libraries,
                signOut,
                levelling = levelling,
                onLevelling = { enabled, from -> levellings.choose(enabled, from) },
                channelChange = channelChange,
                onChannelChange = { style, from -> changes.choose(style, from) },
                // The stores as they stand at the key, not as the last frame saw them: two presses inside one frame
                // both step, each from the value the one before chose.
                valuesNow = {
                    SettingsValues(
                        themes.theme.value,
                        fits.fit.value,
                        changes.style.value,
                        levellings.enabled.value,
                    )
                },
            )
        }
    }
}

/**
 * Channel management as the guide draws it, over itself: what is playing, the picture's hole, and a way to finish.
 * [onDone] is called once per showing, with the channel to tune or null for back to the guide; the guide stops drawing
 * the slot then. A slot is a composable of the app's graph because a feature never depends on another.
 */
typealias ChannelsSlot =
    @Composable (
        current: ChannelId?,
        openedAtNanos: Long,
        pictureHole: DpRect,
        onDone: (tune: ChannelId?) -> Unit,
    ) -> Unit

/**
 * A channel's properties sheet (#631) as the guide draws it, over itself: the [channel], what is playing
 * ([current]), the picture's hole, and a way to finish. [onDone] is called once per showing, with the channel to tune
 * (Watch on the sheet) or null for Back; the guide stops drawing the slot then, and returns to the channel column. A
 * slot, like [ChannelsSlot], because a feature never depends on another.
 */
typealias PropertiesSlot =
    @Composable (
        channel: ChannelId,
        current: ChannelId?,
        openedAtNanos: Long,
        pictureHole: DpRect,
        onDone: (tune: ChannelId?) -> Unit,
    ) -> Unit

/**
 * Settings, Libraries (spec 26) as the guide draws it, over itself: the picture's hole and a way to finish. [onDone] is
 * called once per showing, when the viewer is done (Back); the guide stops drawing the slot then and shows its Settings
 * panel again, on Libraries. A slot for the same reason as [ChannelsSlot].
 */
typealias LibrariesSlot = @Composable (pictureHole: DpRect, onDone: () -> Unit) -> Unit

/**
 * The question whether to sign out of this TV (#336), as the guide draws it over its Settings sheet. [onDone] is
 * called once, when the viewer stays signed in; the guide stops drawing the slot then and shows the sheet again, on
 * Sign out. A sign-out leaves the guide by the app's own way, which the slot is given. A slot for the same reason as
 * [ChannelsSlot]: the sign-out is another feature's.
 */
typealias SignOutSlot = @Composable (onDone: () -> Unit) -> Unit

/**
 * [GuideDestination] without Hilt, so a test can bring its own ViewModel.
 *
 * [openedAtNanos] has no default on purpose: it keys the session, and a default expression such as
 * `System.nanoTime()` is evaluated again when this recomposes (once it has read state, Compose re-runs its defaults),
 * which would end the session and open a new one on an ordinary recomposition, such as channel management closing.
 */
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // Its own parameters; a test leaves four.
@Composable
internal fun GuideRoot(
    viewModel: GuideViewModel,
    opening: GuideOpening,
    pictureHole: DpRect,
    onTune: (ChannelId) -> Unit,
    channels: ChannelsSlot,
    modifier: Modifier = Modifier,
    measure: Boolean = false,
    theme: GuideTheme = GuideTheme.Dark,
    onTheme: (GuideTheme) -> Unit = {},
    onFit: (ScreenFit) -> Unit = {},
    properties: PropertiesSlot = NoProperties,
    libraries: LibrariesSlot = NO_LIBRARIES_SLOT,
    signOut: SignOutSlot = NoSignOut,
    levelling: Boolean = true,
    onLevelling: (Boolean, String) -> Unit = { _, _ -> },
    channelChange: ChannelChangeStyle = ChannelChangeStyle.Stan,
    onChannelChange: (ChannelChangeStyle, String) -> Unit = { _, _ -> },
    valuesNow: (() -> SettingsValues)? = null,
) {
    val current = opening.current
    val openedAtNanos = opening.openedAtNanos
    // The session's measurement line (Task 9) is written when the session ends, and only when [measure] says this build
    // writes it. The rows and the ticks' lag are read as they stand then.
    val view = LocalView.current
    // Started with the session, not in `remember`: a composition that is abandoned before it applies must not leak the
    // frame thread and its listener.
    var measurement by remember(viewModel) { mutableStateOf<GuideMeasurement?>(null) }
    // One session per showing, and per opening: live TV can close and reopen the guide between two frames, which leaves
    // this on screen with a new [openedAtNanos]. The guide's own state starts Closed and may say so for a moment while
    // the open runs: it never closes the guide, which is live TV's to do.
    DisposableEffect(viewModel, openedAtNanos) {
        val started = GuideMeasurement.start(measure, view, openedAtNanos)
        measurement = started
        viewModel.open(opening.current, opening.playing)
        onDispose {
            try {
                val rows = (viewModel.snapshot.value.state as? GuideState.Ready)?.model?.rows?.size ?: 0
                started?.finish(
                    viewModel.windowMs,
                    rows,
                    viewModel.extensionsAsked,
                    viewModel.extensionsApplied,
                    viewModel.tickLagMaxMs,
                    viewModel.tickLateMaxMs,
                )
            } finally {
                viewModel.close()
            }
        }
    }
    // The first grid frame: openMs lands on the frame that drew the grid or one frame later (about 16 ms).
    LaunchedEffect(viewModel, openedAtNanos) {
        viewModel.snapshot.first { it.state is GuideState.Ready }
        withFrameNanos { }
        withFrameNanos { }
        measurement?.gridShown()
    }
    // A Tune is acted on as it is received, never right after Select: the guide may refuse a Select (focus gone), and
    // a session that ends drops its queued events.
    val tune by rememberUpdatedState(onTune)
    // When the viewer opened channel management (a fresh value per opening, for the slot), or 0 while it is not
    // showing. It starts over with each showing of the guide.
    var managingSince by remember(viewModel, openedAtNanos) { mutableLongStateOf(NOT_MANAGING) }
    val managing = managingSince != NOT_MANAGING
    // The channel whose properties sheet shows over the guide (#631), and the showing's own value for the slot.
    var propertiesOf by remember(viewModel, openedAtNanos) { mutableStateOf<ChannelId?>(null) }
    var propertiesSince by remember(viewModel, openedAtNanos) { mutableLongStateOf(NOT_MANAGING) }
    // The Settings sheet while it is open (null when closed), with the Libraries screen or the sign-out question shown
    // over the guide from it.
    var settings by remember(viewModel, openedAtNanos) { mutableStateOf<SettingsPanel?>(null) }
    val librariesShown = settings?.librariesShown == true
    val signOutShown = settings?.signOutShown == true
    val cover = GuideCover(managing, propertiesOf != null, librariesShown, signOutShown)
    // The Libraries screen hides the guide as channel management does, so the clock and the lineup reloads stop
    // under it (a reload no one can see is wasted work on a slow TV) and start again when it closes.
    DisposableEffect(viewModel, librariesShown) {
        if (librariesShown) viewModel.librariesOpened()
        onDispose { if (librariesShown) viewModel.librariesClosed() }
    }
    val covered = cover.covered
    val hidden = cover.hidden
    val backs = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val leave by rememberUpdatedState { backs?.onBackPressed() }
    var themeChoice by remember { mutableStateOf(theme) }
    LaunchedEffect(theme) { themeChoice = theme }
    val chooseTheme = rememberUpdatedState(onTheme)
    val chooseFit = rememberUpdatedState(onFit)
    val chooseLevelling = rememberUpdatedState(onLevelling)
    val chooseChannelChange = rememberUpdatedState(onChannelChange)
    // What the Settings sheet's choice rows show; a key there steps on from the stores' values as they stand
    // ([valuesNow]), or from these where there are no stores (a test's host).
    val settingsValues = SettingsValues(themeChoice, LocalScreenFit.current, channelChange, levelling)
    val shownValues by rememberUpdatedState(settingsValues)
    val keyValues by rememberUpdatedState(valuesNow ?: { shownValues })
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            handleGuideEvent(
                event,
                tune = { tune(it) },
                leave = { leave() },
                openChannels = {
                    viewModel.channelsOpened()
                    managingSince = System.nanoTime().coerceAtLeast(1L)
                },
                openSettings = { settings = SettingsPanel() },
                openProperties = {
                    // Only one screen is over the guide at a time: an open while one shows is ignored. All are read
                    // as they stand now, not as this composition saw them, so a second OK in a frame is ignored too.
                    val open = settings
                    val now =
                        GuideCover(
                            managingSince != NOT_MANAGING,
                            propertiesOf != null,
                            open?.librariesShown == true,
                            open?.signOutShown == true,
                        )
                    if (!now.covered) {
                        viewModel.channelsOpened()
                        propertiesSince = System.nanoTime().coerceAtLeast(1L)
                        propertiesOf = it
                    }
                },
            )
        }
    }
    // The first visit's note stands in the panel until the first key press, which dismisses it and then does its job.
    var firstVisitNote by remember(viewModel, openedAtNanos) { mutableStateOf(opening.firstVisit) }
    val syncNow by viewModel.syncNowState.collectAsStateWithLifecycle()
    // Back walks the focus outwards (to what is playing, then to the menu sheet, which it closes), and from the guide
    // with nothing to go to it is the activity's and leaves live TV. While a screen covers the guide (channel
    // management, the properties sheet, Settings, Libraries), it is that screen's.
    val keys = remember { FocusRequester() }
    // The guide takes the key focus when it shows, and takes it back when the screen that covered it has gone.
    LaunchedEffect(keys, covered) { if (!covered) keys.requestFocus() }
    val back: (() -> Unit)? =
        remember(viewModel, backs) { backs?.let { d -> { if (!viewModel.back()) d.onBackPressed() } } }
    val move = remember(viewModel) { viewModel.tracedMove() }
    val menu: () -> Unit = remember(viewModel) { { viewModel.toggleMenu() } }
    Box(modifier.fillMaxSize()) {
        GuideScreen(
            snapshot = viewModel.snapshot,
            panel = viewModel.panel,
            channelBox =
                remember(viewModel) { ChannelBoxReaders(viewModel.channelHoldings, viewModel.channelIdentities) },
            current = current,
            pictureHole = pictureHole,
            settings = settings,
            settingsValues = settingsValues,
            syncNow = syncNow,
            firstVisitNote = firstVisitNote,
            onNarrowBreakBelow = { viewModel.narrowBreakBelowMs = it },
            modifier =
                Modifier
                    // Kept composed (its state and the key focus stay) but not drawn under the screen that covers it.
                    .graphicsLayer { alpha = if (hidden) 0f else 1f }
                    .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
                    .focusRequester(keys)
                    .onKeyEvent {
                        if (firstVisitNote && dismissesFirstVisitNote(it.type, it.nativeKeyEvent.repeatCount)) {
                            firstVisitNote = false
                        }
                        guideRootKey(
                            it,
                            covered = covered,
                            settings = settings,
                            settingsValues = keyValues(),
                            onSettings = { step ->
                                step.theme?.let { next ->
                                    themeChoice = next
                                    chooseTheme.value(next)
                                }
                                step.fit?.let { next -> chooseFit.value(next) }
                                step.levelling?.let { next ->
                                    chooseLevelling.value(next, step.levellingFrom.orEmpty())
                                }
                                step.channelChange?.let { next ->
                                    chooseChannelChange.value(next, step.channelChangeFrom.orEmpty())
                                }
                                if (step.syncNow) viewModel.syncNow()
                                settings = step.panel
                            },
                            grid = { event -> guideKey(event, move, viewModel::select, back, menu) },
                        )
                    }.focusable(),
        )
        if (managing) {
            channels(current, managingSince, pictureHole) { tuneTo ->
                // The key focus comes back before the slot goes, so a key in between is not lost; the slot ignores keys
                // after onDone.
                runCatching { keys.requestFocus() }
                managingSince = NOT_MANAGING
                viewModel.overlayDone(tuneTo, viewModel::channelsClosed, tune)
            }
        }
        propertiesOf?.let { channel ->
            properties(channel, current, propertiesSince, pictureHole) { tuneTo ->
                runCatching { keys.requestFocus() }
                propertiesOf = null
                viewModel.overlayDone(tuneTo, viewModel::propertiesClosed, tune)
            }
        }
        if (librariesShown) {
            LibrariesBackdrop(pictureHole) {
                libraries(pictureHole) {
                    // As for channel management: the key focus comes back first, then the slot goes, and the panel is
                    // left open on Libraries.
                    runCatching { keys.requestFocus() }
                    settings = settings?.librariesDone()
                }
            }
        }
        if (signOutShown) {
            signOut {
                runCatching { keys.requestFocus() }
                settings = settings?.signOutDone()
            }
        }
    }
}

private const val NOT_MANAGING = 0L

/**
 * What the Libraries screen stands on while the guide is hidden under it: the guide's own ground for the theme (its
 * page and gradient), not the window's black. The [pictureHole] stays clear, as the guide leaves it, so the live
 * picture behind shows in both themes.
 */
@Composable
internal fun LibrariesBackdrop(
    pictureHole: DpRect,
    content: @Composable () -> Unit,
) {
    val colors = LocalGuideColors.current
    Box(Modifier.fillMaxSize().drawBehind { ground(pictureHole, colors) }) { content() }
}

/**
 * What covers the guide: channel management ([managing]), a channel's properties sheet ([properties]), or Settings,
 * Libraries or the sign-out question ([settings], from the Settings sheet). Any of them has the keys, and the guide
 * stays composed under it ([covered]); channel management and Settings, Libraries hide the guide ([hidden]), while the
 * properties sheet and the sign-out question are drawn over it.
 */
private class GuideCover(
    managing: Boolean,
    properties: Boolean,
    libraries: Boolean,
    signOut: Boolean,
) {
    val covered = managing || properties || libraries || signOut
    val hidden = managing || libraries
}

/** A Libraries slot with nothing to show: it finishes at once, so a guide given none is never left covered. */
private val NO_LIBRARIES_SLOT: LibrariesSlot = { _, onDone -> LaunchedEffect(Unit) { onDone() } }

/** A sign-out slot with nothing to ask: it finishes at once, so a guide given none is never left covered. */
private val NoSignOut: SignOutSlot = { onDone -> LaunchedEffect(Unit) { onDone() } }

/** What a one-off guide event asks the screen to do. Exit takes the way Back takes from the guide. */
@Suppress("LongParameterList") // One callback for each thing an event asks.
private fun handleGuideEvent(
    event: GuideEvent,
    tune: (ChannelId) -> Unit,
    leave: () -> Unit,
    openChannels: () -> Unit,
    openSettings: () -> Unit,
    openProperties: (ChannelId) -> Unit,
) {
    when (event) {
        is GuideEvent.Tune -> tune(event.channel)
        GuideEvent.Exit -> leave()
        GuideEvent.OpenChannels -> openChannels()
        GuideEvent.OpenSettings -> openSettings()
        is GuideEvent.OpenChannelProperties -> openProperties(event.channel)
    }
}

/**
 * Keys for the guide: a slot has them while it shows (every key is kept here, as the slot may not have the focus yet),
 * the Settings sheet has them while it is open, stepping on from [settingsValues].
 */
@Suppress("LongParameterList") // The event, what has the keys, and where each kind of key goes.
private fun guideRootKey(
    event: KeyEvent,
    covered: Boolean,
    settings: SettingsPanel?,
    settingsValues: SettingsValues,
    onSettings: (SettingsStep) -> Unit,
    grid: (KeyEvent) -> Boolean,
): Boolean =
    when {
        covered -> {
            true
        }

        settings != null -> {
            settingsStep(settings, settingsValues, event.key, event.type, event.nativeKeyEvent.repeatCount)
                ?.also(onSettings) != null
        }

        else -> {
            grid(event)
        }
    }

/**
 * The guide's keys, all consumed so the focus never leaves the grid: the D-pad moves the focus (auto-repeat included,
 * so holding a direction scrolls; a held Down pauses at the last row before it wraps, and a held Up at the top row
 * before it wraps) and Select acts on the focused cell once per press. Their releases are consumed too.
 *
 * The Menu key is handed to [menu] on its down (opening the menu sheet from anywhere, closing it when it is open).
 * Back is handed to [back] on its release, as the activity itself would: the focus goes to what is playing, then to
 * the menu sheet, which Back closes; from the guide with no sheet over it the activity leaves live TV.
 * Left unconsumed, Compose would spend it on moving the focus out of the guide to live TV's root ("exit"), and the
 * viewer would have to press Back twice. The media keys are not the guide's.
 */
internal fun guideKey(
    event: KeyEvent,
    move: (GuideDirection, Boolean, Long) -> Unit,
    select: () -> Unit,
    back: (() -> Unit)? = null,
    menu: (() -> Unit)? = null,
): Boolean {
    val repeatCount = event.nativeKeyEvent.repeatCount
    val handled = event.key.let { if (it == Key.Back) back != null else keyIsTheGuides(it, menu != null) }
    when {
        !handled -> {
            Unit
        }

        // The Menu key opens the menu sheet, or closes it: once per press, on the down. It is the guide's own.
        event.key == Key.Menu -> {
            if (event.type == KeyEventType.KeyDown && repeatCount == 0) menu?.invoke()
        }

        // The rest is the policy every TV screen shares (#823). Back acts on any release, even one whose down the
        // guide never saw: the Back that opened the guide never reaches here (live's root holds that press to its
        // release), so a release here is always a later press, and a fast double Back can land here as a release
        // alone, its down having gone to live's root in the frame before the guide had the focus.
        else -> {
            when (remoteKeyToAct(event.key, event.type, repeatCount)) {
                RemoteKey.Back -> back?.invoke()
                RemoteKey.Select -> select()
                null -> Unit
                else -> directionOf(event.key)?.let { move(it, repeatCount > 0, event.nativeKeyEvent.eventTime) }
            }
        }
    }
    return handled
}

// Whether a key that is not Back is the guide's: the D-pad, Select, and the Menu key when there is a sheet to toggle.
private fun keyIsTheGuides(
    key: Key,
    hasMenu: Boolean,
): Boolean = if (key == Key.Menu) hasMenu else directionOf(key) != null || key in SELECT_KEYS

internal val SELECT_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

internal fun directionOf(key: Key): GuideDirection? =
    when (key) {
        Key.DirectionUp -> GuideDirection.Up
        Key.DirectionDown -> GuideDirection.Down
        Key.DirectionLeft -> GuideDirection.Left
        Key.DirectionRight -> GuideDirection.Right
        else -> null
    }

// A move, inside its trace section ("guide move"): the step and its publish.
private fun GuideViewModel.tracedMove(): (GuideDirection, Boolean, Long) -> Unit =
    { direction, repeat, atMs -> traced(TRACE_MOVE) { move(direction, repeat, atMs) } }

/** A properties slot with nothing to show: it finishes at once, so a guide given none is never left covered. */
private val NoProperties: PropertiesSlot = { _, _, _, _, onDone -> LaunchedEffect(Unit) { onDone(null) } }

// A screen over the guide finished: back to the guide ([closed]) or, with a channel to tune, the guide goes with it.
private fun GuideViewModel.overlayDone(
    tuneTo: ChannelId?,
    closed: () -> Unit,
    tune: (ChannelId) -> Unit,
) {
    if (tuneTo == null) {
        closed()
    } else {
        channelsEnded()
        tune(tuneTo)
    }
}
