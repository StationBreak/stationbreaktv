// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

/**
 * The canonical text of an ordered list of names (an item's genres or tags) for one column of a version row (spec 09,
 * Vimes M1). API 25's SQLite has no row values, so the lists are not side tables keyed by version.
 *
 * Each name is written as its length in UTF-16 units, a colon and the name itself, and the names are joined with
 * nothing between them: `["Comedy", "A:B"]` is `6:Comedy3:A:B`. The length tells where a name ends, so no character
 * inside a name can be mistaken for a separator, and no escaping is needed. The empty list is the empty text and a list
 * of one empty name is `0:`, so they differ. One list has exactly one encoding, and two lists never share one.
 */
internal object VersionCodec {
    private const val LENGTH_END = ':'

    fun encode(names: List<String>): String =
        buildString {
            names.forEach { name ->
                append(name.length)
                append(LENGTH_END)
                append(name)
            }
        }

    /** The list [text] holds, in order. Throws [IllegalArgumentException] for text this object did not write. */
    fun decode(text: String): List<String> {
        val names = mutableListOf<String>()
        var at = 0
        while (at < text.length) {
            val colon = text.indexOf(LENGTH_END, at)
            require(colon > at) { "A version column is damaged" }
            val digits = text.substring(at, colon)
            require(digits.all { it in '0'..'9' }) { "A version column is damaged" }
            val length = digits.toIntOrNull() ?: throw IllegalArgumentException("A version column is damaged")
            val end = colon + 1 + length
            require(end <= text.length) { "A version column is damaged" }
            names += text.substring(colon + 1, end)
            at = end
        }
        return names
    }
}
