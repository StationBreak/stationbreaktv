// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

/**
 * Helpers for the source-scanning architecture tests: comment stripping and the patterns they match. The patterns
 * run over a file's whole stripped text, not line by line, so a call or a parameter split across lines is still seen.
 */
object SourceScan {
    /**
     * [source] with every comment (line, block and KDoc, nested blocks included) replaced by a space or dropped, and
     * its line breaks kept. String, raw-string and character literals are copied whole, so a URL in a string is not
     * mistaken for a line comment, nor a slash-star in a string for a block comment.
     */
    fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            i =
                when {
                    source.startsWith("\"\"\"", i) -> {
                        copyRawString(source, i, out)
                    }

                    source[i] == '"' -> {
                        copyQuoted(source, i, '"', out)
                    }

                    source[i] == '\'' -> {
                        copyQuoted(source, i, '\'', out)
                    }

                    source.startsWith("//", i) -> {
                        skipLineComment(source, i)
                    }

                    source.startsWith("/*", i) -> {
                        skipBlockComment(source, i, out)
                    }

                    else -> {
                        out.append(source[i])
                        i + 1
                    }
                }
        }
        return out.toString()
    }

    private fun copyRawString(
        source: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        val close = source.indexOf("\"\"\"", start + 3)
        val end = if (close < 0) source.length else close + 3
        out.append(source, start, end)
        return end
    }

    // A "..." or '.' literal, with backslash escapes; it ends at its closing quote or, if unclosed, at the line end.
    private fun copyQuoted(
        source: String,
        start: Int,
        quote: Char,
        out: StringBuilder,
    ): Int {
        out.append(quote)
        var i = start + 1
        while (i < source.length && source[i] != '\n') {
            val c = source[i]
            out.append(c)
            i++
            when {
                c == '\\' && i < source.length && source[i] != '\n' -> {
                    out.append(source[i])
                    i++
                }

                c == quote -> {
                    return i
                }
            }
        }
        return i
    }

    private fun skipLineComment(
        source: String,
        start: Int,
    ): Int {
        val end = source.indexOf('\n', start)
        return if (end < 0) source.length else end
    }

    // Kotlin block comments nest; the line breaks inside are kept so the text keeps its shape.
    private fun skipBlockComment(
        source: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var depth = 0
        var i = start
        while (i < source.length) {
            when {
                source.startsWith("/*", i) -> {
                    depth++
                    i += 2
                }

                source.startsWith("*/", i) -> {
                    depth--
                    i += 2
                    if (depth == 0) break
                }

                else -> {
                    if (source[i] == '\n') out.append('\n')
                    i++
                }
            }
        }
        out.append(' ')
        return i
    }

    // Not a declaration (`fun name`): a call with or without a receiver, or a method reference, is a use.
    private fun useOf(names: String) = Regex("""(?<!\bfun )\b(?:$names)\b""")

    val selectionWrites: Regex = useOf("saveSelection|clearLibraries")
    val cacheRowWrites: Regex = useOf("applySelection|landAdd|revertUnproven")
    val daoRowWriters: Regex = useOf("applySelectionRows|deleteLeavers|clearStamp|revertStamp")
    val resumeAndGate: Regex = Regex("""\b(?:SelectionResume|ResumeGate)\b""")
    val appStart: Regex = Regex("""\bAppStart\b""")

    // A parameter typed as the store, plainly or behind `Lazy<>` / `Provider<>` (any package), over any lines.
    val storeParameter: Regex =
        Regex("""\b\w+\s*:\s*(?:(?:[\w.]+\.)?(?:Lazy|Provider)\s*<\s*)?SelectionStore\b""")
}
