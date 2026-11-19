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
import kotlin.time.Duration

/** "3 · Test Channel", and " · Playing" ([playing], the word) for the channel live TV is on: the panel's line. */
internal fun channelLine(
    number: Int,
    name: String,
    playing: String? = null,
) = "$number · $name" + playing?.let { " · $it" }.orEmpty()

/**
 * What the panel and the info overlay call a cell: its display title, "Off air until 8:30 PM" for a slate, "Station
 * break" for a break (its meta line says what is up next: [upNextLine]), "Removed" for a program that
 * left the library. A slate or a break on the grid itself is the "OFF AIR" or "STATION BREAK" plate, not this line.
 */
@Composable
internal fun cellTitle(
    cell: GuideCell,
    copy: ProgramCopy,
): String =
    when (cell.kind) {
        CellKind.Slate -> {
            copy.offAirUntil(ProgramText.time(cell.endMs, ZoneId.systemDefault(), is24Hour()))
        }

        CellKind.Break -> {
            stringResource(R.string.guide_station_break)
        }

        CellKind.Program -> {
            cell.title.orEmpty()
        }

        CellKind.Unavailable -> {
            stringResource(R.string.guide_unavailable)
        }
    }

/**
 * The episode line of a cell (see [ProgramText.episodeLine]); null for a movie, a slate, a break and an unavailable
 * program.
 */
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

/**
 * The words of the meta line that carry numbers, from the guide's resources. A plain holder of formatters like
 * [ProgramCopy], so [metaLine] is tested on the JVM without an Android runtime. Kept here, not in [ProgramCopy]: the
 * meta line is the guide's alone.
 */
internal class MetaCopy(
    val minutes: (minutes: Int) -> String,
    val hoursMinutes: (hours: Int, minutes: Int) -> String,
    val hours: (hours: Int) -> String,
) {
    companion object {
        fun from(resources: android.content.res.Resources) =
            MetaCopy(
                minutes = { resources.getString(R.string.guide_runtime_minutes, it) },
                hoursMinutes = { h, m -> resources.getString(R.string.guide_runtime_hours_minutes, h, m) },
                hours = { resources.getString(R.string.guide_runtime_hours, it) },
            )
    }
}

/** The words of a break's meta line, from the guide's resources: a plain holder, so [upNextLine] is JVM-tested. */
internal class UpNextCopy(
    val titleAt: (title: String, time: String) -> String,
    val at: (time: String) -> String,
) {
    companion object {
        fun from(resources: android.content.res.Resources) =
            UpNextCopy(
                titleAt = { title, time -> resources.getString(R.string.guide_up_next_title_at, title, time) },
                at = { resources.getString(R.string.guide_up_next_at, it) },
            )
    }
}

/**
 * A break's meta line: "Up next: <title> at <time>", or "Up next at <time>" when the program that follows has no
 * title (it is unavailable) or nothing does. [time] is when it starts, or the break's end when nothing follows.
 */
internal fun upNextLine(
    title: String?,
    time: String,
    copy: UpNextCopy,
): String = title?.takeIf { it.isNotBlank() }?.let { copy.titleAt(it, time) } ?: copy.at(time)

/** [upNextLine] for the break [cell], in the device's zone and clock. */
@Composable
internal fun upNextLine(cell: GuideCell): String {
    val resources = LocalResources.current
    val copy = remember(resources) { UpNextCopy.from(resources) }
    val time = ProgramText.time(cell.upNextMs ?: cell.endMs, ZoneId.systemDefault(), is24Hour())
    return upNextLine(cell.upNextTitle, time, copy)
}

private const val META_SEPARATOR = " · "
private const val META_GENRES = 2
private const val MINUTES_PER_HOUR = 60

/**
 * The one short line under the title: year, runtime (rounded down to whole minutes; under a minute counts as absent)
 * and the first two genres, in that order; null when there is nothing to say.
 */
internal fun metaLine(
    year: Int?,
    runtime: Duration?,
    genres: List<String>,
    copy: MetaCopy,
): String? {
    val minutes = runtime?.inWholeMinutes?.toInt()?.takeIf { it > 0 }
    val runtimeText =
        minutes?.let {
            val h = it / MINUTES_PER_HOUR
            val m = it % MINUTES_PER_HOUR
            when {
                h == 0 -> copy.minutes(m)
                m == 0 -> copy.hours(h)
                else -> copy.hoursMinutes(h, m)
            }
        }
    val genreText =
        genres
            .filter { it.isNotBlank() }
            .take(META_GENRES)
            .joinToString(", ")
            .ifEmpty { null }
    return listOfNotNull(year?.toString(), runtimeText, genreText).joinToString(META_SEPARATOR).ifEmpty { null }
}

@Composable
internal fun metaCopy(): MetaCopy {
    val resources = LocalResources.current
    return remember(resources) { MetaCopy.from(resources) }
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
