// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * Masks credentials in free text before it reaches a log (F0.4, S0.4.1). One function over a string, pure common
 * Kotlin so it runs on every target. It matches by name, case-insensitively, in these forms:
 *  - a query parameter or pair: `?api_key=VALUE&`, `Token='VALUE'`, `Token="VALUE"`, `Token=\"VALUE\"`
 *  - a header line: `Authorization: VALUE` (the whole rest of the line goes)
 *  - a JSON pair, plain or escaped (`{\"k\":\"v\"}`), with a string or bare value; a string with no closing quote
 *    is masked to the end of the input. The JSON key may contain a name anywhere (`NewPw`, `access_token`).
 *  - `Bearer VALUE`, wherever it appears
 * The name `api_key` also matches `api%5Fkey`, and the `=` separator also matches `%3D`.
 *
 * The `=` form of `Authorization` and `X-Emby-Authorization` masks to the next `,`, `}`, `&`, quote or line end,
 * because its value has spaces (`Bearer x`). Any other unquoted value is masked to the next space, so `api_key=a b`
 * leaves ` b` behind.
 *
 * It errs towards over-masking, the safe failure: a name directly followed by `=` or `:` is masked wherever it appears,
 * even inside a longer word (`Upw=1` is masked), while a name inside a longer word that isn't followed by a
 * separator (`secretary: x`, `MaxTokenCount: 5`) is left alone. Every pattern is linear in the input: there are no
 * nested unbounded repeats. Redacting twice gives the same text as redacting once.
 */
object Redactor {
    const val MASK = "<redacted>"

    // Names, longest first so `X-Emby-Authorization` wins over `Authorization` and `AccessToken` over `Token`.
    private const val AUTH = "x-emby-authorization|authorization"
    private const val NAMES = "$AUTH|x-emby-token|accesstoken|api(?:_|%5F)key|apikey|password|secret|token|pw"

    private const val END = "$"
    private const val PLAIN = "\"(?:[^\"\\\\]+|\\\\.)*(?:\"|$END)"
    private const val ESCAPED = "\\\\\"(?:[^\"\\\\]+|\\\\(?!\"))*(?:\\\\\"|$END)"
    private const val SINGLE = "'[^']*(?:'|$END)"
    private const val BARE = "[^,}\\]\\s\"\\\\][^,}\\]\\s]*"

    private const val QUOTED_SEP = "(?:\\s*=\\s*|%3D)"
    private const val SEP = "(?:=|%3D)"
    private const val NOT_QUOTE = "(?![\"'\\\\\\s])"

    private val json =
        Regex(
            "(\\\\?\"[^\"\\\\]*(?:$NAMES)[^\"\\\\]*\\\\?\"\\s*:\\s*)(?:($PLAIN)|($ESCAPED)|($BARE))",
            RegexOption.IGNORE_CASE,
        )

    private val quotedPair =
        Regex("((?:$NAMES)$QUOTED_SEP)(?:($PLAIN)|($ESCAPED)|($SINGLE))", RegexOption.IGNORE_CASE)

    private val authorizationPair = Regex("((?:$AUTH)$SEP)$NOT_QUOTE[^,}&\"\\r\\n]*", RegexOption.IGNORE_CASE)

    private val query = Regex("((?:$NAMES)$SEP)$NOT_QUOTE[^&\\s\"',;#}\\\\]+", RegexOption.IGNORE_CASE)

    private val headerLine = Regex("((?:$NAMES)[ \\t]*:[ \\t]*)[^\\r\\n]*", RegexOption.IGNORE_CASE)

    private val bearer = Regex("\\bBearer\\s+\\S+", RegexOption.IGNORE_CASE)

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        out = json.replace(out) { it.groupValues[1] + maskQuoted(it, 2) }
        out = quotedPair.replace(out) { it.groupValues[1] + maskQuoted(it, 2) }
        out = authorizationPair.replace(out) { it.groupValues[1] + MASK }
        out = query.replace(out) { it.groupValues[1] + MASK }
        out = headerLine.replace(out) { it.groupValues[1] + MASK }
        out = bearer.replace(out) { "Bearer $MASK" }
        return out
    }

    /** The mask in the same quoting as the value it replaces: group [first] plain, +1 escaped, +2 single or bare. */
    private fun maskQuoted(
        match: MatchResult,
        first: Int,
    ): String {
        val groups = match.groupValues
        return when {
            groups[first].isNotEmpty() -> "\"$MASK\""
            groups[first + 1].isNotEmpty() -> "\\\"$MASK\\\""
            groups[first + 2].startsWith("'") -> "'$MASK'"
            else -> MASK
        }
    }
}
