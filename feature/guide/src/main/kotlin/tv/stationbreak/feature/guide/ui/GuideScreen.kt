// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.ProgramInfoOverlay
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.feature.guide.CellDetails
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GUIDE_VIEWPORT_SPAN
import tv.stationbreak.feature.guide.GUIDE_VISIBLE_ROWS
import tv.stationbreak.feature.guide.GuideFocus
import tv.stationbreak.feature.guide.GuideSnapshot
import tv.stationbreak.feature.guide.GuideState
import tv.stationbreak.feature.guide.R
import tv.stationbreak.feature.guide.indexOf
import java.time.ZoneId

// The guide's layout in dp (the guide UX notes, on a 960 x 540 dp screen with a 5% safe area).
internal val ChannelColumnWidth = 144.dp
internal val RowHeight = 38.dp
internal val ClockHeight = 24.dp
private val TopGap = 6.dp
private val HintGap = 8.dp
private val HintHeight = 24.dp
private val PanelGap = 16.dp
private const val HINT_SPACE = "   "

/**
 * Where the guide's parts go on a screen, from the shared safe area: the panel takes the band beside the place the
 * corner picture has (`ScreenLayout.pictureRect`), whether or not live has put its picture there. Times become widths
 * at [viewportWidth] per [GUIDE_VIEWPORT_SPAN]: 8 dp a minute on a 960 dp-wide screen.
 */
@Immutable
internal class GuideGeometry(
    screen: DpSize,
) {
    val safe: DpRect = ScreenLayout.safeArea(screen)
    private val corner: DpRect = ScreenLayout.pictureRect(safe)
    val panelWidth: Dp = corner.left - safe.left - PanelGap
    val topHeight: Dp = corner.bottom - corner.top
    val viewportWidth: Dp = safe.right - safe.left - ChannelColumnWidth
    val spanMs: Long = GUIDE_VIEWPORT_SPAN.inWholeMilliseconds

    /** How wide [ms] of time is on the grid. */
    fun widthOf(ms: Long): Dp = viewportWidth * (ms.toFloat() / spanMs)
}

/**
 * The guide: the details [panel] top-left, the picture's place top-right, the clock strip above the time columns, the
 * grid of [snapshot] below, and the hint row. The grid reads [snapshot] as one value, so a model and a focus from
 * different moments are never drawn together; each cell reads only whether it is the focused one, so a move recomposes
 * the two cells it touched and the panel. [info] is the overlay Select opened on a later program.
 */
@Suppress("LongParameterList") // The guide's flows, the overlay, the channel playing and the picture's hole.
@Composable
internal fun GuideScreen(
    snapshot: StateFlow<GuideSnapshot>,
    panel: StateFlow<CellDetails?>,
    info: CellDetails?,
    current: ChannelId?,
    pictureHole: DpRect?,
    modifier: Modifier = Modifier,
) {
    val shown = snapshot.collectAsStateWithLifecycle()
    // The snapshot is read inside the parts that draw it, never here, so this level does not recompose on a move.
    val focusOf: () -> GuideFocus? = remember(shown) { { shown.value.focus } }
    // The clock and the viewport as states of their own, so reading them is invalidated only when they change.
    val now = remember(shown) { derivedStateOf { (shown.value.state as? GuideState.Ready)?.nowMs } }
    val viewport =
        remember(shown) { derivedStateOf { (shown.value.state as? GuideState.Ready)?.viewportStartMs ?: 0L } }
    val nowOf: () -> Long? = remember(now) { { now.value } }
    val viewportOf: () -> Long = remember(viewport) { { viewport.value } }
    val stateOf: () -> GuideState = remember(shown) { { shown.value.state } }
    val snapshotOf: () -> GuideSnapshot = remember(shown) { { shown.value } }
    BoxWithConstraints(modifier.fillMaxSize().testTag(GuideTags.SCREEN)) {
        val geometry = remember(maxWidth, maxHeight) { GuideGeometry(DpSize(maxWidth, maxHeight)) }
        Box(Modifier.fillMaxSize().drawBehind { ground(pictureHole) })
        Column(
            Modifier
                .offset(geometry.safe.left, geometry.safe.top)
                .width(geometry.safe.right - geometry.safe.left),
        ) {
            DetailsPanel(panel, nowOf, Modifier.size(geometry.panelWidth, geometry.topHeight))
            Spacer(Modifier.height(TopGap))
            GuideBody(stateOf, current, geometry, GridReaders(focusOf, viewportOf, nowOf))
            Spacer(Modifier.height(HintGap))
            HintRow(snapshotOf)
        }
        info?.let { GuideInfoOverlay(it, nowOf) }
    }
}

