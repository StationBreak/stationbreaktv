// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.OrderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Format version 4, `writeVersion` and the upgrade behind the gate (docs/adr/0026-studio-fold-2.md, Versioning). */
class StudioFold2FormatTest {
    private val studio = source("studio", "name" to JsonPrimitive("Studio A"))

    private fun at(version: Int) = minimalRules().with("formatVersion", JsonPrimitive(version))

    private fun studioFile(version: Int) = at(version).with("sources", array(studio))

    private fun studioRules(version: Int) = readOk(studioFile(version).text())

    private val library = readOk(minimalRules().text()).sources.single()

    @Test
    fun theGateIsOffInThisRelease() {
        assertFalse(StudioFold2Gate.WRITES_FORMAT_4)
        assertEquals(4, StudioFold2Gate.FORMAT_VERSION)
    }

    @Test
    fun aFormat4FileReadsAndWritesByteForByte() {
        val text = RulesWriter.write(studioRules(4))
        val rules = readOk(text)
        assertEquals(4, rules.formatVersion)
        assertTrue(rules.matchesStudiosByFold2)
        assertEquals(text, RulesWriter.write(rules))
    }

    @Test
    fun aFormat5FileNeedsAnUpdate() {
        assertEquals(RulesResult.UpdateRequired(5), RulesReader.read(studioFile(5).text()))
    }

    @Test
    fun version4KnowsTheVersion3FieldsAndNothingMore() {
        val curated = obj("mode" to JsonPrimitive("curated"), "repeatGapMinutes" to JsonPrimitive(60))
        val text = studioFile(4).with("ordering", curated).with("futureField", "x").text()
        val rules = readOk(text)
        assertEquals(OrderingMode.Curated, rules.orderingMode)
        assertEquals(60, rules.ordering?.repeatGapMinutes)
        assertEquals(setOf("futureField"), rules.extras.keys)
    }

    @Test
    fun aFormat3StudioFileStaysValidAndMatchesUnderFold1() {
        val rules = studioRules(3)
        assertEquals(3, rules.requiredVersion, "requiredVersion stays 3 for studio features")
        assertFalse(rules.matchesStudiosByFold2)
        assertIs<RulesResult.Ok>(RulesReader.read(studioFile(3).text()))
    }

    @Test
    fun everyStudioFeatureCounts() {
        val exclusion = readOk(at(3).with("exclusions", array(studio)).text())
        val filter =
            readOk(
                minimalRules()
                    .with("formatVersion", JsonPrimitive(3))
                    .with("filters", obj("studios" to array(JsonPrimitive("Studio A"))))
                    .text(),
            )
        val tags =
            readOk(
                minimalRules()
                    .with("formatVersion", JsonPrimitive(3))
                    .with("filters", obj("tags" to array(JsonPrimitive("Tag A"))))
                    .text(),
            )
        assertTrue(studioRules(3).usesStudios)
        assertTrue(exclusion.usesStudios)
        assertTrue(filter.usesStudios)
        assertFalse(tags.usesStudios, "a version 3 feature that is not a studio")
    }

    @Test
    fun withTheGateOffTheWriteVersionNeverLowersAStudioFile() {
        assertEquals(3, studioRules(3).writeVersion)
        assertEquals(4, studioRules(4).writeVersion)
        assertEquals(4, studioRules(4).normalised().formatVersion)
        assertEquals(3, studioRules(3).normalised().formatVersion)
        assertEquals(3, studioRules(4).writeVersion(previousVersion = null, gatePassed = false))
        assertEquals(4, studioRules(4).writeVersion(previousVersion = 4, gatePassed = false))
        assertEquals(3, studioRules(4).writeVersion(previousVersion = 2, gatePassed = false))
    }

    @Test
    fun withTheGateOnAStudioFileIsWrittenAt4() {
        assertEquals(4, studioRules(3).writeVersion(previousVersion = 3, gatePassed = true))
        assertEquals(4, studioRules(3).writeVersion(previousVersion = null, gatePassed = true))
        assertEquals(4, studioRules(3).normalised(gatePassed = true).formatVersion)
        assertEquals(4, studioRules(4).normalised(gatePassed = true).formatVersion)
    }

    @Test
    fun rulesWithoutStudiosAreWrittenAtTheirRequiredVersionEitherWay() {
        val tags =
            readOk(
                minimalRules()
                    .with("formatVersion", JsonPrimitive(4))
                    .with("filters", obj("tags" to array(JsonPrimitive("Tag A"))))
                    .text(),
            )
        val plain = readOk(minimalRules().with("formatVersion", JsonPrimitive(4)).text())
        for (gate in listOf(false, true)) {
            assertEquals(3, tags.writeVersion(previousVersion = 4, gatePassed = gate))
            assertEquals(1, plain.writeVersion(previousVersion = 4, gatePassed = gate))
            assertEquals(1, plain.normalised(gatePassed = gate).formatVersion)
        }
        assertEquals(library, plain.sources.single())
    }

    @Test
    fun theUpgradeIsHeldBehindTheGate() {
        val three = studioRules(3)
        assertSame(three, three.upgradedToStudioFold2(gatePassed = false))
        assertSame(three, three.upgradedToStudioFold2())
        val upgraded = three.upgradedToStudioFold2(gatePassed = true)
        assertEquals(three.copy(formatVersion = 4), upgraded, "only the version changes")
        assertEquals(upgraded, upgraded.upgradedToStudioFold2(gatePassed = true), "idempotent")
    }

    @Test
    fun theUpgradeLeavesOtherFilesAlone() {
        val noStudios = readOk(minimalRules().with("formatVersion", JsonPrimitive(3)).text())
        val one = readOk(minimalRules().text())
        val two = readOk(minimalRules().with("formatVersion", JsonPrimitive(2)).text())
        val four = studioRules(4)
        for (rules in listOf(noStudios, one, two, four)) {
            assertSame(rules, rules.upgradedToStudioFold2(gatePassed = true))
        }
    }
}
