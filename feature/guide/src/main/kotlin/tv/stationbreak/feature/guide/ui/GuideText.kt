// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.feature.guide.CellKind
import tv.stationbreak.feature.guide.GuideCell
import tv.stationbreak.feature.guide.R
import java.time.ZoneId

/** "3 · Test Channel": the channel line of the panel and the overlay. */
internal fun channelLine(
    number: Int,
    name: String,
) = "$number · $name"

/**
 * What the panel and the info overlay call a cell: its display title, "Off air until 8:30 PM" for a slate,
 * "Unavailable" for a program that left the library. A slate on the grid itself is the "OFF AIR" plate, not this line.
 */
@Composable
internal fun cellTitle(
    cell: GuideCell,
    copy: ProgramCopy,
): String =
    when (cell.kind) {
        CellKind.Slate -> copy.offAirUntil(ProgramText.time(cell.endMs, ZoneId.systemDefault(), is24Hour()))
        CellKind.Program -> cell.title.orEmpty()
        CellKind.Unavailable -> stringResource(R.string.guide_unavailable)
    }

/** The episode line of a cell (see [ProgramText.episodeLine]); null for a movie, a slate and an unavailable program. */
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
