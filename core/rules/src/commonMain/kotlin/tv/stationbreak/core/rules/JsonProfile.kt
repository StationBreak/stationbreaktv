// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

/**
 * The JSON profile of a rules file: RFC 8259 narrowed by I-JSON (RFC 7493). This checks what a JSON parser would
 * silently accept: a byte-order mark, a repeated member name in one object, and a number or word that is not strict
 * JSON (`01`, `+5`, `NaN`, `Infinity`). Unpaired surrogates and invalid UTF-8 are checked elsewhere.
 */
internal object JsonProfile {
    private const val BYTE_ORDER_MARK = '\uFEFF'
    private const val NOT_JSON = "The file is not valid JSON"
    private val NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")
    private val WORDS = setOf("true", "false", "null")

    /** Null when [text] fits the profile, otherwise a plain reason. Text that is not JSON is left to the parser. */
    fun problem(text: String): String? =
        if (text.startsWith(BYTE_ORDER_MARK)) "The file starts with a byte-order mark" else Scan(text).run()

    private class Frame(
        val isObject: Boolean,
    ) {
        val names = HashSet<String>()
        var expectName = isObject
    }

    private class Scan(
        private val text: String,
    ) {
        private val frames = ArrayList<Frame>()
        private var pos = 0
        private var problem: String? = null

        fun run(): String? {
            while (pos < text.length && problem == null) step()
            return problem
        }

        private fun step() {
            val c = text[pos]
            when {
                c == '"' -> string()
                c == '{' || c == '[' -> open(c)
                c == '}' || c == ']' -> close()
                c == ',' -> comma()
                c == ':' || c == ' ' || c == '\t' || c == '\n' || c == '\r' -> pos++
                else -> literal()
            }
        }

        private fun open(c: Char) {
            frames.add(Frame(isObject = c == '{'))
            pos++
        }

        private fun close() {
            if (frames.isNotEmpty()) frames.removeAt(frames.lastIndex)
            pos++
        }

        private fun comma() {
            frames.lastOrNull()?.let { if (it.isObject) it.expectName = true }
            pos++
        }

        private fun string() {
            val decoded = StringBuilder()
            var i = pos + 1
            while (i < text.length && text[i] != '"') {
                if (text[i] == '\\') {
                    i += escape(i, decoded)
                } else {
                    decoded.append(text[i])
                    i++
                }
            }
            pos = i + 1
            val frame = frames.lastOrNull()
            if (frame != null && frame.isObject && frame.expectName) {
                frame.expectName = false
                if (!frame.names.add(decoded.toString())) problem = "The file repeats a member name"
            }
        }

        /** Appends the character an escape at [at] stands for and returns how many characters it takes. */
        private fun escape(
            at: Int,
            out: StringBuilder,
        ): Int {
            val next = text.getOrNull(at + 1)
            val code = if (next == 'u') unicode(at + 2) else null
            when {
                next == null -> out.append('\\')
                code != null -> out.append(code.toChar())
                else -> out.append(SIMPLE.getOrElse(next) { next })
            }
            return if (code != null) {
                UNICODE_LENGTH
            } else if (next == null) {
                1
            } else {
                2
            }
        }

        private fun unicode(from: Int): Int? {
            val digits = text.substring(from, minOf(text.length, from + HEX_DIGITS))
            val valid = digits.length == HEX_DIGITS && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            return if (valid) digits.toInt(HEX_RADIX) else null
        }

        private fun literal() {
            var end = pos
            while (end < text.length && text[end] !in DELIMITERS) end++
            val token = text.substring(pos, end)
            if (token !in WORDS && !NUMBER.matches(token)) problem = NOT_JSON
            pos = end
        }
    }

    private const val HEX_DIGITS = 4
    private const val HEX_RADIX = 16
    private const val UNICODE_LENGTH = 6
    private val SIMPLE = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', 'b' to '\b', 'f' to '\u000C')
    private val DELIMITERS = setOf(',', ':', '[', ']', '{', '}', '"', ' ', '\t', '\n', '\r')
}
