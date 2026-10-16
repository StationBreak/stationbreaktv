// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.Seed

/**
 * Builds [ChannelRules] from the JSON of a version 1 file. Throws [InvalidRules] for a wrong shape; an unknown kind is
 * recorded in [unsupported] and the rest of the file is still checked.
 */
internal class RulesDecoder {
    val unsupported = UnsupportedKinds()
    private val sources = SourceDecoder(unsupported)

    fun rules(root: JsonObject): ChannelRules =
        ChannelRules(
            formatVersion = int(required(root, "formatVersion"), "formatVersion"),
            name = root["name"]?.let { name(it, "name", RulesLimits.MAX_NAME_CODE_POINTS) },
            seed = seed(root),
            sources = sources.list(required(root, "sources"), "sources", min = 1),
            exclusions = root["exclusions"]?.let { sources.list(it, "exclusions", min = 0) },
            maxRating = root["maxRating"]?.let { maxRating(it) },
            ordering = root["ordering"]?.let { ordering(it) },
            timing = TimingDecoder.timing(required(root, "timing")),
            filler = root["filler"]?.let { filler(it) },
            extras = extras(root, RulesVocabulary.TOP_LEVEL),
        )

    private fun seed(root: JsonObject): Seed {
        val text = (required(root, "seed") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return text?.let { Seed.parseOrNull(it) }
            ?: invalid("seed must be a decimal string from 0 to 18446744073709551615, with no leading zeros")
    }

    private fun maxRating(element: JsonElement): String {
        val text = string(element, "maxRating")
        if (text !in RulesVocabulary.RATINGS) invalid("maxRating is not a rating on the ladder")
        return text
    }

    private fun ordering(element: JsonElement): Ordering? {
        val obj = element as? JsonObject ?: invalid("ordering must be an object")
        val text = string(required(obj, "mode", "ordering"), "ordering.mode")
        val mode = OrderingMode.entries.firstOrNull { it.wireName == text }
        if (mode == null) unsupported.mark("ordering mode", text)
        return mode?.let { Ordering(it, extras(obj, setOf("mode"))) }
    }

    private fun filler(element: JsonElement): Filler? {
        val obj = element as? JsonObject ?: invalid("filler must be an object")
        val text = string(required(obj, "mode", "filler"), "filler.mode")
        val mode = FillerMode.entries.firstOrNull { it.wireName == text }
        if (mode == null) unsupported.mark("filler mode", text)
        return mode?.let { build(it, obj) }
    }

    private fun build(
        mode: FillerMode,
        obj: JsonObject,
    ): Filler {
        val every = obj["every"]?.let { ranged(it, "filler.every", 1, RulesVocabulary.MAX_EVERY) }
        val source = obj["source"]?.let { sources.source(it, "filler.source", filler = true) }
        if (mode == FillerMode.BetweenEveryN && every == null) invalid("filler.every is required for betweenEveryN")
        val missingSource = mode != FillerMode.None && source == null && unsupported.reason == null
        if (missingSource) invalid("filler.source is required for ${mode.wireName}")
        return Filler(mode, every, source, extras(obj, setOf("mode", "every", "source")))
    }
}
