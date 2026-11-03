// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.stationbreak.feature.channels.R

/** A cap as the rows show it: the number, or "No limit" while a kept uncapped Shuffle is in force. */
@Composable
internal fun capText(cap: Int?): String = cap?.toString() ?: stringResource(R.string.builder_cap_no_limit)

/** "Kept from Name", or a general line when the built-in channel the settings came from is not known. */
@Composable
internal fun keptFromText(from: String): String =
    if (from.isBlank()) {
        stringResource(R.string.builder_kept_from_copied)
    } else {
        stringResource(R.string.builder_kept_from, from)
    }
