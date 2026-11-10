// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.LocalScreenFit
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.core.ui.keepVisible
import tv.stationbreak.feature.settings.LibrariesPhase
import tv.stationbreak.feature.settings.LibrariesSettingsState
import tv.stationbreak.feature.settings.LibraryRow
import tv.stationbreak.feature.settings.Notice
import tv.stationbreak.feature.settings.R
import tv.stationbreak.feature.settings.RowStatus

private val PanelGap = 16.dp
private val TickSize = 24.dp

// The check mark, as fractions of the box inside its padding: down from the left, then up to the top right.
private val CheckStroke = 2.5.dp
private const val CHECK_START_Y = 0.55f
private const val CHECK_CORNER_X = 0.38f
private const val CHECK_CORNER_Y = 0.9f
private const val CHECK_END_Y = 0.1f

// The theme's secondary size, 14sp, is under the 10-foot minimum.
private const val SECONDARY_SP = 16

/**
 * The Libraries screen (spec 26), approved to try on a TV, not final: the title, a line saying what happened or why
 * nothing can be changed, and one row per library with a tick, in the band left of [pictureHole], inside the safe
 * area. The focus is drawn from [LibrariesSettingsState.focus], not Compose's focus search.
 */
@Composable
internal fun LibrariesSettingsScreen(
    state: LibrariesSettingsState,
    pictureHole: DpRect,
    modifier: Modifier = Modifier,
) {
    // The guide's Dark or Light, as it provides it; Dark when there is no guide around.
    val panel = LocalPanelColors.current
    // Left to right whatever the device's language, as the picture's hole is top-right in screen coordinates.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize().testTag(SettingsTags.LIBRARIES)) {
            val safe = ScreenLayout.safeArea(DpSize(maxWidth, maxHeight), LocalScreenFit.current)
            val width = (pictureHole.left - safe.left - PanelGap).coerceAtLeast(0.dp)
            Column(
                Modifier
                    .offset(safe.left, safe.top)
                    .width(width)
                    .height(safe.bottom - safe.top)
                    .background(panel.ground),
            ) {
                // Not inside a Surface, so the content colour would be tv-material's black default.
                CompositionLocalProvider(LocalContentColor provides panel.text) {
                    Header(state)
                    LibraryList(state, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Header(state: LibrariesSettingsState) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.settings_libraries_title),
            Modifier.testTag(SettingsTags.TITLE),
            style = MaterialTheme.typography.titleLarge,
        )
        when (state.phase) {
            LibrariesPhase.Loading -> {
                NoteLine(stringResource(R.string.settings_libraries_loading), SettingsTags.LOADING)
            }

            is LibrariesPhase.Unavailable -> {
                NoteLine(stringResource(R.string.settings_libraries_unavailable), SettingsTags.UNAVAILABLE)
            }

            LibrariesPhase.Listed -> {
                Unit
            }
        }
        state.notice?.let { NoteLine(stringResource(noticeText(it)), SettingsTags.NOTICE) }
    }
}

private fun noticeText(notice: Notice): Int =
    when (notice) {
        Notice.LastLibrary -> R.string.settings_libraries_last_library
        Notice.Saved -> R.string.settings_libraries_saved
        Notice.Failed -> R.string.settings_libraries_failed
        Notice.NotListed -> R.string.settings_libraries_not_listed
    }

@Composable
private fun NoteLine(
    text: String,
    tag: String,
) {
    Text(
        text,
        Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = SECONDARY_SP.sp),
        color = LocalPanelColors.current.secondary,
    )
}

@Composable
private fun LibraryList(
    state: LibrariesSettingsState,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val focus = state.focus
    val rowCount = state.rows.size
    // Not only when the focus moves: the header gaining a line (Saved.) shrinks the viewport, and the focused row's own
    // status line (Adding...) grows it, and either can push the focused row past the bottom edge. The flow's key is
    // whether the focused row is clipped now, so it runs again whenever that turns true, with no allocation per frame.
    LaunchedEffect(list, focus, rowCount) {
        if (rowCount == 0) return@LaunchedEffect
        snapshotFlow { list.isOutOfView(focus) }.collect { if (it) list.keepVisible(focus) }
    }
    LazyColumn(
        modifier.fillMaxWidth().testTag(SettingsTags.LIST),
        state = list,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(state.rows.size, key = { state.rows[it].id.value }) { i ->
            LibraryLine(state.rows[i], focused = i == focus)
        }
    }
}

// Whether row [index] is not wholly inside the viewport (laid out clipped, or not laid out at all).
private fun LazyListState.isOutOfView(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return true
    return item.offset < info.viewportStartOffset || item.offset + item.size > info.viewportEndOffset
}

@Composable
private fun LibraryLine(
    row: LibraryRow,
    focused: Boolean,
) {
    val name = row.name ?: stringResource(R.string.settings_libraries_unnamed)
    // A press not stored yet shows as saving or removing; a stored add still being read, as adding.
    val status =
        when (row.status) {
            RowStatus.Saving -> stringResource(R.string.settings_libraries_saving) to SettingsTags.saving(row.id)
            RowStatus.Removing -> stringResource(R.string.settings_libraries_removing) to SettingsTags.removing(row.id)
            RowStatus.Adding -> stringResource(R.string.settings_libraries_adding) to SettingsTags.adding(row.id)
            null -> null
        }
    val tickState =
        stringResource(if (row.ticked) R.string.settings_libraries_ticked else R.string.settings_libraries_unticked)
    val spoken = status?.let { "$name, ${it.first}" } ?: name
    Row(
        Modifier
            .fillMaxWidth()
            .drawnFocus(focused, LocalPanelColors.current.ring)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(SettingsTags.row(row.id))
            // One announcement for the whole row: its name, its line (saving, removing, adding) and its tick.
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Checkbox
                toggleableState = ToggleableState(row.ticked)
                stateDescription = tickState
                selected = focused
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tick(row.ticked)
        Column(Modifier.padding(start = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            status?.let { (text, tag) ->
                Text(
                    text,
                    Modifier.testTag(tag),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = SECONDARY_SP.sp),
                    color = LocalPanelColors.current.secondary,
                )
            }
        }
    }
}

// A plain check box: an outline, filled with a check mark when ticked.
@Composable
private fun Tick(ticked: Boolean) {
    val shape = RoundedCornerShape(3.dp)
    val panel = LocalPanelColors.current
    Box(
        Modifier
            .size(TickSize)
            .background(if (ticked) panel.text else Color.Transparent, shape)
            .border(2.dp, panel.text, shape),
    ) {
        if (ticked) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = CheckStroke.toPx()
                val start = Offset(0f, size.height * CHECK_START_Y)
                val corner = Offset(size.width * CHECK_CORNER_X, size.height * CHECK_CORNER_Y)
                val end = Offset(size.width, size.height * CHECK_END_Y)
                drawLine(panel.ground, start, corner, stroke, StrokeCap.Round)
                drawLine(panel.ground, corner, end, stroke, StrokeCap.Round)
            }
        }
    }
}
