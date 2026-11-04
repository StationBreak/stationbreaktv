// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * The ordering modes of rules format versions 1 to 3, with the exact names a rules file uses (docs/rules-format.md,
 * "Ordering"). [Rotation] and [InOrder] are read only in a version 2 file, [Curated]
 * only in a version 3 file. It lives here so that the rules format and the scheduler share one enum.
 */
enum class OrderingMode(
    val wireName: String,
) {
    Shuffle("shuffle"),
    Chronological("chronological"),
    RandomInOrder("randomInOrder"),
    Rotation("rotation"),
    InOrder("inOrder"),
    Curated("curated"),
}
