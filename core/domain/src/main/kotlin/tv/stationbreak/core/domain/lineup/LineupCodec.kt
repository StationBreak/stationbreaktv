// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.RulesReader
import tv.stationbreak.core.rules.RulesResult
import tv.stationbreak.core.rules.RulesWriter

/**
 * The stored form of a [Lineup] (spec 12): compact JSON with `"version": 1`. Only `core:domain` reads or writes it.
 *
 * **Any** change to the schema, adding a field or an enum value included, bumps [VERSION]. So an older app sees
 * [Decoded.NewerVersion] for a newer file and never saves over fields it doesn't know, and a version-1 file with an
 * unknown key or an unknown `state` or `kind` value is [Decoded.Unreadable] (set aside, never silently trimmed). The
 * rules of a starter or custom channel are a rules-format file held as a JSON string, so they keep their own format
 * (including its fields this app does not know) exactly.
 *
 * One bad channel never discards the rest: when a channel's `source` cannot be used (its rules fail [RulesReader] or
 * need a newer app, a leftovers ceiling is not on the rating ladder, a field is missing or of the wrong type), that
 * channel decodes as [ChannelSource.Unusable] holding the source's JSON, and keeps its ID, numbers, name, state and
 * time. Encoding writes that JSON back unchanged, so `encode(decode(x))` keeps it byte for byte. What the channel's
 * own fields or the lineup's fields get wrong (a missing field, a wrong type, an ID that is not canonical, two channels
 * at one number) makes the whole file unreadable, because nothing safe could be kept of it.
 *
 * A channel `source` nested deeper than 32 levels is [ChannelSource.Unusable] without being re-encoded (its JSON is not
 * kept: a source that deep is not one this app or an older one wrote).
 *
 * Beside the lineup the file may hold a `meta` object, the refresher's bookkeeping about the lineup ([RefreshMeta]).
 * Version 1 gained it before any release shipped, so it is an optional key of version 1 and not a new version: a file
 * without it loads with an empty [RefreshMeta], and an unknown key inside it is [Decoded.Unreadable] like any other.
 *
 * Decoding never throws.
 */
internal object LineupCodec {
    /** The schema version this app reads and writes. */
    const val VERSION = 1

    /** What [decode] found. */
    sealed interface Decoded {
        /** A version-[VERSION] lineup, perhaps with [ChannelSource.Unusable] channels, and its [meta]. */
        data class Loaded(
            val lineup: Lineup,
            val meta: RefreshMeta = RefreshMeta(),
        ) : Decoded

        /** Not a lineup this version can read. The caller sets it aside before writing another. */
        data object Unreadable : Decoded

        /** A lineup from a newer app. It must never be written over. */
        data class NewerVersion(
            val version: Int,
        ) : Decoded
    }

    private val json = Json

    /** The bytes to store for [lineup] and its [meta]. */
    fun encode(
        lineup: Lineup,
        meta: RefreshMeta = RefreshMeta(),
    ): ByteArray {
        val file =
            FileV1(
                version = VERSION,
                serverId = lineup.serverId.value,
                userId = lineup.userId.value,
                catalogVersion = lineup.catalogVersion,
                utcOffsetMinutes = lineup.utcOffsetMinutes,
                finalised = lineup.finalised,
                channels = lineup.channels.map(::channelOf),
                meta = MetaV1.of(meta),
            )
        return json.encodeToString(FileV1.serializer(), file).encodeToByteArray()
    }

    /** What [bytes] hold. Never throws: whatever else goes wrong, even a stack overflow, is [Decoded.Unreadable]. */
    @Suppress("TooGenericExceptionCaught")
    fun decode(bytes: ByteArray): Decoded =
        try {
            val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
            when (val version = versionOf(root)) {
                null -> Decoded.Unreadable
                VERSION -> lineupOf(json.decodeFromJsonElement(FileV1.serializer(), root))
                else -> Decoded.NewerVersion(version)
            }
        } catch (_: IllegalArgumentException) {
            // SerializationException is one: bad JSON, an unknown key or enum value, a missing field, a wrong type.
            Decoded.Unreadable
        } catch (_: CharacterCodingException) {
            Decoded.Unreadable
        } catch (_: StackOverflowError) {
            Decoded.Unreadable
        } catch (_: Exception) {
            Decoded.Unreadable
        }

