// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

/**
 * The text of an item's or a series' provider ids (spec 26) for one column: `key=value;key=value`, the keys sorted, and
 * `''` for none. The sync hands over maps already cleaned (keys `[a-z0-9._-]{1,32}`; values printable ASCII U+0021 to
 * U+007E with no `;` or `=`, at most 64 characters), so neither separator can occur inside a key or a value and
 * nothing is escaped: a cleaned map comes back exactly. [encode] leaves out an entry that is not clean rather than
 * write text it could not read back. [decode] never throws: text that is not exactly what [encode] writes (a damaged
 * column) reads as no ids. The ids are never logged, so nothing here names one in a message.
 */
internal object ProviderIdsCodec {
    private const val ENTRY_END = ';'
    private const val KEY_END = '='
    private const val MAX_KEY = 32
    private const val MAX_VALUE = 64

    fun encode(ids: Map<String, String>): String =
        ids.entries
            .filter { (key, value) -> validKey(key) && validValue(value) }
            .sortedBy { it.key }
            .joinToString(ENTRY_END.toString()) { "${it.key}$KEY_END${it.value}" }

    /** The map [text] holds, or an empty map when [text] is empty or is not what [encode] writes. */
    @Suppress("ReturnCount") // Each malformed shape gives up at once.
    fun decode(text: String): Map<String, String> {
        if (text.isEmpty()) return emptyMap()
        val ids = LinkedHashMap<String, String>()
        var previous: String? = null
        for (entry in text.split(ENTRY_END)) {
            val at = entry.indexOf(KEY_END)
            if (at < 0) return emptyMap()
            val key = entry.substring(0, at)
            val value = entry.substring(at + 1)
            if (!validKey(key) || !validValue(value)) return emptyMap()
            // Sorted and distinct, as encode writes them: anything else is not this codec's text.
            if (previous != null && key <= previous) return emptyMap()
            ids[key] = value
            previous = key
        }
        return ids
    }

    private fun validKey(key: String): Boolean =
        key.length in 1..MAX_KEY && key.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }

    private fun validValue(value: String): Boolean =
        value.length in 1..MAX_VALUE && value.all { it in '!'..'~' && it != ENTRY_END && it != KEY_END }
}
