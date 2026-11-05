// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.ui

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
