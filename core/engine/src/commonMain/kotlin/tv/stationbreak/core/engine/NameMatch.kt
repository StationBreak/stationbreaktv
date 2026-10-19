// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

private const val CASE_GAP = 'a' - 'A'

/**
 * Folds the ASCII capitals `A` to `Z` to `a` to `z`, one UTF-16 code unit at a time, and changes nothing else
 * (docs/rules-format.md, "String matching"). It does not use `lowercase()`, which would fold `K` (the Kelvin sign) and
 * the Turkish dotted capital I and is locale-sensitive on some platforms. No trimming, no normalisation.
 *
 * Folding by code unit is the same as by code point here: every UTF-16 surrogate unit is outside `A` to `Z`, so a
 * pair passes through whole.
 */
internal fun foldAscii(text: String): String {
    var first = -1
    for (i in text.indices) {
        if (text[i] in 'A'..'Z') {
            first = i
            break
        }
    }
    if (first < 0) return text
    val out = text.toCharArray()
    for (i in first until out.size) {
        val ch = out[i]
        if (ch in 'A'..'Z') out[i] = ch + CASE_GAP
    }
    return out.concatToString()
}

/**
 * The names of a list of rules, folded once, each remembering the rules it came from: its position in [names], or the
 * matching entry of [labels] when the caller numbers the rules differently. [contains] folds the probe the same way, so
 * the match is ASCII case-insensitive and exact otherwise.
 */
internal class NameSet(
    names: List<String>,
    labels: IntArray = IntArray(names.size) { it },
) {
    private val byName: Map<String, IntArray>

    init {
        val building = HashMap<String, MutableList<Int>>()
        for (index in names.indices) {
            building.getOrPut(foldAscii(names[index])) { ArrayList(1) }.add(labels[index])
        }
        val frozen = HashMap<String, IntArray>(building.size * 2)
        for ((name, indices) in building) frozen[name] = indices.toIntArray()
        byName = frozen
    }

    val isEmpty: Boolean get() = byName.isEmpty()

    /** True if some name equals [name] after folding both. */
    fun contains(name: String): Boolean = byName.containsKey(foldAscii(name))

    /** The labels (ascending) of the names equal to [name], or null. */
    fun indicesOf(name: String): IntArray? = byName[foldAscii(name)]
}
