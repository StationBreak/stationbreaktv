// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui.mark

import java.util.Locale

private val StopWords = setOf("the", "a", "an", "of", "in", "and")
private const val FALLBACK = "?"

/**
 * Two-character initials for a channel called [name] (ChannelMarks "Initials rules"), as the guide draws them where a
 * channel has no logo. Two or more significant words give the first letters of the first and the last; one word gives
 * its first two letters. Never throws; an empty name gives "?"; the result is never more than two code points. The
 * guide also resolves clashes inside a band of numbers ([markFallbackInitialsOf]); a lone mark, as on live TV's
 * banner, has no neighbours to clash with.
 */
fun markInitialsOf(name: String): String = byRules(significantWords(name))

/**
 * The first word's initials alone, for a channel whose [markInitialsOf] another channel in its band already has; null
 * when [name] has fewer than two significant words (there is nothing shorter to fall back to).
 */
fun markFallbackInitialsOf(name: String): String? {
    val words = significantWords(name)
    return if (words.size > 1) singleWord(words.first()) else null
}

private fun byRules(words: List<String>): String =
    when (words.size) {
        0 -> FALLBACK
        1 -> singleWord(words[0])
        else -> upperFirst(words.first()) + upperFirst(words.last())
    }

/** Rule 3: a capital and the next letter in lower case. A one-character word gives one character. */
private fun singleWord(word: String): String {
    val first = upperFirst(word)
    val secondStart = word.offsetByCodePoints(0, 1)
    if (secondStart >= word.length) return first
    val second = word.substring(secondStart, word.offsetByCodePoints(secondStart, 1))
    return first + oneCodePoint(second.lowercase(Locale.ROOT), second)
}

private fun upperFirst(word: String): String {
    val first = word.substring(0, word.offsetByCodePoints(0, 1))
    return oneCodePoint(first.uppercase(Locale.ROOT), first)
}

/** Case mapping can turn one code point into several (a German sharp s); keep the original then. */
private fun oneCodePoint(
    mapped: String,
    original: String,
): String = if (mapped.codePointCount(0, mapped.length) == 1) mapped else original

/** The words of [name] without stop words, punctuation or apostrophes; hyphens and other punctuation split words. */
private fun significantWords(name: String): List<String> {
    val words = ArrayList<String>()
    val current = StringBuilder()
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == '\''.code || cp == '’'.code -> Unit
            isWordPart(cp) -> current.appendCodePoint(cp)
            else -> if (current.isNotEmpty()) words += current.toString().also { current.setLength(0) }
        }
    }
    if (current.isNotEmpty()) words += current.toString()
    return words.filter { it.lowercase(Locale.ROOT) !in StopWords }
}

private fun isWordPart(cp: Int): Boolean =
    Character.isLetterOrDigit(cp) ||
        when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK,
            Character.COMBINING_SPACING_MARK,
            Character.MATH_SYMBOL,
            Character.CURRENCY_SYMBOL,
            Character.MODIFIER_SYMBOL,
            Character.OTHER_SYMBOL,
            -> true

            else -> false
        }
