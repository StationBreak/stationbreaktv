// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.ProgramInfoOverlay
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.feature.guide.CellDetails
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GUIDE_VIEWPORT_SPAN
import tv.stationbreak.feature.guide.GUIDE_VISIBLE_ROWS
import tv.stationbreak.feature.guide.GuideFocus
import tv.stationbreak.feature.guide.GuideMenuEntry
import tv.stationbreak.feature.guide.GuideSnapshot
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.R
import tv.stationbreak.feature.guide.indexOf
import tv.stationbreak.feature.guide.isAwayFrom
import java.time.ZoneId
import kotlin.math.abs

// The guide's layout in dp (the guide UX notes, on a 960 x 540 dp screen with a 5% safe area).
internal val ChannelColumnWidth = 84.dp

/**
 * The band the visible rows share; each row is an equal part of it, so the grid body keeps its height. Five 40 dp
 * cells with 1 dp between them are 204 dp; each row's part is 40.8 dp, the cell's 0.5 dp inset on every side being
 * the gap, so a cell is 39.8 dp tall.
 */
internal val RowsBandHeight: Dp = 204.dp
internal val RowHeight: Dp = RowsBandHeight / GUIDE_VISIBLE_ROWS
internal val ClockHeight = 20.dp
internal val TopGap = 4.dp
internal val HintGap = 4.dp
internal val HintHeight = 20.dp
private val HintPairGap = 20.dp
private val HintActionGap = 6.dp
private val KeyHeight = 16.dp
private val KeyStroke = 1.dp
private val KeyRadius = 3.dp
private val KeyPadding = 6.dp
private val KeyRoundPadding = 4.dp
private val PanelGap = 12.dp
private const val HINT_SPACE = "   "

// How far (dp) the hole may sit from the safe area's top and right edges: rounding, not placement.
private const val CORNER_SLACK = 0.5f

/**
 * Where the guide's parts go on a screen, from the shared safe area and the [hole] live TV laid its picture in (screen
 * dp): the panel takes the band from the safe area's left edge to the hole, as tall as the hole reaches down, and the
 * clock strip and the grid start below it. Live owns where the picture is; the guide only lays out around it. Times
 * become widths at [viewportWidth] per [GUIDE_VIEWPORT_SPAN]: about 7.7 dp a minute (696 dp over 90 minutes) on a 960
 * dp-wide screen. The hole must be in the safe area's top-right corner, where `ScreenLayout.pictureRect` puts it
 * (anything else is refused), and the guide is laid out left to right whatever the layout direction, as that corner
 * and its hole are.
 */
@Immutable
internal class GuideGeometry(
    screen: DpSize,
    hole: DpRect,
) {
    val safe: DpRect = ScreenLayout.safeArea(screen)

    init {
        // The panel takes the band left of the hole and the grid starts below it: a hole anywhere else would be drawn
        // over. Live puts it at ScreenLayout.pictureRect, the safe area's top-right corner.
        val atTop = abs(hole.top.value - safe.top.value) <= CORNER_SLACK
        val atRight = abs(hole.right.value - safe.right.value) <= CORNER_SLACK
        require(atTop && atRight) { "The picture hole must be in the safe area's top-right corner: $hole in $safe" }
        require(hole.left - PanelGap > safe.left) { "The picture hole leaves no room for the panel: $hole in $safe" }
    }

    val panelWidth: Dp = hole.left - safe.left - PanelGap
    val topHeight: Dp = hole.bottom - safe.top
    val viewportWidth: Dp = safe.right - safe.left - ChannelColumnWidth
    val spanMs: Long = GUIDE_VIEWPORT_SPAN.inWholeMilliseconds

    /** How wide [ms] of time is on the grid. */
    fun widthOf(ms: Long): Dp = viewportWidth * (ms.toFloat() / spanMs)
}

/**
 * The guide: the details [panel] top-left, the picture's place top-right, the clock strip above the time columns, the
 * grid of [snapshot] below, and the hint row. The grid reads [snapshot] as one value, so a model and a focus from
 * different moments are never drawn together; each cell reads only whether it is the focused one, so a move recomposes
 * the two cells it touched and the panel. [info] is the overlay Select opened on a later program; the panel is not
 * shown under it.
 */
