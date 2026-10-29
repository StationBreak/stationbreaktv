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
    private val studioLists = HashMap<List<String>, List<String>>()
    private val names = HashMap<String, String>()

    /** The number of distinct name lists that needed a fold, for telling how much sharing there is. */
    val distinctLists: Int get() = lists.size

    /** The number of distinct folded names held. */
    val distinctNames: Int get() = names.size

    /** [list] of studio names cleaned ([cleanStudioName]) and folded, shared like [of]. */
    fun ofStudios(list: List<String>): List<String> {
        val cleaned = studioLists[list] ?: foldStudioNames(list).map { names.getOrPut(it) { it } }
        studioLists[list] = cleaned
        return cleaned
    }

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

private const val LAST_C0 = 0x1F
private const val DELETE = 0x7F
private const val LAST_C1 = 0x9F
private const val LINE_SEPARATOR = 0x2028
private const val PARAGRAPH_SEPARATOR = 0x2029
private const val ZWNJ = 0x200C
private const val ZWJ = 0x200D
private const val FIRST_SUPPLEMENTARY = 0x10000
private const val HIGH_BASE = 0xD800
private const val LOW_BASE = 0xDC00
private const val SURROGATE_BITS = 10

// The format characters outside the Basic Multilingual Plane (Unicode 15): shorthand-format and musical-symbol
// controls and the tag characters. Named by range because common code has no code-point category lookup.
private val SUPPLEMENTARY_FORMAT = listOf(0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0001..0xE0001, 0xE0020..0xE007F)

/**
 * A studio name as the sync now stores it, for names cached before that cleaning existed, so every TV matches the same
 * text whichever version wrote its cache. Removes C0 and C1 controls, format characters other than ZWJ and ZWNJ
 * (direction controls, the zero-width space, the byte order mark...) and a lone half of a surrogate pair; tab, CR, LF,
 * U+2028 and U+2029 become a space; then trims. Nothing else is collapsed. Idempotent, so a name cleaned at sync time
 * comes through unchanged.
 */
fun cleanStudioName(text: String): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        val next = text.getOrNull(i + 1)
        if (ch.isHighSurrogate() && next != null && next.isLowSurrogate()) {
            val cp = FIRST_SUPPLEMENTARY + ((ch.code - HIGH_BASE) shl SURROGATE_BITS) + (next.code - LOW_BASE)
            if (SUPPLEMENTARY_FORMAT.none { cp in it }) out.append(ch).append(next)
            i += 2
            continue
        }
        when {
            ch.isSurrogate() -> Unit
            isSpaced(ch.code) -> out.append(' ')
            !isStripped(ch) -> out.append(ch)
        }
        i++
    }
    return out.toString().trim()
}

private fun isSpaced(code: Int) =
    code == '\t'.code || code == '\r'.code || code == '\n'.code || code == LINE_SEPARATOR || code == PARAGRAPH_SEPARATOR

private fun isStripped(ch: Char): Boolean {
    val code = ch.code
    return code <= LAST_C0 || code in DELETE..LAST_C1 ||
        (ch.category == CharCategory.FORMAT && code != ZWNJ && code != ZWJ)
}

/** [names] cleaned ([cleanStudioName]) then folded ([foldNameAscii]), in order; a name left empty is dropped. */
internal fun foldStudioNames(names: List<String>): List<String> =
    names.mapNotNull { name -> cleanStudioName(name).takeIf { it.isNotEmpty() }?.let(::foldNameAscii) }
