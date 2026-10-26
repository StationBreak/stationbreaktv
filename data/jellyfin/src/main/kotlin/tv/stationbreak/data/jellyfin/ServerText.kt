// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

/**
 * Makes free text from the server safe to draw (it is untrusted input). The input is cut to a multiple of the cap
 * first, so no later step does unbounded work. Then markup is removed (`<br>` becomes a new line, or a space in a
 * genre; every other tag is dropped; entities are decoded once), line and paragraph separators become new lines (or
 * go, in a genre), tab and a lone carriage return become a space, and a CRLF is one new line. Control characters and
 * Unicode format characters (direction controls, marks, the byte order mark...) other than the joiners ZWJ and ZWNJ
 * are removed, a run of combining marks is capped, and the result is trimmed and capped. Decoding comes before the
 * strip so an entity can't smuggle a character in.
 *
 * Code points are written as numbers, not `\u` escapes, because the formatter rewrites an escape into the raw
 * (invisible) character, which a reader can't see in the source.
 */
internal object ServerText {
    const val OVERVIEW_MAX = 1_500
    const val GENRES_MAX = 5
    const val GENRE_MAX = 40
    const val MARKS_MAX = 4

    // How much raw text is looked at before the cap: room for markup and entities, but bounded.
    private const val INPUT_FACTOR = 8

    private val lineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    // No '<' inside, so a run of them is matched in one pass and the scan stays linear.
    private val tag = Regex("<[^<>]*>")
    private val entity = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});")

    private val named =
        mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    /** The overview as safe text, or null when nothing readable is left. */
    fun overview(raw: String?): String? {
        val bounded = raw?.take(OVERVIEW_MAX * INPUT_FACTOR) ?: return null
        return clean(bounded, multiline = true, max = OVERVIEW_MAX)
    }

    /** At most [GENRES_MAX] genres, each safe, single-line and at most [GENRE_MAX] characters; blanks dropped. */
    fun genres(raw: List<String>?): List<String> =
        raw
            .orEmpty()
            .asSequence()
            .mapNotNull { clean(it.take(GENRE_MAX * INPUT_FACTOR), multiline = false, max = GENRE_MAX) }
            .take(GENRES_MAX)
            .toList()

    private fun clean(
        raw: String,
        multiline: Boolean,
        max: Int,
    ): String? {
        val unmarked = raw.replace(lineBreak, if (multiline) "\n" else " ").replace(tag, "")
        val text = decode(unmarked).replace("\r\n", "\n")
        val kept = limitMarks(text.mapNotNull { map(it, multiline) }.joinToString("")).trim()
        return capped(kept, max).trimEnd().takeIf { it.isNotEmpty() }
    }

    private fun decode(text: String): String =
        entity.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.drop(2).toIntOrNull(HEX))
                body.startsWith("#") -> codePoint(body.drop(1).toIntOrNull())
                else -> named[body.lowercase()] ?: match.value
            }
        }

    private fun codePoint(value: Int?): String =
        if (value != null && value in 0..Character.MAX_CODE_POINT && value !in SURROGATES) {
            String(Character.toChars(value))
        } else {
            ""
        }

    /** What [c] becomes: itself, a space, a new line, or nothing. */
    private fun map(
        c: Char,
        multiline: Boolean,
    ): String? =
        when {
            c == '\n' || c.code == LINE_SEPARATOR || c.code == PARAGRAPH_SEPARATOR -> if (multiline) "\n" else null
            c == '\t' || c == '\r' -> " "
            c < ' ' || c.code in DELETE..LAST_C1 -> null
            isBidi(c.code) -> null
            Character.getType(c) == Character.FORMAT.toInt() && c.code != ZWNJ && c.code != ZWJ -> null
            else -> c.toString()
        }

    // Embeddings and overrides (U+202A to U+202E) and isolates (U+2066 to U+2069).
    private fun isBidi(code: Int): Boolean = code in EMBEDDINGS || code in ISOLATES

    /** Keeps at most [MARKS_MAX] combining marks in a row, so a stack of them can't grow a line without bound. */
    private fun limitMarks(text: String): String {
        val out = StringBuilder(text.length)
        var run = 0
        for (c in text) {
            run = if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) run + 1 else 0
            if (run <= MARKS_MAX) out.append(c)
        }
        return out.toString()
    }

    /** The first [max] characters, never ending in half of a surrogate pair. */
    private fun capped(
        text: String,
        max: Int,
    ): String {
        if (text.length <= max) return text
        val cut = if (text[max - 1].isHighSurrogate()) max - 1 else max
        return text.substring(0, cut)
    }

    private const val HEX = 16
    private const val DELETE = 0x7F
    private const val LAST_C1 = 0x9F
    private const val LINE_SEPARATOR = 0x2028
    private const val PARAGRAPH_SEPARATOR = 0x2029
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D
    private val EMBEDDINGS = 0x202A..0x202E
    private val ISOLATES = 0x2066..0x2069
    private val SURROGATES = 0xD800..0xDFFF
}
