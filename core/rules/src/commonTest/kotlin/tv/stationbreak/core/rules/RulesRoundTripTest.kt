// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RulesRoundTripTest {
    // Filler modes other than "none" are not scheduled by a version 1 reader, but the file is still read and kept.
    private val notScheduled =
        setOf("filler-pad-to-grid.json", "filler-between-every-n.json", "filler-tag-source.json", "full.json")

    @Test
    fun everyValidExampleReadsThenWritesIdenticalText() {
        for ((name, text) in ExampleFiles.valid) {
            val result = RulesReader.read(text)
            val rules = assertNotNull(rulesOf(result), "$name: $result")
            assertEquals(text, RulesWriter.write(rules), name)
        }
    }

    @Test
    fun everyValidExampleReadsAsOkExceptTheUnscheduledFillerModes() {
        for ((name, text) in ExampleFiles.valid) {
            val result = RulesReader.read(text)
            if (name in notScheduled) {
                assertIs<RulesResult.Unsupported>(result, name)
            } else {
                assertIs<RulesResult.Ok>(result, "$name: $result")
            }
        }
    }

    @Test
    fun aHighBitSeedIsWrittenBackExactly() {
        val text = ExampleFiles.valid.getValue("genre-with-max-rating.json")
        val rules = readOk(text)
        assertEquals(Seed.parse("13835058055282163712"), rules.seed)
        assertTrue(RulesWriter.write(rules).contains("\"seed\": \"13835058055282163712\""))
        val largest = readOk(ExampleFiles.valid.getValue("tag.json"))
        assertEquals(ULong.MAX_VALUE, largest.seed.value)
        assertTrue(RulesWriter.write(largest).contains("\"seed\": \"18446744073709551615\""))
    }

    @Test
    fun theTypedFieldsCarryWhatTheFileSays() {
        val rules = readOk(ExampleFiles.valid.getValue("exclusion.json"))
        assertEquals(1, rules.formatVersion)
        assertEquals("Comedy, Minus One", rules.name)
        assertEquals(2, rules.sources.size)
        assertEquals(6, rules.exclusions?.size)
        assertIs<Source.Genre>(rules.sources[0])
        assertEquals(Source.YearRange(from = 1900, to = 1969), rules.exclusions?.get(4))
        assertEquals(OrderingMode.Shuffle, rules.orderingMode)
        assertEquals(1, rules.timing.effectiveBlockLength)
        assertTrue(rules.timing.effectiveAlignToGrid)
        assertEquals(24, rules.timing.effectiveEpochHours)
        assertEquals("06:00", rules.timing.broadcastDay.effectiveStart)
        assertEquals(6, rules.timing.effectiveGraceHours)
        assertEquals(30, rules.timing.effectiveGridMinutes)
    }

    @Test
    fun graceAndGridDefaultWhenAbsentAndAreNotInventedOnWrite() {
        val rules = readOk(ExampleFiles.valid.getValue("library-only.json"))
        assertEquals(null, rules.timing.graceHours)
        assertEquals(null, rules.timing.gridMinutes)
        assertEquals(6, rules.timing.effectiveGraceHours)
        assertEquals(30, rules.timing.effectiveGridMinutes)
        val written = RulesWriter.write(rules)
        assertTrue(!written.contains("graceHours") && !written.contains("gridMinutes"))
    }

    @Test
    fun graceAndGridTravelInTheRulesAndComeBackOut() {
        val text = ExampleFiles.valid.getValue("timing-grid-and-grace.json")
        val rules = readOk(text)
        assertEquals(12, rules.timing.graceHours)
        assertEquals(60, rules.timing.gridMinutes)
        assertEquals(12, rules.timing.effectiveGraceHours)
        assertEquals(60, rules.timing.effectiveGridMinutes)
        assertEquals(emptySet(), rules.timing.extras.keys)
        assertEquals(text, RulesWriter.write(rules))
    }

    @Test
    fun aGraceOfZeroIsKeptAndWrittenBack() {
        val timing = obj("graceHours" to JsonPrimitive(0), "broadcastDay" to obj("utcOffsetMinutes" to JsonPrimitive(0)))
        val rules = readOk(minimalRules().with("timing", timing).text())
        assertEquals(0, rules.timing.graceHours)
        assertEquals(0, rules.timing.effectiveGraceHours)
        assertTrue(RulesWriter.write(rules).contains("\"graceHours\": 0"))
    }

    @Test
    fun theDefaultsApplyWhenAFieldIsAbsentAndTheFieldIsNotInventedOnWrite() {
        val rules = readOk(ExampleFiles.valid.getValue("library-only.json"))
        assertEquals(null, rules.ordering)
        assertEquals(null, rules.filler)
        assertEquals(FillerMode.None, rules.fillerMode)
        assertTrue(!RulesWriter.write(rules).contains("ordering"))
    }

    @Test
    fun theFullExampleKeepsEverySection() {
        val rules = assertNotNull(rulesOf(RulesReader.read(ExampleFiles.valid.getValue("full.json"))))
        assertEquals(OrderingMode.RandomInOrder, rules.orderingMode)
        assertEquals("TV-Y7", rules.maxRating)
        assertEquals(-300, rules.timing.broadcastDay.utcOffsetMinutes)
        assertEquals(FillerMode.BetweenEveryN, rules.fillerMode)
        assertEquals(3, rules.filler?.every)
        assertEquals(Source.Tag("Station IDs"), rules.filler?.source)
    }

    @Test
    fun aHundredEmojiNameCountsAsOneHundredCodePoints() {
        val rules = readOk(ExampleFiles.valid.getValue("name-100-code-points.json"))
        assertEquals(200, rules.name?.length)
    }
}