/** The ground of the guide: Ink everywhere, except the [hole] live TV laid its picture in, when it gave one. */
private fun DrawScope.ground(hole: DpRect?) {
    if (hole == null) {
        drawRect(BroadcastColors.Ink)
        return
    }
    val left = hole.left.toPx()
    val top = hole.top.toPx()
    val right = hole.right.toPx()
    val bottom = hole.bottom.toPx()
    drawRect(BroadcastColors.Ink, size = Size(size.width, top))
    drawRect(BroadcastColors.Ink, Offset(0f, bottom), Size(size.width, size.height - bottom))
    drawRect(BroadcastColors.Ink, Offset(0f, top), Size(left, bottom - top))
    drawRect(BroadcastColors.Ink, Offset(right, top), Size(size.width - right, bottom - top))
}

/** What the grid reads from the snapshot on its own, outside composition where it can. */
@Immutable
private class GridReaders(
    val focusOf: () -> GuideFocus?,
    val viewportOf: () -> Long,
    val nowOf: () -> Long?,
)

/**
 * The same grid frame: the same model (by identity, never a deep compare), viewport, first row and clock. A focus move
 * publishes a new Ready that is the same frame, so the body is not recomposed for it.
 */
private object SameGridFrame : SnapshotMutationPolicy<GuideState> {
    override fun equivalent(
        a: GuideState,
        b: GuideState,
    ): Boolean =
        if (a is GuideState.Ready && b is GuideState.Ready) {
            a.model === b.model &&
                a.viewportStartMs == b.viewportStartMs &&
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
) {
    val frame by remember(stateOf) { derivedStateOf(SameGridFrame, stateOf) }
    LocalBodyComposed.current?.invoke()
    val height = ClockHeight + RowHeight * GUIDE_VISIBLE_ROWS
    when (val state = frame) {
        // An open on its way: nothing yet, so a cached guide never flashes a loading line.
        GuideState.Closed -> {
            Spacer(Modifier.height(height))
        }

        GuideState.Loading -> {
            Message(R.string.guide_loading, GuideTags.LOADING, height)
        }

        GuideState.Empty -> {
            Message(R.string.guide_empty, GuideTags.EMPTY, height)
        }

        is GuideState.Ready -> {
            GuideGrid(
                state.model,
                state.firstVisibleRow,
                current,
                geometry,
                readers.focusOf,
                readers.viewportOf,
                readers.nowOf,
                Modifier.height(height),
            )
        }
    }
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
            style = TextStyle(fontSize = 18.sp),
            color = BroadcastColors.Ground,
        )
    }
}

/** Which hint the focused cell calls for. */
private enum class HintKind { Watch, Info, BackOnly }

private fun hintKindOf(snapshot: GuideSnapshot): HintKind {
    val ready = snapshot.state as? GuideState.Ready
    val located = snapshot.focus?.let { ready?.model?.indexOf(it) }
    if (ready == null || located == null) return HintKind.BackOnly
    val cell = ready.model.rows[located.first].cells[located.second]
    return when {
        cell.kind == CellKind.Slate -> HintKind.BackOnly
        cell.startMs <= ready.nowMs && ready.nowMs < cell.endMs -> HintKind.Watch
        else -> HintKind.Info
    }
}

/**
 * The keys that matter here, short and following the focus: "OK Watch" on a program on now, "OK Info" on a later one,
 * and always "Back Exit live TV", since leaving from the guide is the one thing no one would guess. Key names in Cream,
 * what they do in Ground. Recomposes only when the kind of the focused cell changes.
 */
@Composable
private fun HintRow(snapshotOf: () -> GuideSnapshot) {
    val kind by remember(snapshotOf) { derivedStateOf { hintKindOf(snapshotOf()) } }
    val ok = stringResource(R.string.guide_key_ok)
    val back = stringResource(R.string.guide_key_back)
    val exit = stringResource(R.string.guide_hint_exit)
    val action =
        when (kind) {
            HintKind.Watch -> stringResource(R.string.guide_hint_watch)
            HintKind.Info -> stringResource(R.string.guide_hint_info)
            HintKind.BackOnly -> null
        }
    val text =
        buildAnnotatedString {
            if (action != null) {
                withStyle(SpanStyle(color = BroadcastColors.Cream)) { append(ok) }
                withStyle(SpanStyle(color = BroadcastColors.Ground)) { append(" $action$HINT_SPACE") }
            }
            withStyle(SpanStyle(color = BroadcastColors.Cream)) { append(back) }
            withStyle(SpanStyle(color = BroadcastColors.Ground)) { append(" $exit") }
        }
    Text(
        text = text,
        modifier = Modifier.height(HintHeight).testTag(GuideTags.HINT),
        style = mono(16.sp),
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
