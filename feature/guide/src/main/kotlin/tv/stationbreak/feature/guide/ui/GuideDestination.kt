// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.activity.compose.BackHandler
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
import tv.stationbreak.feature.guide.GuideDirection
import tv.stationbreak.feature.guide.GuideEvent
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.GuideViewModel
import tv.stationbreak.feature.guide.ui.mark.LocalMarkCache
import tv.stationbreak.feature.guide.ui.mark.MarkCaches

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
 * Keys: the guide moves its own focus (model-driven, not Compose focus search), with the D-pad and Select; Back closes
 * the info overlay when one is open and is otherwise left to the activity, which leaves live TV. Select on the Channels
 * button above the grid shows [channels] over the guide (channel management, which `app` hands in); the guide's own
 * keys and Back stand down while it shows. Left from a row's first cell moves the focus to its channel; Select there
 * shows [properties] (the channel's properties sheet, #631) over the guide, which stands down the same way. Select on
 * Libraries in the Settings panel shows [libraries] the same way; Back from it returns to the panel, and Back from the
 * panel closes it.
 */
@Suppress("LongParameterList") // The guide's own four, its three slots, and the modifier.
@Composable
fun GuideDestination(
    opening: GuideOpening,
    pictureHole: DpRect,
    onTune: (ChannelId) -> Unit,
    channels: ChannelsSlot,
    properties: PropertiesSlot,
    libraries: LibrariesSlot,
    modifier: Modifier = Modifier,
) {
    val viewModel: GuideViewModel = hiltViewModel()
    val themes = rememberGuideThemeStore()
    val theme by themes.theme.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalGuideColors provides theme.colors,
        LocalMarkCache provides MarkCaches.get(LocalContext.current),
    ) {
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
            properties,
            libraries,
        )
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
 * [GuideDestination] without Hilt, so a test can bring its own ViewModel.
 *
 * [openedAtNanos] has no default on purpose: it keys the session, and a default expression such as
 * `System.nanoTime()` is evaluated again when this recomposes (once it has read state, Compose re-runs its defaults),
 * which would end the session and open a new one on an ordinary recomposition, such as channel management closing.
 */
@Suppress("LongParameterList", "LongMethod") // The destination's own, and two a test leaves at their defaults.
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
    properties: PropertiesSlot = NoProperties,
    libraries: LibrariesSlot = NO_LIBRARIES_SLOT,
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
    // A Tune is acted on as it is received, never right after Select: the guide may refuse a Select (info open, focus
    // gone), and a session that ends drops its queued events.
    val tune by rememberUpdatedState(onTune)
    // When the viewer opened channel management (a fresh value per opening, for the slot), or 0 while it is not
    // showing. It starts over with each showing of the guide.
    var managingSince by remember(viewModel, openedAtNanos) { mutableLongStateOf(NOT_MANAGING) }
    val managing = managingSince != NOT_MANAGING
    // The channel whose properties sheet shows over the guide (#631), and the showing's own value for the slot.
    var propertiesOf by remember(viewModel, openedAtNanos) { mutableStateOf<ChannelId?>(null) }
    var propertiesSince by remember(viewModel, openedAtNanos) { mutableLongStateOf(NOT_MANAGING) }
    // The Settings panel while it is open (null when closed), with the Libraries screen shown over the guide from it.
    var settings by remember(viewModel, openedAtNanos) { mutableStateOf<SettingsPanel?>(null) }
    val librariesShown = settings?.librariesShown == true
    // Any screen over the guide has the keys, and the guide stays composed under it. Channel management and Settings,
    // Libraries hide the guide; the properties sheet is drawn over it.
    val covered = managing || propertiesOf != null || librariesShown
    val hidden = managing || librariesShown
    val backs = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val leave by rememberUpdatedState { backs?.onBackPressed() }
    var themeChoice by remember { mutableStateOf(theme) }
    LaunchedEffect(theme) { themeChoice = theme }
    val chooseTheme = rememberUpdatedState(onTheme)
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
                    if (managingSince == NOT_MANAGING && propertiesOf == null && settings?.librariesShown != true) {
                        viewModel.channelsOpened()
                        propertiesSince = System.nanoTime().coerceAtLeast(1L)
                        propertiesOf = it
                    }
                },
            )
        }
    }
    val info by viewModel.info.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    // Back over the info overlay closes it; with none open, Back walks the focus outwards (to what is playing, then to
    // the menu row), and from the menu row it is the activity's and leaves live TV. While channel management shows, it
    // is the screen's.
    BackHandler(enabled = info != null && !covered) { viewModel.closeInfo() }
    val keys = remember { FocusRequester() }
    // The guide takes the key focus when it shows, and takes it back when the screen that covered it has gone.
    LaunchedEffect(keys, covered) { if (!covered) keys.requestFocus() }
    val back: (() -> Unit)? =
        remember(viewModel, backs) { backs?.let { d -> { if (!viewModel.back()) d.onBackPressed() } } }
    val move = remember(viewModel) { viewModel.tracedMove() }
    Box(modifier.fillMaxSize()) {
        GuideScreen(
            snapshot = viewModel.snapshot,
            panel = viewModel.panel,
            info = info,
            current = current,
            pictureHole = pictureHole,
            settings = settings,
            themeChoice = themeChoice,
            noLibraries = selection?.gone == true,
            modifier =
                Modifier
                    // Kept composed (its state and the key focus stay) but not drawn under the screen that covers it.
                    .graphicsLayer { alpha = if (hidden) 0f else 1f }
                    .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
                    .focusRequester(keys)
                    .onKeyEvent {
                        guideRootKey(
                            it,
                            managing = covered,
                            settings = settings,
                            onSettings = { step ->
                                step.theme?.let { next ->
                                    themeChoice = next
                                    chooseTheme.value(next)
                                }
                                settings = step.panel
                            },
                            grid = { event -> guideKey(event, move, viewModel::select, back) },
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
            libraries(pictureHole) {
                // As for channel management: the key focus comes back first, then the slot goes, and the panel is
                // left open on Libraries.
                runCatching { keys.requestFocus() }
                settings = settings?.librariesDone()
            }
        }
    }
}

private const val NOT_MANAGING = 0L

/** A slot that draws nothing, for a test that never opens Settings, Libraries. */
private val NO_LIBRARIES_SLOT: LibrariesSlot = { _, _ -> }

/** What a one-off guide event asks the screen to do. Exit takes the way Back takes from the menu row. */
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

/** Keys for the guide: the slot has them while it shows, the Settings panel has them while it is open. */
private fun guideRootKey(
    event: KeyEvent,
    managing: Boolean,
    settings: SettingsPanel?,
    onSettings: (SettingsStep) -> Unit,
    grid: (KeyEvent) -> Boolean,
): Boolean =
    when {
        managing -> {
            true
        }

        settings != null -> {
            settingsStep(settings, event.key, event.type, event.nativeKeyEvent.repeatCount)
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
 * Back is handed to [back] on its release, as the activity itself would: the info overlay's BackHandler closes it,
 * with none open the focus goes to what is playing, then to the menu row, and from there the activity leaves live TV.
 * Left unconsumed, Compose would spend it on moving the focus out of the guide to live TV's root ("exit"), and the
 * viewer would have to press Back twice. The media keys are not the guide's.
 */
internal fun guideKey(
    event: KeyEvent,
    move: (GuideDirection, Boolean, Long) -> Unit,
    select: () -> Unit,
    back: (() -> Unit)? = null,
): Boolean {
    val down = event.type == KeyEventType.KeyDown
    val direction = directionOf(event.key)
    val handled =
        when {
            event.key == Key.Back -> back != null
            direction != null -> true
            else -> event.key in SELECT_KEYS
        }
    when {
        !handled -> Unit

        // Any release, even one whose down the guide never saw. The Back that opened the guide never reaches here
        // (live's root holds that press to its release), so a release here is always a later press. A fast double
        // Back is such a press: its down can arrive in the frame before the guide has the focus, going to live's root,
        // which passes it, and only the release lands here. Waiting for a down the guide saw would swallow that press.
        event.key == Key.Back -> if (event.type == KeyEventType.KeyUp) back?.invoke()

        direction != null -> if (down) with(event.nativeKeyEvent) { move(direction, repeatCount > 0, eventTime) }

        down && event.nativeKeyEvent.repeatCount == 0 -> select()
    }
    return handled
}

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
