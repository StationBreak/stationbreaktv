// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.stationbreak.feature.channels.ChannelRow
import tv.stationbreak.feature.channels.R

/** What a screen reader says for a row: number, name, labels, then the reasons, each only when there is one. */
internal fun spokenRow(
    number: Int?,
    name: String,
    labels: String,
    reason: String?,
    shelfReason: String?,
): String =
    listOfNotNull(number?.toString(), name, labels, reason, shelfReason)
        .filter { it.isNotEmpty() }
        .joinToString(", ")

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
    )
