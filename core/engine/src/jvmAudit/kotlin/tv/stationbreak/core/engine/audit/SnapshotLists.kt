// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.CanonicalId

// The ID, list and nested-object reads of [SnapshotFields]: the same strictness, in a file of their own.

internal fun SnapshotFields.idOrNull(key: String): CanonicalId? =
    textOrNull(key)?.let { CanonicalId.parseCanonicalOrNull(it) ?: fail(key, "is not a canonical ID") }

internal fun SnapshotFields.id(key: String): CanonicalId = idOrNull(key) ?: fail(key, "is required")

internal fun SnapshotFields.array(key: String): JsonArray {
    val v = raw(key)
    return v as? JsonArray ?: fail(key, "is not a list (or is missing)")
}

/** The objects of the list [key], each read as its own [SnapshotFields]; at most [limit] of them. */
internal fun SnapshotFields.objects(
    key: String,
    limit: Int,
): List<SnapshotFields> {
    val list = array(key)
    if (list.size > limit) fail(key, "has more than $limit entries")
    return list.mapIndexed { i, e ->
        SnapshotFields(e as? JsonObject ?: fail("$key[$i]", "is not an object"), "$where.$key[$i]")
    }
}

/** The nested object [key], or null when the key is absent or null. */
internal fun SnapshotFields.objOrNull(key: String): SnapshotFields? {
    val v = raw(key)
    return if (v == null || v is JsonNull) {
        null
    } else {
        SnapshotFields(v as? JsonObject ?: fail(key, "is not an object"), "$where.$key")
    }
}

/** The nested object [key] as plain JSON (the rules of a custom channel are read by the rules reader). */
internal fun SnapshotFields.plainObject(key: String): JsonObject {
    val v = raw(key)
    return v as? JsonObject ?: fail(key, "is not an object")
}

private fun SnapshotFields.strings(key: String): List<String> {
    val list = array(key)
    if (list.size > MAX_LIST_ENTRIES) fail(key, "has more than $MAX_LIST_ENTRIES entries")
    return list.mapIndexed { i, e ->
        val p = e as? JsonPrimitive
        if (p == null || !p.isString) fail("$key[$i]", "is not text")
        p.content
    }
}

internal fun SnapshotFields.texts(key: String): List<String> =
    strings(key).onEachIndexed { i, text ->
        if (text.length > MAX_TEXT_CHARS) fail("$key[$i]", "is longer than $MAX_TEXT_CHARS characters")
    }

internal fun SnapshotFields.ids(key: String): List<CanonicalId> =
    array(key).mapIndexed { i, e ->
        val p = e as? JsonPrimitive
        val text = if (p != null && p.isString) p.content else fail("$key[$i]", "is not text")
        CanonicalId.parseCanonicalOrNull(text) ?: fail("$key[$i]", "is not a canonical ID")
    }