@Suppress("LongParameterList") // The guide's flows, the overlay, the channel playing and the picture's hole.
@Composable
internal fun GuideScreen(
    snapshot: StateFlow<GuideSnapshot>,
    panel: StateFlow<CellDetails?>,
    info: CellDetails?,
    current: ChannelId?,
    pictureHole: DpRect,
    settings: SettingsPanel? = null,
    themeChoice: GuideTheme = GuideTheme.Dark,
    modifier: Modifier = Modifier,
    noLibraries: Boolean = false,
    syncNow: SyncNowState = SyncNowState.Idle,
) {
    val shown = snapshot.collectAsStateWithLifecycle()
    // The snapshot is read inside the parts that draw it, never here, so this level does not recompose on a move.
    // No cell is focused while the focus is on the menu row, whatever cell it will return to.
    // Nor while it is on the channel column, whose own cell is outlined instead.
    val focusOf: () -> GuideFocus? =
        remember(shown) { { shown.value.let { if (it.menu != null || it.onChannel) null else it.focus } } }
    val channelFocusOf: () -> ChannelId? =
        remember(shown) {
            {
                shown.value
                    .takeIf { it.onChannel }
                    ?.focus
                    ?.channel
            }
        }
    // The panel's variant, read as a state of its own so a move on the grid does not recompose this level.
    val onChannel by remember(shown) { derivedStateOf { shown.value.onChannel } }
    val menuOf: () -> GuideMenuEntry? = remember(shown) { { shown.value.menu } }
    // The clock and the viewport as states of their own, so reading them is invalidated only when they change.
    val now = remember(shown) { derivedStateOf { (shown.value.state as? GuideState.Ready)?.nowMs } }
    val viewport =
        remember(shown) { derivedStateOf { (shown.value.state as? GuideState.Ready)?.viewportStartMs ?: 0L } }
    val nowOf: () -> Long? = remember(now) { { now.value } }
    val viewportOf: () -> Long = remember(viewport) { { viewport.value } }
    val stateOf: () -> GuideState = remember(shown) { { shown.value.state } }
    val snapshotOf: () -> GuideSnapshot = remember(shown) { { shown.value } }
    // Left to right whatever the device's language: the picture's hole is top-right in screen coordinates, and the
    // panel, the clock strip's times and the grid's time axis read from it leftwards and onwards, as on a cable guide.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize().testTag(GuideTags.SCREEN)) {
            val geometry =
                remember(maxWidth, maxHeight, pictureHole) { GuideGeometry(DpSize(maxWidth, maxHeight), pictureHole) }
            val colors = LocalGuideColors.current
            Box(Modifier.fillMaxSize().drawBehind { ground(pictureHole, colors) })
            Column(
                Modifier
                    .offset(geometry.safe.left, geometry.safe.top)
                    .width(geometry.safe.right - geometry.safe.left),
            ) {
                // The info overlay says what the panel would, so the panel makes way while it is open: the title and
                // the time are shown once. Its place is kept, so the grid does not move.
                // The menu row takes the foot of that band, so the grid and the picture stay where they are.
                val panelSize = Modifier.size(geometry.panelWidth, geometry.topHeight - ButtonRowHeight)
                when {
                    info != null -> Spacer(panelSize)
                    settings != null -> GuideSettingsPanel(themeChoice, settings.row, panelSize, syncNow)
                    onChannel -> ChannelPanel(snapshotOf, panelSize)
                    else -> DetailsPanel(panel, nowOf, panelSize)
                }
                GuideMenu(menuOf)
                Spacer(Modifier.height(TopGap))
                GuideBody(
                    stateOf,
                    current,
                    geometry,
                    GridReaders(focusOf, viewportOf, nowOf, channelFocusOf),
                    noLibraries,
                )
                Spacer(Modifier.height(HintGap))
                HintRow(snapshotOf, current)
            }
            info?.let { GuideInfoOverlay(it, nowOf) }
        }
    }
}

/**
 * The ground of the guide: a diagonal from [GuideColors.pageGradientStart] to [GuideColors.page] across the first 60%,
 * then [GuideColors.page]. The [hole] live TV laid its picture in is left clear. The picture itself stays dark.
 */
