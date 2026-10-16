// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * The ordering modes of rules format version 1, with the exact names a rules file uses (docs/rules-format.md,
 * "Ordering"). It lives here so that the rules format and the scheduler share one enum.
 */
enum class OrderingMode(
    val wireName: String,
) {
    Shuffle("shuffle"),
    Chronological("chronological"),
    RandomInOrder("randomInOrder"),
}
