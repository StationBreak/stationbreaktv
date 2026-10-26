// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

/**
 * Makes free text from the server safe to draw (it is untrusted input). Markup is removed (`<br>` becomes a new line,
 * every other tag is dropped, entities are decoded once), then control characters other than the new line and the
 * Unicode direction controls (embeddings, overrides and isolates, which can reorder what the viewer reads) are removed,
 * and the result is trimmed and capped. Decoding comes before the control strip so an entity can't smuggle one in.
 */
internal object ServerText {
    const val OVERVIEW_MAX = 1_500
    const val GENRES_MAX = 5
    const val GENRE_MAX = 40

    private val lineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]*>")
    private val entity = Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});")

    private val named =
        mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    /** The overview as safe text, or null when nothing readable is left. */
    fun overview(raw: String?): String? = raw?.let { clean(it, keepNewlines = true, max = OVERVIEW_MAX) }

    /** At most [GENRES_MAX] genres, each safe, single-line and at most [GENRE_MAX] characters; blanks dropped. */
    fun genres(raw: List<String>?): List<String> =
        raw.orEmpty().mapNotNull { clean(it, keepNewlines = false, max = GENRE_MAX) }.take(GENRES_MAX)

    private fun clean(
        raw: String,
        keepNewlines: Boolean,
        max: Int,
    ): String? {
        val text = decode(raw.replace(lineBreak, "\n").replace(tag, ""))
        val kept = text.filter { allowed(it, keepNewlines) }.trim()
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

    private fun allowed(
        c: Char,
        keepNewlines: Boolean,
    ): Boolean =
        when {
            c == '\n' -> keepNewlines
            c < ' ' || c in '\u007F'..'\u009F' -> false
            c in '‪'..'‮' || c in '⁦'..'⁩' -> false
            else -> true
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
    private val SURROGATES = 0xD800..0xDFFF
}
