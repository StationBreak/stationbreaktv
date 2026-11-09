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
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.core.ui.StationBreakDarkColors
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.feature.settings.LibrariesPhase
import tv.stationbreak.feature.settings.LibrariesSettingsState
import tv.stationbreak.feature.settings.LibraryRow
import tv.stationbreak.feature.settings.Notice
import tv.stationbreak.feature.settings.R
import tv.stationbreak.feature.settings.RowStatus

private val PanelGap = 16.dp
private val TickSize = 24.dp

// The channel screen's colours (its palette test checks the same pairs against WCAG 4.5:1).
private val Ground = BroadcastColors.Ink
private val TextColour = BroadcastColors.Cream
private val Secondary = StationBreakDarkColors.onSurfaceVariant

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
    // Left to right whatever the device's language, as the picture's hole is top-right in screen coordinates.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize().testTag(SettingsTags.LIBRARIES)) {
            val safe = ScreenLayout.safeArea(DpSize(maxWidth, maxHeight))
            val width = (pictureHole.left - safe.left - PanelGap).coerceAtLeast(0.dp)
            Column(
                Modifier
                    .offset(safe.left, safe.top)
                    .width(width)
                    .height(safe.bottom - safe.top)
                    .background(Ground),
            ) {
                // Not inside a Surface, so the content colour would be tv-material's black default.
                CompositionLocalProvider(LocalContentColor provides TextColour) {
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
        color = Secondary,
    )
}

@Composable
private fun LibraryList(
    state: LibrariesSettingsState,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val focus = state.focus
    LaunchedEffect(focus, state.rows.size) { if (state.rows.isNotEmpty()) list.keepVisible(focus) }
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

// Scrolls only when the focused row is not fully on screen.
private suspend fun LazyListState.keepVisible(index: Int) {
    val info = layoutInfo
    val shown =
        info.visibleItemsInfo.filter {
            it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset
        }
    when {
        shown.isEmpty() -> scrollToItem(index)
        index < shown.first().index -> scrollToItem(index)
        index > shown.last().index -> scrollToItem((index - shown.size + 1).coerceAtLeast(0))
    }
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
            .drawnFocus(focused)
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
                    color = Secondary,
                )
            }
        }
    }
}

// A plain check box: an outline, filled with a check mark when ticked.
@Composable
private fun Tick(ticked: Boolean) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .size(TickSize)
            .background(if (ticked) TextColour else Color.Transparent, shape)
            .border(2.dp, TextColour, shape),
    ) {
        if (ticked) {
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = CheckStroke.toPx()
                val start = Offset(0f, size.height * CHECK_START_Y)
                val corner = Offset(size.width * CHECK_CORNER_X, size.height * CHECK_CORNER_Y)
                val end = Offset(size.width, size.height * CHECK_END_Y)
                drawLine(Ground, start, corner, stroke, StrokeCap.Round)
                drawLine(Ground, corner, end, stroke, StrokeCap.Round)
            }
        }
    }
}
