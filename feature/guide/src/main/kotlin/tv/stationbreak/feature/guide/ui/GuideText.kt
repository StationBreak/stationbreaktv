// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.BroadcastType
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import java.time.ZoneId

/** The letter spacing of the guide's capitals (channel lines, the NOW chip), in sp. */
internal const val LETTER_SPACING = 1.5f

/** "3 · Test Channel": the channel line of the panel and the overlay. */
internal fun channelLine(
    number: Int,
    name: String,
) = "$number · $name"

/** What a cell is called: its display title, "Off air until 8:30 PM" for a slate, nothing for a lost program. */
@Composable
internal fun cellTitle(
    cell: GuideCell,
    copy: ProgramCopy,
): String =
    when (cell.kind) {
        CellKind.Slate -> copy.offAirUntil(ProgramText.time(cell.endMs, ZoneId.systemDefault(), is24Hour()))
        CellKind.Program -> cell.title.orEmpty()
    }

/** The episode line of a cell (see [ProgramText.episodeLine]); null for a movie, a slate and a lost program. */
internal fun episodeLine(
    cell: GuideCell,
    copy: ProgramCopy,
): String? =
    cell.info?.let {
        ProgramText.episodeLine(
            isEpisode = it.kind == ScheduleItemKind.Episode,
            seriesName = it.seriesName,
            season = it.seasonNumber,
            episode = it.episodeNumber,
            title = it.title,
            copy = copy,
        )
    }

/** The minutes from [nowMs] to [atMs] as the clock and the slot read (see [ProgramText.minutesUntil]). */
internal fun minutesUntil(
    atMs: Long,
    nowMs: Long,
): Int = ProgramText.minutesUntil(atMs, nowMs)

@Composable
internal fun programCopy(): ProgramCopy {
    val resources = LocalResources.current
    return remember(resources) { ProgramCopy.from(resources) }
}

@Composable
internal fun is24Hour(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        android.text.format.DateFormat
            .is24HourFormat(context)
    }
}

/** Space Mono Bold at [size]: labels, times and channel numbers. */
internal fun mono(
    size: TextUnit,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = BroadcastType.Mono,
    fontWeight = FontWeight.Bold,
    fontSize = size,
    letterSpacing = letterSpacing,
)
