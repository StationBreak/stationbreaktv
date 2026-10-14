// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * Masks credentials in free text before it reaches a log (F0.4, S0.4.1). One function over a string, pure common
 * Kotlin so it runs on every target. It matches by name, case-insensitively, in four forms:
 *  - a query parameter: `?api_key=VALUE&`
 *  - a header line: `Authorization: VALUE` (the whole rest of the line goes)
 *  - a quoted pair inside an authorization header: `MediaBrowser Token="VALUE"`
 *  - a JSON pair: `"password" : "VALUE"` (a string or a bare value)
 *
 * It errs towards over-masking: a name followed by `=` or `:` is masked wherever it appears. Redacting twice gives
 * the same text as redacting once.
 */
object Redactor {
    const val MASK = "<redacted>"

    // Longest first, so `X-Emby-Authorization` wins over `Authorization` and `AccessToken` over `Token`.
    private const val NAMES =
        "x-emby-authorization|x-emby-token|authorization|accesstoken|api_key|apikey|password|secret|token|pw"

    private val json =
        Regex("(\"(?:$NAMES)\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]\\s\"][^,}\\]\\s]*)", RegexOption.IGNORE_CASE)

    private val quotedPair = Regex("((?:$NAMES)\\s*=\\s*\")[^\"]*(\")", RegexOption.IGNORE_CASE)

    private val headerLine = Regex("((?:$NAMES)[ \\t]*:[ \\t]*)[^\\r\\n]*", RegexOption.IGNORE_CASE)

    private val query = Regex("((?:$NAMES)=)[^&\\s\"',;#]+", RegexOption.IGNORE_CASE)

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = json.replace(out) { it.groupValues[1] + quoteIfString(it.groupValues[2]) }
        out = quotedPair.replace(out) { it.groupValues[1] + MASK + it.groupValues[2] }
        out = headerLine.replace(out) { it.groupValues[1] + MASK }
        out = query.replace(out) { it.groupValues[1] + MASK }
        return out
    }

    private fun quoteIfString(value: String): String = if (value.startsWith("\"")) "\"$MASK\"" else MASK
}