    /** [source] as the file holds it (the `source` of a channel): what the days' key ([LineupDays.inputsOf]) hashes. */
    fun sourceOf(source: ChannelSource): JsonElement = SourceCodec.encode(source)

    // The version as a whole number of 1 or more (a too-big one is newer than any app), or null.
    private fun versionOf(root: JsonElement): Int? {
        val primitive = (root as? JsonObject)?.get("version") as? JsonPrimitive
        val digits = primitive?.takeIf { !it.isString && WHOLE.matches(it.content) }?.content ?: return null
        val value = digits.toIntOrNull() ?: if (digits.startsWith("-")) 0 else Int.MAX_VALUE
        return value.takeIf { it >= 1 }
    }

    private fun lineupOf(file: FileV1): Decoded {
        val server = CanonicalId.parseCanonicalOrNull(file.serverId)
        val user = CanonicalId.parseCanonicalOrNull(file.userId)
        if (server == null || user == null || !validOffset(file.utcOffsetMinutes)) return Decoded.Unreadable
        val channels = file.channels.map(::channelOf)
        val lineup = Lineup.ofOrNull(server, user, file.catalogVersion, file.utcOffsetMinutes, file.finalised, channels)
        val meta = file.meta?.refreshMeta ?: RefreshMeta()
        return if (lineup != null) Decoded.Loaded(lineup, meta) else Decoded.Unreadable
    }

    private fun validOffset(minutes: Int): Boolean =
        try {
            BroadcastDay(minutes, null)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun channelOf(channel: LineupChannel) =
        ChannelV1(
            id = channel.id.value,
            defaultNumber = channel.defaultNumber,
            numberOverride = channel.numberOverride,
            nameOverride = channel.nameOverride,
            state = StateV1.of(channel.state),
            onSinceMs = channel.onSinceMs,
            source = SourceCodec.encode(channel.source),
        )

    // Throws IllegalArgumentException for a blank ID; decode turns that into Unreadable.
    private fun channelOf(channel: ChannelV1) =
        LineupChannel(
            id = ChannelId(channel.id),
            defaultNumber = channel.defaultNumber,
            numberOverride = channel.numberOverride,
            nameOverride = channel.nameOverride,
            state = channel.state.state,
            source = SourceCodec.decode(channel.source),
            onSinceMs = channel.onSinceMs,
        )

    private val WHOLE = Regex("^-?(0|[1-9][0-9]*)$")
}

/**
 * A channel's `source`. Unknown keys and an unknown `kind` throw [IllegalArgumentException] (the file is
 * unreadable); anything else wrong gives [ChannelSource.Unusable].
 */
private object SourceCodec {
    private const val KIND = "kind"
    private const val KEY = "recipeKey"
    private const val EDITED = "edited"
    private const val RULES = "rules"
    private const val MAX_RATING = "maxRating"
    private const val MAX_DEPTH = 32

    private val keys =
        mapOf(
            "starter" to setOf(KIND, KEY, EDITED, RULES),
            "leftovers" to setOf(KIND, KEY, MAX_RATING),
            "custom" to setOf(KIND, RULES),
        )

    fun encode(source: ChannelSource): JsonElement =
        when (source) {
            is ChannelSource.Starter -> {
                JsonObject(
                    linkedMapOf(
                        KIND to JsonPrimitive("starter"),
                        KEY to JsonPrimitive(source.recipeKey),
                        EDITED to JsonPrimitive(source.edited),
                        RULES to JsonPrimitive(RulesWriter.write(source.rules)),
                    ),
                )
            }

            is ChannelSource.Leftovers -> {
                JsonObject(
                    linkedMapOf(
                        KIND to JsonPrimitive("leftovers"),
                        KEY to JsonPrimitive(source.recipeKey),
                        MAX_RATING to JsonPrimitive(source.maxRating),
                    ),
                )
            }

            is ChannelSource.Custom -> {
                val rules = JsonPrimitive(RulesWriter.write(source.rules))
                JsonObject(linkedMapOf(KIND to JsonPrimitive("custom"), RULES to rules))
            }

            // Only decode makes one, from JSON it parsed, so this gives back the very element it was made from.
            is ChannelSource.Unusable -> {
                try {
                    Json.parseToJsonElement(source.rawJson)
                } catch (_: IllegalArgumentException) {
                    JsonPrimitive(source.rawJson)
                }
            }
        }

