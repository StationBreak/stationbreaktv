// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import java.util.Locale

/** One flat JSON object on one line. Values are strings, numbers, booleans or null; nothing else is accepted. */
internal fun jsonObject(vararg fields: Pair<String, Any?>): String =
    fields.joinToString(",", "{", "}") { (name, value) ->
        val text =
            when (value) {
                null -> "null"
                is String -> quote(value)
                is Boolean, is Number -> value.toString()
                else -> error("unsupported JSON value")
            }
        "${quote(name)}:$text"
    }

private fun quote(text: String): String =
    buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' -> append("\\u%04x".format(Locale.ROOT, c.code))
                else -> append(c)
            }
        }
        append('"')
    }