private fun DrawScope.ground(
    hole: DpRect,
    colors: GuideColors,
) {
    val brush =
        Brush.linearGradient(
            0f to colors.pageGradientStart,
            0.6f to colors.page,
            1f to colors.page,
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
    val cleared =
        Path().apply {
            addRect(Rect(hole.left.toPx(), hole.top.toPx(), hole.right.toPx(), hole.bottom.toPx()))
        }
    clipPath(cleared, clipOp = ClipOp.Difference) {
        drawRect(brush)
    }
}

/** What the grid reads from the snapshot on its own, outside composition where it can. */
@Immutable
private class GridReaders(
    val focusOf: () -> GuideFocus?,
    val viewportOf: () -> Long,
    val nowOf: () -> Long?,
    val channelFocusOf: () -> ChannelId?,
)

/**
 * The same grid frame: the same model (by identity, never a deep compare), first row and clock. A focus move and a
 * horizontal page publish a new Ready that is the same frame, so the body is not recomposed for them: the viewport is
 * read on its own, in the layout and draw phases.
 */
private object SameGridFrame : SnapshotMutationPolicy<GuideState> {
    override fun equivalent(
        a: GuideState,
        b: GuideState,
    ): Boolean =
        if (a is GuideState.Ready && b is GuideState.Ready) {
            a.model === b.model &&
                a.firstVisibleRow == b.firstVisibleRow &&
                a.nowMs == b.nowMs
        } else {
            a == b
        }
}

/** Called each time the grid's body composes. Tests count compositions with it; null (the default) in the app. */
internal val LocalBodyComposed = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The clock strip and the grid, or what stands in for them while there is no grid. Reads the state itself. */
@Composable
private fun GuideBody(
    stateOf: () -> GuideState,
    current: ChannelId?,
    geometry: GuideGeometry,
    readers: GridReaders,
    noLibraries: Boolean,
) {
    val frame by remember(stateOf) { derivedStateOf(SameGridFrame, stateOf) }
    LocalBodyComposed.current?.invoke()
    val height = ClockHeight + RowHeight * GUIDE_VISIBLE_ROWS
    val state = frame
    val message = guideMessageOf(state, noLibraries)
    when {
        message != null -> {
            Message(message.text, message.tag, height)
        }

        // An open on its way: nothing yet, so a quick open never flashes a loading line (a slow one says Loading).
        state !is GuideState.Ready -> {
            Spacer(Modifier.height(height))
        }

        else -> {
            GuideGrid(
                state.model,
                state.firstVisibleRow,
                current,
                geometry,
                readers.focusOf,
                readers.viewportOf,
                readers.nowOf,
                readers.channelFocusOf,
                Modifier.height(height),
            )
        }
    }
}

/** A line that stands where the grid goes: its copy and its test tag. */
internal enum class GuideMessage(
    @StringRes val text: Int,
    val tag: String,
) {
    Loading(R.string.guide_loading, GuideTags.LOADING),
    Empty(R.string.guide_empty, GuideTags.EMPTY),

    /** None of this TV's libraries is available (spec 26). */
    NoLibraries(R.string.guide_no_libraries, GuideTags.NO_LIBRARIES),
}

/**
 * The line that stands where the grid goes, or null when the grid (or nothing yet) is drawn: no libraries (spec 26)
 * whatever the schedule still holds, once the guide is open; else Loading or Empty as they are.
 */
internal fun guideMessageOf(
    state: GuideState,
    noLibraries: Boolean,
): GuideMessage? =
    when {
        state == GuideState.Closed -> null
        noLibraries -> GuideMessage.NoLibraries
        state == GuideState.Loading -> GuideMessage.Loading
        state == GuideState.Empty -> GuideMessage.Empty
        else -> null
    }

/** A still line where the grid goes: no spinner, an animation costs frames on the older TV. */
@Composable
private fun Message(
    text: Int,
    tag: String,
    height: Dp,
) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(text),
            modifier = Modifier.testTag(tag),
            style = guideText(18.sp, FontWeight.Medium),
            color = LocalGuideColors.current.textSecondary,
        )
    }
}

/**
 * Whether Back goes to the menu row: the guide shows a grid or says it is empty, the focus is not on the menu already,
 * and it is not on the grid away from the playing cell (Back goes there first).
 */
private fun GuideSnapshot.isAtPlaying(current: ChannelId?): Boolean =
    (state is GuideState.Ready || state == GuideState.Empty) && menu == null && !isAwayFrom(current)

/** Which hint the focused cell calls for: what OK does on it, as `GuideViewModel.select` does it. */
internal enum class HintKind { Watch, Info, Properties, BackOnly }

internal fun hintKindOf(snapshot: GuideSnapshot): HintKind {
    val ready = snapshot.state as? GuideState.Ready
    val located = snapshot.focus?.let { ready?.model?.indexOf(it) }
    // On the menu row OK does what the entry says by itself.
    if (ready == null || located == null || snapshot.menu != null) return HintKind.BackOnly
    val cell = ready.model.rows[located.first].cells[located.second]
    return when {
        // On the channel column OK opens its properties (#631).
        snapshot.onChannel -> HintKind.Properties

        cell.startMs <= ready.nowMs && ready.nowMs < cell.endMs -> HintKind.Watch

        cell.kind != CellKind.Program -> HintKind.BackOnly

        else -> HintKind.Info
    }
}