    fun decode(element: JsonElement): ChannelSource {
        val fields = element as? JsonObject
        val kind = fields?.string(KIND)
        if (fields != null && kind != null) {
            val allowed = requireNotNull(keys[kind]) { "An unknown channel kind" }
            require(allowed.containsAll(fields.keys)) { "An unknown key in a channel source" }
        }
        if (nestsDeeperThan(element, MAX_DEPTH)) return ChannelSource.Unusable("null")
        val source = if (fields != null && kind != null) known(kind, fields) else null
        return source ?: ChannelSource.Unusable(Json.encodeToString(JsonElement.serializer(), element))
    }

    // True when [element] has more than [levels] levels of objects and arrays. Stops as soon as it is known.
    private fun nestsDeeperThan(
        element: JsonElement,
        levels: Int,
    ): Boolean =
        when (element) {
            is JsonObject -> levels <= 0 || element.values.any { nestsDeeperThan(it, levels - 1) }
            is JsonArray -> levels <= 0 || element.any { nestsDeeperThan(it, levels - 1) }
            else -> false
        }

    private fun known(
        kind: String,
        fields: JsonObject,
    ): ChannelSource? =
        try {
            when (kind) {
                "starter" -> starter(fields)
                "leftovers" -> leftovers(fields)
                else -> custom(fields)
            }
        } catch (_: IllegalArgumentException) {
            // A leftovers ceiling that is not on the ladder.
            null
        }

    private fun starter(fields: JsonObject): ChannelSource? {
        val key = fields.string(KEY)
        val edited = (fields[EDITED] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val rules = rulesOf(fields)
        return if (key != null && edited != null && rules != null) ChannelSource.Starter(key, edited, rules) else null
    }

    private fun leftovers(fields: JsonObject): ChannelSource? {
        val key = fields.string(KEY) ?: return null
        val ceiling = fields[MAX_RATING]
        return when {
            ceiling == JsonNull -> ChannelSource.Leftovers(key, null)
            ceiling is JsonPrimitive && ceiling.isString -> ChannelSource.Leftovers(key, ceiling.content)
            else -> null
        }
    }

    private fun custom(fields: JsonObject): ChannelSource? = rulesOf(fields)?.let { ChannelSource.Custom(it) }

    // Only rules this app can schedule; any other result keeps the channel unusable.
    private fun rulesOf(fields: JsonObject) = (fields.string(RULES)?.let(RulesReader::read) as? RulesResult.Ok)?.rules

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Suppress("LongParameterList")
@Serializable
private class FileV1(
    val version: Int,
    val serverId: String,
    val userId: String,
    val catalogVersion: Int,
    val utcOffsetMinutes: Int,
    val finalised: Boolean,
    val channels: List<ChannelV1>,
    val meta: MetaV1? = null,
)

@Serializable
private class MetaV1(
    val plannedGeneration: Long? = null,
    val collectionPassActedOn: Long? = null,
    val plannedLibraries: List<String>? = null,
) {
    val refreshMeta get() = RefreshMeta(plannedGeneration, collectionPassActedOn, plannedLibraries)

    companion object {
        /** Null for an empty [meta], so a file with nothing to say holds no `meta` key. */
        fun of(meta: RefreshMeta): MetaV1? =
            if (meta == RefreshMeta()) {
                null
            } else {
                MetaV1(meta.plannedGeneration, meta.collectionPassActedOn, meta.plannedLibraries)
            }
    }
}

@Suppress("LongParameterList")
@Serializable
private class ChannelV1(
    val id: String,
    val defaultNumber: Int?,
    val numberOverride: Int?,
    val nameOverride: String?,
    val state: StateV1,
    val onSinceMs: Long,
    val source: JsonElement,
)

@Serializable
private enum class StateV1(
    val state: ChannelState,
) {
    @SerialName("on")
    On(ChannelState.On),

    @SerialName("shelf")
    Shelf(ChannelState.Shelf),

    @SerialName("userHidden")
    UserHidden(ChannelState.UserHidden),
    ;

    companion object {
        fun of(state: ChannelState): StateV1 = entries.first { it.state == state }
    }
}
