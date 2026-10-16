// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import kotlin.test.Test
import kotlin.test.assertFailsWith

// The types cannot hold what the writer must not emit: every rule the reader checks is also checked on construction.
class InvariantsTest {
    private val base = readOk(minimalRules().text())
    private val id = CanonicalId.parse(PLACEHOLDER_ID)

    private fun extras(vararg keys: String) = JsonObject(keys.associateWith { JsonPrimitive(1) })

    private fun rejects(build: () -> Any) {
        assertFailsWith<IllegalArgumentException> { build() }
    }

    @Test
    fun rulesNeedTheSupportedVersionAndAtLeastOneSource() {
        rejects { base.copy(formatVersion = 2) }
        rejects { base.copy(formatVersion = 0) }
        rejects { base.copy(sources = emptyList()) }
        rejects { base.copy(sources = List(257) { Source.Library(id) }) }
        rejects { base.copy(exclusions = List(257) { Source.Library(id) }) }
    }

    @Test
    fun theNameAndTheRatingAreChecked() {
        rejects { base.copy(name = "") }
        rejects { base.copy(name = "a".repeat(101)) }
        rejects { base.copy(name = "a\uD800b") }
        rejects { base.copy(maxRating = "PG-14") }
    }

    @Test
    fun extrasCannotCollideWithKnownKeys() {
        for (key in listOf("formatVersion", "name", "seed", "sources", "exclusions", "maxRating", "ordering", "timing", "filler")) {
            rejects { base.copy(extras = extras(key)) }
        }
        rejects { Source.Library(id, extras("type")) }
        rejects { Source.Library(id, extras("id")) }
        rejects { Source.Genre("x", extras("name")) }
        rejects { Source.YearRange(1, 2, extras("from")) }
        rejects { Ordering(OrderingMode.Shuffle, extras("mode")) }
        rejects { base.timing.copy(extras = extras("broadcastDay")) }
        rejects { base.timing.broadcastDay.copy(extras = extras("start")) }
        rejects { Filler(FillerMode.None, null, null, extras("source")) }
    }

    @Test
    fun sourcesAreChecked() {
        rejects { Source.Genre("") }
        rejects { Source.Tag("a".repeat(257)) }
        rejects { Source.Tag("\uDC00") }
        rejects { Source.YearRange(0, 5) }
        rejects { Source.YearRange(1, 10000) }
        rejects { Source.YearRange(1999, 1990) }
    }

    @Test
    fun timingIsChecked() {
        rejects { base.timing.copy(blockLength = 0) }
        rejects { base.timing.copy(blockLength = 101) }
        rejects { base.timing.copy(epochHours = 5) }
        rejects { base.timing.broadcastDay.copy(utcOffsetMinutes = 7) }
        rejects { base.timing.broadcastDay.copy(utcOffsetMinutes = 855) }
        rejects { base.timing.broadcastDay.copy(start = "06:10") }
    }

    @Test
    fun fillerIsChecked() {
        rejects { Filler(FillerMode.BetweenEveryN, null, Source.Library(id)) }
        rejects { Filler(FillerMode.BetweenEveryN, 3, null) }
        rejects { Filler(FillerMode.PadToGrid, null, null) }
        rejects { Filler(FillerMode.None, 0, null) }
        rejects { Filler(FillerMode.None, 1001, null) }
        rejects { Filler(FillerMode.None, null, Source.Genre("x")) }
        rejects { Filler(FillerMode.None, null, Source.YearRange(1, 2)) }
        rejects { Filler(FillerMode.None, null, Source.Series(id)) }
    }

    @Test
    fun theWriterOnlyEverSeesRulesThatPassed() {
        // Anything that can be built can be written and read back.
        val filler = Filler(FillerMode.None, 5, Source.Tag("x"))
        val rules = base.copy(filler = filler, name = "😀")
        assertRoundTrips(rules)
    }

    private fun assertRoundTrips(rules: ChannelRules) {
        kotlin.test.assertEquals(rules, readOk(RulesWriter.write(rules)))
    }
}
