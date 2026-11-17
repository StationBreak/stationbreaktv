// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.R

/** What a screen reader says for a row: number, name, labels, then the reasons, each only when there is one. */
@Suppress("LongParameterList") // The row's five parts, and how two are put together.
internal fun spokenRow(
    number: Int?,
    name: String,
    labels: String,
    reason: String?,
    shelfReason: String?,
    join: (String, String) -> String,
): String =
    listOfNotNull(number?.toString(), name, labels, reason, shelfReason)
        .filter { it.isNotEmpty() }
        .reduceOrNull(join)
        .orEmpty()

/** Puts two spoken parts together with the words of string resource [res] (two placeholders: the first, the second). */
@Composable
internal fun joiner(res: Int): (String, String) -> String {
    val resources = LocalResources.current
    return { first, second -> resources.getString(res, first, second) }
}

/** The parts that are there, put together with [join]; one part is spoken alone. */
internal fun spokenParts(
    vararg parts: String?,
    join: (String, String) -> String,
): String =
    parts
        .filterNotNull()
        .filter { it.isNotEmpty() }
        .reduceOrNull(join)
        .orEmpty()

@Composable
internal fun reasonOf(row: ChannelRow): String? =
    if (row.gettingReady) {
        stringResource(R.string.channels_row_getting_ready)
    } else {
        row.reason?.let { stringResource(reasonText(it)) }
    }

/** What a screen reader says for [row]: the same words the row's own node carries. */
@Composable
internal fun spokenOf(row: ChannelRow): String =
    spokenRow(
        row.number,
        row.name,
        labelsOf(row),
        reasonOf(row),
        row.shelfReason?.let { stringResource(shelfReasonText(it)) },
        joiner(R.string.spoken_pair),
    )
