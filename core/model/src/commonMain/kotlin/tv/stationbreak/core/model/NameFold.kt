// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

private const val CASE_GAP = 'a' - 'A'

/**
 * Folds the ASCII capitals `A` to `Z` to `a` to `z`, one UTF-16 code unit at a time, and changes nothing else. The
 * single definition of the fold is docs/rules-format.md, "String matching"; this is its one implementation (the rule
 * engine's `foldAscii` delegates here). It does not use `lowercase()`, which would fold `K` (the Kelvin sign) and
 * the Turkish dotted capital I and is locale-sensitive on some platforms. No trimming, no normalisation.
 *
 * Folding by code unit is the same as by code point here: every UTF-16 surrogate unit is outside `A` to `Z`, so a
 * pair passes through whole. A text with no capital is returned as it is (the same instance).
 */
fun foldNameAscii(text: String): String {
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

/** [names] folded by [foldNameAscii], in order; the same list instance when no name has a capital. */
internal fun foldNames(names: List<String>): List<String> {
    var capitals = false
    for (i in names.indices) {
        val name = names[i]
        for (j in name.indices) {
            if (name[j] in 'A'..'Z') {
                capitals = true
                break
            }
        }
        if (capitals) break
    }
    return if (capitals) names.map(::foldNameAscii) else names
}

/**
 * The folded names of one snapshot, shared: equal name lists give one folded list, and equal folded names are one
 * `String`, so the folds cost memory by the number of distinct names and lists, not by the number of items. One is made
 * when a snapshot is mapped and handed to every [RuleItem.primeFolds] and [RuleSeries.primeFolds]. Not thread-safe;
 * the mapping is single-threaded.
 */
class NameFolds {
    private val lists = HashMap<List<String>, List<String>>()
    private val names = HashMap<String, String>()

    /** The number of distinct name lists that needed a fold, for telling how much sharing there is. */
    val distinctLists: Int get() = lists.size

    /** The number of distinct folded names held. */
    val distinctNames: Int get() = names.size

    /** [list] folded; [list] itself, with no table entry, when no name in it has a capital. */
    fun of(list: List<String>): List<String> = lists[list] ?: share(list)

    private fun share(list: List<String>): List<String> {
        val folded = foldNames(list)
        if (folded === list) return list
        val shared = folded.map { names.getOrPut(it) { it } }
        lists[list] = shared
        return shared
    }
}
