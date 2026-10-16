// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.ordering.splitmix64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Property test (a hand-written loop, no framework): editing any field except the seed, then writing and reading the
 * file again, never changes the seed or the text of the seed. The cases come from a fixed SplitMix64 stream, so a
 * failure repeats exactly.
 */
class SeedIndependenceTest {
    // Vigna's SplitMix64 generator: draw k is splitmix64(state + (k - 1) * gamma), using the real primitive.
    private class Stream(
        private var state: ULong,
    ) {
        fun next(): ULong = splitmix64(state).also { state += 0x9E3779B97F4A7C15uL }

        fun below(bound: Int): Int = (next() % bound.toULong()).toInt()

        fun <T> pick(items: List<T>): T = items[below(items.size)]

        fun id(): CanonicalId = CanonicalId.parse(buildString { repeat(32) { append("0123456789abcdef"[below(16)]) } })
    }

    private val namePieces = listOf("a", "Z", " ", "é", "Ж", "😀", "🎬", "7", "-", "\"", "\\")
    private val ratings = listOf("TV-Y", "TV-G", "Approved", "TV-Y7-FV", "PG", "PG-13", "TV-14", "R", "NC-17", "TV-AO")
    private val epochHours = listOf(1, 2, 3, 4, 6, 8, 12, 24)

    private fun randomName(s: Stream): String {
        val raw = buildString { repeat(1 + s.below(100)) { append(s.pick(namePieces)) } }
        return clip(raw)
    }

    // Keeps a generated name inside 100 code points without splitting a pair.
    private fun clip(text: String): String {
        var out = text
        while (RulesLimits.codePoints(out) > RulesLimits.MAX_NAME_CODE_POINTS) out = out.dropLast(1)
        return if (out.isNotEmpty() && out.last().isHighSurrogate()) out.dropLast(1).ifEmpty { "a" } else out
    }

    private fun randomSource(s: Stream): Source =
        when (s.below(6)) {
            0 -> Source.Library(s.id())
            1 -> Source.Genre(s.pick(listOf("Comedy", "Dráma", "Animé", "Sci-Fi")))
            2 -> Source.Collection(s.id())
            3 -> Source.Tag(s.pick(listOf("Late Night", "😀", "x")))
            4 -> Source.YearRange(1900 + s.below(50), 1950 + s.below(70))
            else -> Source.Series(s.id())
        }

    private fun randomExtra(s: Stream): Pair<String, JsonElement> {
        val key = s.pick(listOf("seedHint", "note", "future", "formatVersionHint", "sources2"))
        val value: JsonElement =
            when (s.below(3)) {
                0 -> JsonPrimitive("18446744073709551615")
                1 -> JsonPrimitive(s.below(1000))
                else -> JsonObject(mapOf("n" to JsonPrimitive(true)))
            }
        return key to value
    }

    private fun ChannelRules.withTiming(change: (Timing) -> Timing) = copy(timing = change(timing))

    private fun ChannelRules.withDay(change: (BroadcastDay) -> BroadcastDay) =
        withTiming { it.copy(broadcastDay = change(it.broadcastDay)) }

    /** A start time; on the hour when the grid is hourly, because a half-hour start is invalid there. */
    private fun randomStart(
        s: Stream,
        gridMinutes: Int,
    ): String {
        val hour = s.below(24).toString().padStart(2, '0')
        val minutes = if (gridMinutes == 60) listOf("00") else listOf("00", "30")
        return "$hour:${s.pick(minutes)}"
    }

    /** Each entry changes one field other than the seed. */
    private val edits: List<(ChannelRules, Stream) -> ChannelRules> =
        listOf(
            { r, s -> r.copy(name = randomName(s)) },
            { r, _ -> r.copy(name = null) },
            { r, s -> r.copy(maxRating = if (s.below(4) == 0) null else s.pick(ratings)) },
            { r, s -> r.copy(ordering = if (s.below(4) == 0) null else Ordering(s.pick(OrderingMode.entries))) },
            { r, s -> r.withTiming { it.copy(blockLength = 1 + s.below(100)) } },
            { r, s -> r.withTiming { it.copy(alignToGrid = s.below(2) == 0) } },
            { r, s -> r.withTiming { it.copy(epochHours = s.pick(epochHours)) } },
            { r, s -> r.withDay { it.copy(utcOffsetMinutes = (s.below(105) - 48) * 15) } },
            { r, s ->
                r.withTiming { t ->
                    t.copy(broadcastDay = t.broadcastDay.copy(start = randomStart(s, t.effectiveGridMinutes)))
                }
            },
            { r, s -> r.withTiming { it.copy(graceHours = s.below(49)) } },
            { r, s -> r.withTiming { it.copy(minRuntimeSeconds = 1 + s.below(3600)) } },
            { r, s -> r.withTiming { t -> t.copy(gridMinutes = s.pick(listOf(15, 30))) } },
            { r, s -> r.copy(sources = r.sources + randomSource(s)) },
            { r, s -> r.copy(exclusions = r.effectiveExclusions + randomSource(s)) },
            { r, _ -> r.copy(sources = r.sources.reversed()) },
            { r, s -> r.copy(extras = JsonObject(r.extras + randomExtra(s))) },
        )

    private fun seedText(written: String): String {
        val root = Json.parseToJsonElement(written) as JsonObject
        return (root.getValue("seed") as JsonPrimitive).content
    }

    private fun rulesFor(
        name: String,
        text: String,
    ): ChannelRules = assertNotNull(rulesOf(RulesReader.read(text)), name)

    @Test
    fun editingAnyOtherFieldNeverChangesTheSeed() {
        val bases = ExampleFiles.valid.map { (name, text) -> name to rulesFor(name, text) }

        val stream = Stream(0x5EED5EEDuL)
        repeat(1000) { case ->
            val (name, base) = bases[case % bases.size]
            var rules = base
            repeat(1 + stream.below(3)) { rules = stream.pick(edits)(rules, stream) }
            val written = RulesWriter.write(rules)
            val context = "case $case from $name"
            assertEquals(base.seed.format(), seedText(written), context)
            val reread = assertNotNull(rulesOf(RulesReader.read(written)), "$context: $written")
            assertEquals(base.seed, reread.seed, context)
            assertEquals(rules, reread, context)
            assertEquals(written, RulesWriter.write(reread), context)
        }
    }

    @Test
    fun anExtraFieldNamedSeedCannotBeBuilt() {
        val base = readOk(minimalRules().with("seed", "18446744073709551614").text())
        assertFailsWith<IllegalArgumentException> {
            base.copy(extras = JsonObject(mapOf("seed" to JsonPrimitive("1"))))
        }
        assertEquals("18446744073709551614", seedText(RulesWriter.write(base)))
    }
}
