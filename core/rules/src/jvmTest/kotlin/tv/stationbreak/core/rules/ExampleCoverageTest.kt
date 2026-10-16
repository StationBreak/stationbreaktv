// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The examples must show every kind the document describes (spec 07, task 1.0).
class ExampleCoverageTest {
    private val docs: List<Map<String, Any?>> by lazy {
        val examples = RulesFormatFiles.valid
        assertTrue(examples.isNotEmpty(), "no files in examples/")
        examples.values.map { asMap(RulesFormatFiles.parse(it)) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(v: Any?) = v as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun list(v: Any?) = (v as? List<Map<String, Any?>>).orEmpty()

    private fun kinds(field: String) = docs.flatMap { list(it[field]) }.map { it["type"] }.toSet()

    @Test
    fun everySourceKindIsShown() = assertEquals(sourceKinds, kinds("sources").intersect(sourceKinds))

    @Test
    fun everyKindOfExclusionIsShown() = assertEquals(sourceKinds, kinds("exclusions").intersect(sourceKinds))

    @Test
    fun everyOrderingModeIsShown() {
        val modes = docs.mapNotNull { (it["ordering"] as? Map<*, *>)?.get("mode") }.toSet()
        assertEquals(setOf("shuffle", "chronological", "randomInOrder"), modes)
    }

    @Test
    fun everyFillerModeIsShownWithEachFillerSource() {
        val fillers = docs.mapNotNull { it["filler"] as? Map<*, *> }
        assertEquals(setOf("none", "padToGrid", "betweenEveryN"), fillers.map { it["mode"] }.toSet())
        val sources = fillers.mapNotNull { (it["source"] as? Map<*, *>)?.get("type") }.toSet()
        assertEquals(setOf("library", "collection", "tag"), sources)
    }

    @Test
    fun aRatingCeilingIsShown() = assertTrue(docs.any { "maxRating" in it })

    @Test
    fun aBroadcastDayWithANonZeroOffsetIsShown() {
        val offsets =
            docs.mapNotNull {
                val day = (it["timing"] as? Map<*, *>)?.get("broadcastDay") as? Map<*, *>
                day?.get("utcOffsetMinutes")
            }
        assertTrue(offsets.any { it != 0 })
    }

    @Test
    fun aQuarterHourOffsetIsShown() {
        val offsets =
            docs.mapNotNull {
                val timing = it["timing"] as? Map<*, *>
                (timing?.get("broadcastDay") as? Map<*, *>)?.get("utcOffsetMinutes") as? Int
            }
        assertTrue(offsets.any { it == 345 })
    }

    @Test
    fun everyExampleStatesItsBroadcastDayOffset() {
        for ((name, text) in RulesFormatFiles.valid) {
            val m = asMap(RulesFormatFiles.parse(text))
            val day = (m["timing"] as? Map<*, *>)?.get("broadcastDay") as? Map<*, *>
            assertTrue(day?.get("utcOffsetMinutes") != null, "$name has no timing.broadcastDay.utcOffsetMinutes")
        }
    }

    @Test
    fun aSeedWithTheHighBitSetIsShown() {
        val seeds = docs.map { it["seed"] as String }
        assertTrue(seeds.any { it.toULong() >= HIGH_BIT })
    }

    @Test
    fun theForwardCompatibilityExampleCarriesUnknownFieldsAtEveryObjectLevel() {
        val doc = asMap(RulesFormatFiles.parse(RulesFormatFiles.valid.getValue("forward-compat-extras.json")))

        fun hasExtra(
            m: Any?,
            at: String,
        ) = assertTrue((m as Map<*, *>).keys.any { it !in knownKeys.getValue(at) }, "no unknown field in $at")
        hasExtra(doc, "top")
        list(doc["sources"]).forEach { hasExtra(it, "source") }
        list(doc["exclusions"]).forEach { hasExtra(it, "source") }
        hasExtra(doc["ordering"], "ordering")
        val timing = doc["timing"] as Map<*, *>
        hasExtra(timing, "timing")
        hasExtra(timing["broadcastDay"], "broadcastDay")
        val filler = doc["filler"] as Map<*, *>
        hasExtra(filler, "filler")
        hasExtra(filler["source"], "source")
    }

    private companion object {
        const val HIGH_BIT = 9223372036854775808uL
        val sourceKinds = setOf("library", "genre", "collection", "tag", "yearRange", "series")
        val knownKeys =
            mapOf(
                "top" to
                    setOf(
                        "formatVersion",
                        "name",
                        "seed",
                        "sources",
                        "exclusions",
                        "maxRating",
                        "ordering",
                        "timing",
                        "filler",
                    ),
                "source" to setOf("type", "id", "name", "from", "to"),
                "ordering" to setOf("mode"),
                "timing" to setOf("blockLength", "alignToGrid", "epochHours", "broadcastDay"),
                "broadcastDay" to setOf("utcOffsetMinutes", "start"),
                "filler" to setOf("mode", "every", "source"),
            )
    }
}
