// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

/**
 * Makes free text from the server safe to draw (it is untrusted input). The input is cut to a multiple of the cap
 * first, so no later step does unbounded work. Then markup is removed (`<br>`, `</p>`, `</div>`, `</li>` and `<li>`
 * become a new line, or a space in a genre; every other tag is dropped; entities are decoded once), line and
 * paragraph separators become new lines (or go, in a genre), tab and a lone carriage return become a space, and a
 * CRLF is one new line. Control characters and Unicode format characters (direction controls, marks, the byte order
 * mark...) other than the joiners ZWJ and ZWNJ are removed, as is any half of a surrogate pair, a run of combining
 * marks is capped, three or more new lines in a row become two, and the result is trimmed and capped. Text is walked
 * by code point, not UTF-16 unit. Decoding comes before the strip so an entity can't smuggle a character in.
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

    // A line break, or the edge of a block (end of a paragraph, division or list item, or the start of a list item).
    private val lineBreak = Regex("<br\\s*/?>|</(?:p|div|li)\\s*>|<li\\b[^<>]*>", RegexOption.IGNORE_CASE)
    private val blankRuns = Regex("\n{3,}")

    // No '<' inside, so a run of them is matched in one pass and the scan stays linear.
    private val tag = Regex("<[^<>]*>")
    private val entity = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});")

    private val named =
        mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    /** The overview as safe text, or null when nothing readable is left. */
    fun overview(raw: String?): String? {
        val bounded = bound(raw ?: return null, OVERVIEW_MAX)
        return clean(bounded, multiline = true, max = OVERVIEW_MAX)
    }

    /** At most [GENRES_MAX] genres, each safe, single-line and at most [GENRE_MAX] characters; blanks dropped. */
    fun genres(raw: List<String>?): List<String> =
        raw
            .orEmpty()
            .asSequence()
            .mapNotNull { clean(bound(it, GENRE_MAX), multiline = false, max = GENRE_MAX) }
            .take(GENRES_MAX)
            .toList()

    /** The raw text cut to room for markup around [max] characters; run before any regex, so their work is bounded. */
    internal fun bound(
        raw: String,
        max: Int,
    ): String = raw.take(max * INPUT_FACTOR)

    private fun clean(
        raw: String,
        multiline: Boolean,
        max: Int,
    ): String? {
        val unmarked = raw.replace(lineBreak, if (multiline) "\n" else " ").replace(tag, "")
        val text = decode(unmarked).replace("\r\n", "\n")
        val mapped = StringBuilder(text.length)
        text.codePoints().forEach { cp -> map(cp, multiline)?.let { mapped.append(it) } }
        val kept = limitMarks(mapped).replace(blankRuns, "\n\n").trim()
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

    /** What code point [cp] becomes: itself, a space, a new line, or nothing (also for half of a surrogate pair). */
    private fun map(
        cp: Int,
        multiline: Boolean,
    ): String? =
        when {
            cp in SURROGATES -> null
            cp == '\n'.code || cp == LINE_SEPARATOR || cp == PARAGRAPH_SEPARATOR -> if (multiline) "\n" else null
            cp == '\t'.code || cp == '\r'.code -> " "
            isInvisible(cp) -> null
            else -> String(Character.toChars(cp))
        }

    /**
     * True for a code point that is never kept in text from the server: a C0 or C1 control, a bidi control, or a
     * format character other than the joiners ZWJ and ZWNJ. (A surrogate is handled by the caller: it is a half, not
     * a character.) Shared with [studioName], so every kind of server text strips the same set.
     */
    internal fun isInvisible(cp: Int): Boolean =
        cp < ' '.code || cp in DELETE..LAST_C1 || isBidi(cp) ||
            (Character.getType(cp) == Character.FORMAT.toInt() && cp != ZWNJ && cp != ZWJ)

    // Embeddings and overrides (U+202A to U+202E) and isolates (U+2066 to U+2069).
    private fun isBidi(code: Int): Boolean = code in EMBEDDINGS || code in ISOLATES

    /** Keeps at most [MARKS_MAX] combining marks in a row, so a stack of them can't grow a line without bound. */
    private fun limitMarks(text: CharSequence): String {
        val out = StringBuilder(text.length)
        var run = 0
        text.codePoints().forEach { cp ->
            run = if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) run + 1 else 0
            if (run <= MARKS_MAX) out.appendCodePoint(cp)
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
    internal const val LINE_SEPARATOR = 0x2028
    internal const val PARAGRAPH_SEPARATOR = 0x2029
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D
    private val EMBEDDINGS = 0x202A..0x202E
    private val ISOLATES = 0x2066..0x2069
    internal val SURROGATES = 0xD800..0xDFFF
}

/**
 * A studio name as stored for matching: tab, CR, LF, U+2028 and U+2029 become a space; C0 and C1 controls, bidi
 * controls and other format characters (except ZWJ and ZWNJ) and lone surrogates are removed (the code-point rule
 * [ServerText] uses); then it is trimmed. Null when nothing is left. Nothing else is done: no markup removal, no
 * entity decoding, no cap on combining marks, no collapsing of spaces, and the caller has already dropped a name that
 * is too long. This is not the display rule: any future display of a studio name must still go through [ServerText]'s
 * full rule. The kid hold applies the same cleaning when it reads names (`cleanStudioName` in core/model), so names
 * cached before this existed match the same way.
 */
internal fun studioName(raw: String): String? {
    val kept = StringBuilder(raw.length)
    raw.codePoints().forEach { cp ->
        when {
            cp in ServerText.SURROGATES -> Unit
            isLineOrTab(cp) -> kept.append(" ")
            !ServerText.isInvisible(cp) -> kept.appendCodePoint(cp)
        }
    }
    return kept.toString().trim().takeIf { it.isNotEmpty() }
}

private fun isLineOrTab(cp: Int) =
    cp == '\t'.code || cp == '\r'.code || cp == '\n'.code ||
        cp == ServerText.LINE_SEPARATOR || cp == ServerText.PARAGRAPH_SEPARATOR
