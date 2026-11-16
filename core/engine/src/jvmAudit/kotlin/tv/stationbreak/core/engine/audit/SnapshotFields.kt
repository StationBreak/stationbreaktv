// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A problem in the snapshot file. The message names the place and the rule, never the file's text. */
internal class SnapshotProblem(
    message: String,
) : Exception(message)

/** The longest text a field of the file may hold, and the most entries a list may hold. */
internal const val MAX_TEXT_CHARS = 1_024
internal const val MAX_LIST_ENTRIES = 1_024

/**
 * One object of the file, read strictly: a key is read by name, a missing required key or a value of the wrong type
 * is a [SnapshotProblem] naming [where], and [done] refuses any key that was not read. Lists, IDs and nested objects
 * are read by the extensions in `SnapshotLists.kt`.
 */
internal class SnapshotFields(
    val json: JsonObject,
    val where: String,
) {
    private val read = HashSet<String>()

    /** The value of [key], marked as read; null when the key is not there. */
    fun raw(key: String): JsonElement? {
        read.add(key)
        return json[key]
    }

    fun fail(
        key: String,
        what: String,
    ): Nothing = throw SnapshotProblem("$where.$key: $what")

    private fun primitive(
        key: String,
        needText: Boolean,
    ): JsonPrimitive? {
        val v = raw(key)
        return when {
            v == null || v is JsonNull -> null
            v is JsonPrimitive && v.isString == needText -> v
            else -> fail(key, if (needText) "is not text" else "is not a number or true/false")
        }
    }

    fun textOrNull(key: String): String? {
        val text = primitive(key, true)?.content
        if (text != null && text.length > MAX_TEXT_CHARS) fail(key, "is longer than $MAX_TEXT_CHARS characters")
        return text
    }

    fun text(key: String): String = textOrNull(key) ?: fail(key, "is required")

    fun longOrNull(key: String): Long? {
        val p = primitive(key, false) ?: return null
        return p.longOrNull ?: fail(key, "is not a whole number")
    }

    fun long(key: String): Long = longOrNull(key) ?: fail(key, "is required")

    fun intOrNull(key: String): Int? {
        val n = longOrNull(key) ?: return null
        return if (n in Int.MIN_VALUE..Int.MAX_VALUE) n.toInt() else fail(key, "is out of range")
    }

    fun bool(key: String): Boolean {
        val p = primitive(key, false) ?: fail(key, "is required")
        return p.booleanOrNull ?: fail(key, "is not true or false")
    }

    /** Refuses a key the reader never asked for: a misspelled or newer field is not silently ignored. */
    fun done() {
        val extra = json.keys.firstOrNull { it !in read }
        if (extra != null) throw SnapshotProblem("$where has a key this version does not know")
    }
}
