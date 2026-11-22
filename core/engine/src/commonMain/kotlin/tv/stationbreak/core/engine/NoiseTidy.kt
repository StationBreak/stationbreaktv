// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * Input noise removed before a rating, genre or tag is matched (#1088). Runs of spaces are one, the ends lose spaces,
 * and the dash variants (U+2010 to U+2015, U+2212, U+FE58, U+FE63, U+FF0D) are `-`. With `anySpace` every Unicode space
 * separator, tab, CR and LF counts as a space too; a rating is tidied without it, because the frozen `kid-hold-5`
 * vectors pin a tab or a no-break space in a rating as present and off the ladder. Case is untouched. A text with no
 * noise is returned as it is (the same instance). Fixed code point lists, as `cleanStudioName`'s, so every TV reads
 * the same.
 */
internal object NoiseTidy {
    // The white space of genres and tags, each range a character as a Kotlin escape.
    private val SPACES: List<CharRange> =
        listOf(
            '\t'..'\t',
            '\n'..'\n',
            '\r'..'\r',
            ' '..' ', // no-break space
            ' '..' ', // Ogham space mark
            ' '..' ', // en quad to hair space
            ' '..' ', // narrow no-break space
            ' '..' ', // medium mathematical space
            '　'..'　', // ideographic space
        )

    // The dash variants, each range a character as a Kotlin escape.
    private val DASHES: List<CharRange> =
        listOf(
            '‐'..'―', // hyphen, non-breaking hyphen, figure dash, en dash, em dash, horizontal bar
            '−'..'−', // minus sign
            '﹘'..'﹘', // small em dash
            '﹣'..'﹣', // small hyphen-minus
            '－'..'－', // fullwidth hyphen-minus
        )

    fun tidy(
        text: String,
        anySpace: Boolean = true,
    ): String {
        if (isTidy(text)) return text
        val out = StringBuilder(text.length)
        for (c in text) {
            val mapped = mapped(c, anySpace)
            if (mapped != ' ' || out.lastOrNull().let { it != null && it != ' ' }) out.append(mapped)
        }
        return out.toString().trimEnd(' ')
    }

    private fun mapped(
        c: Char,
        anySpace: Boolean,
    ): Char =
        when {
            c == ' ' || (anySpace && SPACES.any { c in it }) -> ' '
            DASHES.any { c in it } -> '-'
            else -> c
        }

    // Printable ASCII, no space at either end and no two in a row: nothing to remove.
    private fun isTidy(text: String): Boolean =
        !text.startsWith(' ') &&
            !text.endsWith(' ') &&
            text.indices.none { text[it] !in ' '..'~' || (text[it] == ' ' && text[it - 1] == ' ') }
}