/**
 * The keys that matter here, short and following the focus: "OK Watch" on a cell on now (a slate too: OK changes to
 * its channel), "OK Info" on a later program, nothing for OK on a later slate or unavailable cell, and "Back Exit
 * live TV", since leaving from the guide is the one thing no one would guess, which becomes "Back To what's playing"
 * while the focus is off the playing channel's cell and "Back To the menu" on it. Key names sit on the key pill; what
 * they do is secondary. Recomposes only when the kind of the focused cell changes.
 */
@Composable
private fun HintRow(
    snapshotOf: () -> GuideSnapshot,
    current: ChannelId?,
) {
    val kind by remember(snapshotOf) { derivedStateOf { hintKindOf(snapshotOf()) } }
    // Away from the playing channel's cell, Back goes there; on it, to the menu row; from the menu row it exits. Read
    // like the kind, so it recomposes only when the answer changes.
    val away by remember(snapshotOf, current) { derivedStateOf { snapshotOf().isAwayFrom(current) } }
    val ok = stringResource(R.string.guide_key_ok)
    // On the channel column the way out is back to the programs, by Right or Back (#631).
    val onChannel by remember(snapshotOf) { derivedStateOf { snapshotOf().onChannel } }
    val back = stringResource(if (onChannel) R.string.guide_key_right else R.string.guide_key_back)
    val toMenu by remember(snapshotOf, current) { derivedStateOf { snapshotOf().isAtPlaying(current) } }
    val exit =
        stringResource(
            when {
                onChannel -> R.string.guide_hint_programs
                away -> R.string.guide_hint_playing
                toMenu -> R.string.guide_hint_menu
                else -> R.string.guide_hint_exit
            },
        )
    val action =
        when (kind) {
            HintKind.Watch -> stringResource(R.string.guide_hint_watch)
            HintKind.Info -> stringResource(R.string.guide_hint_info)
            HintKind.Properties -> stringResource(R.string.guide_hint_properties)
            HintKind.BackOnly -> null
        }
    val spoken =
        buildString {
            if (action != null) append("$ok $action$HINT_SPACE")
            append("$back $exit")
        }
    val actionStyle = guideText(10.sp, FontWeight.Normal)
    Row(
        Modifier
            .height(HintHeight)
            .fillMaxWidth()
            .testTag(GuideTags.HINT)
            .clearAndSetSemantics { text = AnnotatedString(spoken) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (action != null) {
            HintKey(ok, round = true)
            HintAction(action, actionStyle)
            Spacer(Modifier.width(HintPairGap))
        }
        HintKey(back, round = false)
        HintAction(exit, actionStyle)
    }
}

@Composable
private fun HintAction(
    text: String,
    style: TextStyle,
) = Text(
    text = text,
    modifier = Modifier.padding(start = HintActionGap),
    style = style,
    color = LocalGuideColors.current.textSecondary,
    maxLines = 1,
)

/** An outlined key cap: a 1 dp stroke at 28%, 3 dp corners, 16 dp tall (the OK key is a circle), 10 sp SemiBold. */
@Composable
private fun HintKey(
    label: String,
    round: Boolean,
) {
    val colors = LocalGuideColors.current
    val shape = if (round) CircleShape else RoundedCornerShape(KeyRadius)
    Text(
        text = label,
        modifier =
            Modifier
                .height(KeyHeight)
                .border(KeyStroke, colors.keyCapStroke, shape)
                .padding(horizontal = if (round) KeyRoundPadding else KeyPadding)
                .wrapContentHeight(Alignment.CenterVertically),
        style = guideText(10.sp, FontWeight.SemiBold),
        color = colors.textPrimary,
        maxLines = 1,
    )
}

/** The info overlay of a later program: the shared overlay, its slot with the weekday when it is not today. */
@Composable
private fun GuideInfoOverlay(
    info: CellDetails,
    nowOf: () -> Long?,
) {
    val copy = programCopy()
    val cell = info.cell
    val zone = remember { ZoneId.systemDefault() }
    val is24h = is24Hour()
    val now by remember(nowOf) { derivedStateOf(nowOf) }
    val today = now ?: cell.startMs
    val item = info.item
    ProgramInfoOverlay(
        channel = channelLine(info.channelNumber, info.channelName),
        title = cellTitle(cell, copy),
        episodeLine = episodeLine(cell, copy),
        slot = ProgramText.slotOn(cell.startMs, cell.endMs, today, zone, is24h),
        overview = item?.overview,
        year = item?.year,
        genres = item?.genres.orEmpty(),
        runtimeMinutes = item?.runtime?.inWholeMinutes?.toInt(),
        loading = info.loading,
        modifier = Modifier.testTag(GuideTags.INFO),
    )
}
