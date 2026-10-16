// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Property test (a hand-written loop, no framework): editing any field except the seed, then writing and reading the
 * file again, never changes the seed or the text of the seed. The cases come from a fixed SplitMix64 stream, so a failure
 * repeats exactly. (The stream is local to this test; the real primitives are task 3.0.)
 */
class SeedIndependenceTest {
    private class Stream(
        private var state: ULong,
    ) {
        fun next(): ULong {
            state += 0x9E3779B97F4A7C15uL
            var z = state
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            return z xor (z shr 31)
        }

        fun below(bound: Int): Int = (next() % bound.toULong()).toInt()

        fun <T> pick(items: List<T>): T = items[below(items.size)]

        fun id(): CanonicalId = CanonicalId.parse(buildString { repeat(32) { append("0123456789abcdef"[below(16)]) } })
    }

    private val namePieces = listOf("a", "Z", " ", "é", "Ж", "😀", "🎬", "7", "-", "\"", "\\")
    private val ratings = listOf("TV-Y", "TV-G", "Approved", "TV-Y7-FV", "PG", "PG-13", "TV-14", "R", "NC-17", "TV-AO")
    private val epochHours = listOf(1, 2, 3, 4, 6, 8, 12, 24)

    private fun randomName(s: Stream): String = buildString { repeat(1 + s.below(100)) { append(s.pick(namePieces)) } }.let { clip(it) }

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

    /** One edit to a field other than the seed. */
    private fun edit(
        rules: ChannelRules,
        s: Stream,
    ): ChannelRules {
        val day = rules.timing.broadcastDay
        return when (s.below(13)) {
            0 -> rules.copy(name = randomName(s))
            1 -> rules.copy(name = null)
            2 -> rules.copy(maxRating = if (s.below(4) == 0) null else s.pick(ratings))
            3 -> rules.copy(ordering = if (s.below(4) == 0) null else Ordering(s.pick(OrderingMode.entries)))
            4 -> rules.copy(timing = rules.timing.copy(blockLength = 1 + s.below(100)))
            5 -> rules.copy(timing = rules.timing.copy(alignToGrid = s.below(2) == 0))
            6 -> rules.copy(timing = rules.timing.copy(epochHours = s.pick(epochHours)))
            7 -> rules.copy(timing = rules.timing.copy(broadcastDay = day.copy(utcOffsetMinutes = (s.below(105) - 48) * 15)))
            8 -> rules.copy(timing = rules.timing.copy(broadcastDay = day.copy(start = "${s.below(24).toString().padStart(2, '0')}:${s.pick(listOf("00", "30"))}")))
            9 -> rules.copy(sources = rules.sources + randomSource(s))
            10 -> rules.copy(exclusions = rules.effectiveExclusions + randomSource(s))
            11 -> rules.copy(sources = rules.sources.reversed())
            else -> rules.copy(extras = JsonObject(rules.extras + randomExtra(s)))
        }
    }

    private fun seedText(written: String): String {
        val root = Json.parseToJsonElement(written) as JsonObject
        return (root.getValue("seed") as JsonPrimitive).content
    }

    @Test
    fun editingAnyOtherFieldNeverChangesTheSeed() {
        val bases = ExampleFiles.valid.map { (name, text) -> name to assertNotNull(rulesOf(RulesReader.read(text)), name) }
        val stream = Stream(0x5EED5EEDuL)
        repeat(1000) { case ->
            val (name, base) = bases[case % bases.size]
            var rules = base
            repeat(1 + stream.below(3)) { rules = edit(rules, stream) }
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
    fun anExtraFieldNamedSeedCannotReplaceTheSeed() {
        val base = readOk(minimalRules().with("seed", "18446744073709551614").text())
        val tricked = base.copy(extras = JsonObject(mapOf("seed" to JsonPrimitive("1"))))
        assertEquals("18446744073709551614", seedText(RulesWriter.write(tricked)))
    }
}
