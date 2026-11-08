// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.model.ChannelId
import java.util.Locale

/** The tile families of the ChannelMarks spec. [Custom] (600 and up) is not in the spec yet; it reads like General. */
internal enum class MarkBand { Kids, General, Drama, Genre, World, Premium, Custom }

private const val NUMBERS_PER_BAND = 100
private val NumberedBands = MarkBand.entries - MarkBand.Custom

/** The band a channel number falls in: a band per hundred from Kids (1 to 99) to Premium (500 to 599), then Custom. */
internal fun markBandOf(number: Int): MarkBand =
    if (number < 1) MarkBand.Kids else NumberedBands.getOrElse(number / NUMBERS_PER_BAND) { MarkBand.Custom }

/** What the initials rules need to know about a channel. */
internal data class MarkChannel(
    val id: ChannelId,
    val number: Int,
    val name: String,
)

private val StopWords = setOf("the", "a", "an", "of", "in", "and")
private const val FALLBACK = "?"

/**
 * Two-character initials for each channel (ChannelMarks "Initials rules"). Each band is processed in number order. Two
 * or more significant words give the first letters of the first and the last; one word gives its first two letters. A
 * channel whose initials are already taken in its band falls back to its first word alone; if that is taken too it is
 * kept as it is. Never throws; an empty name gives "?"; the result is never more than two code points.
 */
internal fun assignInitials(channels: List<MarkChannel>): Map<ChannelId, String> {
    val result = HashMap<ChannelId, String>(channels.size)
    channels
        .groupBy { markBandOf(it.number) }
        .values
        .forEach { band ->
            val taken = HashSet<String>()
            for (channel in band.sortedWith(compareBy<MarkChannel> { it.number }.thenBy { it.name })) {
                val words = significantWords(channel.name)
                var initials = byRules(words)
                if (initials in taken && words.size > 1) {
                    val single = singleWord(words.first())
                    initials = single
                }
                taken += initials
                result[channel.id] = initials
            }
        }
    return result
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
