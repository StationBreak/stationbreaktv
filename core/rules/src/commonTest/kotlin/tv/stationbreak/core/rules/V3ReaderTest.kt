// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tv.stationbreak.core.model.OrderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Format version 3 is read only in a file that says 3 (docs/rules-format.md, "Format version"). */
class V3ReaderTest {
    private fun example(name: String): String = ExampleFiles.validV3.getValue(name)

    private fun v(
        version: Int,
        change: (kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject,
    ) = change(minimalRules().with("formatVersion", JsonPrimitive(version))).text()

    private val studioSources = array(source("studio", "name" to JsonPrimitive("Studio A")))

    @Test
    fun aStudioSourceReadsAndWritesByteForByte() {
        val text = example("studio.json")
        val rules = readOk(text)
        assertEquals(3, rules.formatVersion)
        assertEquals(Source.Studio("Studio A"), rules.sources.single())
        assertEquals(Source.Studio("Studio B"), rules.effectiveExclusions.single())
        assertEquals(text, RulesWriter.write(rules))
    }

    @Test
    fun everyNewFilterPartRoundTrips() {
        for (name in listOf("filters-tags-studios.json", "not-genres.json", "runtime-per-kind.json")) {
            val text = example(name)
            assertEquals(text, RulesWriter.write(readOk(text)), name)
        }
        val tags = readOk(example("filters-tags-studios.json")).filters
        assertEquals(listOf("Tag A", "Tag B"), tags?.tags)
        assertEquals(listOf("Studio A", "Studio C"), tags?.studios)
        assertEquals(listOf("Horror", "Thriller"), readOk(example("not-genres.json")).filters?.notGenres)
        val runtime = readOk(example("runtime-per-kind.json")).filters
        assertNotNull(runtime)
        assertNull(runtime.minRuntimeMinutes)
        assertEquals(180, runtime.maxRuntimeMinutes)
        assertNull(runtime.episodeMinRuntimeMinutes)
        assertEquals(30, runtime.episodeMaxRuntimeMinutes)
        assertEquals(80, runtime.movieMinRuntimeMinutes)
        assertEquals(150, runtime.movieMaxRuntimeMinutes)
    }

    @Test
    fun aRuntimeWithOnlyAKindWindowAndAnUnknownFieldReadsOk() {
        val text = example("runtime-kind-window-with-unknown-field.json")
        val rules = readOk(text)
        assertEquals(30, rules.filters?.episodeMaxRuntimeMinutes)
        assertEquals(setOf("future"), rules.filters?.runtimeExtras?.keys)
        assertEquals(text, RulesWriter.write(rules))
    }

    @Test
    fun aRuntimeWithAnUnknownFieldAndNoWindowIsInvalid() {
        val filters = buildJsonObject { put("runtime", buildJsonObject { put("future", 1) }) }
        assertIs<RulesResult.Invalid>(RulesReader.read(v(3) { it.with("filters", filters) }))
    }

    @Test
    fun aPerKindRuntimeAloneIsAWholeRuntimePart() {
        val filters =
            buildJsonObject {
                put("runtime", buildJsonObject { put("episode", buildJsonObject { put("minMinutes", 5) }) })
            }
        val text = v(3) { it.with("filters", filters) }
        val rules = readOk(text)
        assertEquals(5, rules.filters?.episodeMinRuntimeMinutes)
        assertEquals(3, rules.requiredVersion)
        assertEquals(RulesWriter.write(rules), RulesWriter.write(readOk(RulesWriter.write(rules))))
    }

    @Test
    fun curatedWithAndWithoutTunablesRoundTrips() {
        val plain = example("curated.json")
        val rules = readOk(plain)
        assertEquals(OrderingMode.Curated, rules.orderingMode)
        assertNull(rules.ordering?.repeatGapMinutes)
        assertEquals(180, rules.ordering?.effectiveRepeatGapMinutes)
        assertEquals(35, rules.ordering?.effectivePairUpToMinutes)
        assertEquals(plain, RulesWriter.write(rules))

        val tunedText = example("curated-tuned.json")
        val tuned = readOk(tunedText)
        assertEquals(120, tuned.ordering?.repeatGapMinutes)
        assertEquals(25, tuned.ordering?.pairUpToMinutes)
        assertEquals(120, tuned.ordering?.effectiveRepeatGapMinutes)
        assertEquals(25, tuned.ordering?.effectivePairUpToMinutes)
        assertNull(tuned.effectiveCaps)
        assertEquals(tunedText, RulesWriter.write(tuned))
    }

    @Test
    fun aV2FileWithAStudioSourceIsUnsupported() {
        for (version in 1..2) {
            val text = v(version) { it.with("sources", studioSources) }
            assertIs<RulesResult.Unsupported>(RulesReader.read(text), "version $version")
        }
    }

    @Test
    fun aV2FileWithCuratedIsUnsupported() {
        val text = v(2) { it.with("ordering", obj("mode" to JsonPrimitive("curated"))) }
        assertIs<RulesResult.Unsupported>(RulesReader.read(text))
    }

    @Test
    fun aV2FileWithTagsInFiltersKeepsThemAsExtras() {
        val filters =
            buildJsonObject {
                putJsonArray("genres") { add(JsonPrimitive("Comedy")) }
                putJsonArray("tags") { add(JsonPrimitive("Tag A")) }
                putJsonArray("studios") { add(JsonPrimitive("Studio A")) }
                putJsonArray("notGenres") { add(JsonPrimitive("Horror")) }
            }
        val text = v(2) { it.with("filters", filters) }
        val rules = readOk(text)
        val read = assertNotNull(rules.filters)
        assertNull(read.tags)
        assertNull(read.studios)
        assertNull(read.notGenres)
        assertEquals(setOf("tags", "studios", "notGenres"), read.extras.keys)
        assertEquals(2, rules.requiredVersion)
        assertEquals(RulesWriter.write(rules), RulesWriter.write(readOk(RulesWriter.write(rules))))
        assertTrue(RulesWriter.write(rules).contains("\"tags\""))
    }

    @Test
    fun aV2FileKeepsAPerKindRuntimeAndTheTunablesAsExtras() {
        val runtime =
            buildJsonObject {
                put("maxMinutes", 40)
                put("episode", buildJsonObject { put("maxMinutes", 20) })
            }
        val ordering =
            buildJsonObject {
                put("mode", "shuffle")
                put("repeatGapMinutes", 60)
            }
        val text = v(2) { it.with("filters", obj("runtime" to runtime)).with("ordering", ordering) }
        val rules = readOk(text)
        assertNull(rules.filters?.episodeMaxRuntimeMinutes)
        assertEquals(setOf("episode"), rules.filters?.runtimeExtras?.keys)
        assertNull(rules.ordering?.repeatGapMinutes)
        assertEquals(setOf("repeatGapMinutes"), rules.ordering?.extras?.keys)
        assertEquals(2, rules.requiredVersion)
    }

    @Test
    fun aV2RuntimeWithOnlyAPerKindWindowIsInvalid() {
        val runtime = obj("episode" to buildJsonObject { put("maxMinutes", 20) })
        val text = v(2) { it.with("filters", obj("runtime" to runtime)) }
        assertIs<RulesResult.Invalid>(RulesReader.read(text))
    }

    @Test
    fun aV3FileMustSayThree() {
        val rules = readOk(example("studio.json"))
        assertEquals(3, rules.requiredVersion)
        assertFailsWithIllegalArgument { rules.copy(formatVersion = 2) }
        assertFailsWithIllegalArgument { rules.copy(formatVersion = 1) }
        val tagged = readOk(example("filters-tags-studios.json"))
        assertFailsWithIllegalArgument { tagged.copy(formatVersion = 2) }
        val curated = readOk(example("curated.json"))
        assertFailsWithIllegalArgument { curated.copy(formatVersion = 2) }
        val tuned = readOk(example("curated-tuned.json"))
        assertFailsWithIllegalArgument { tuned.copy(formatVersion = 2) }
    }

    @Test
    fun curatedWithCapsIsInvalid() {
        val ordering =
            buildJsonObject {
                put("mode", "curated")
                put("caps", buildJsonObject { put("series", 2) })
            }
        assertIs<RulesResult.Invalid>(RulesReader.read(v(3) { it.with("ordering", ordering) }))
    }

    @Test
    fun theTunablesAreCheckedWhateverTheMode() {
        val ok =
            buildJsonObject {
                put("mode", "shuffle")
                put("repeatGapMinutes", 0)
                put("pairUpToMinutes", 1440)
            }
        assertIs<RulesResult.Ok>(RulesReader.read(v(3) { it.with("ordering", ok) }))
        for ((field, value) in listOf("repeatGapMinutes" to 1441, "repeatGapMinutes" to -1, "pairUpToMinutes" to 0)) {
            val bad =
                buildJsonObject {
                    put("mode", "curated")
                    put(field, value)
                }
            assertIs<RulesResult.Invalid>(RulesReader.read(v(3) { it.with("ordering", bad) }), "$field $value")
        }
    }

    @Test
    fun aUnknownFieldInAVersion3FileIsStillKept() {
        val filters =
            buildJsonObject {
                putJsonArray("tags") { add(JsonPrimitive("Tag A")) }
                put("future", true)
            }
        val text = v(3) { it.with("filters", filters) }
        val rules = readOk(text)
        assertEquals(setOf("future"), rules.filters?.extras?.keys)
        assertTrue(RulesWriter.write(rules).contains("\"future\""))
    }

    @Test
    fun aFillerSourceCannotBeAStudio() {
        val filler =
            buildJsonObject {
                put("mode", "padToGrid")
                put("source", source("studio", "name" to JsonPrimitive("Studio A")))
            }
        assertIs<RulesResult.Invalid>(RulesReader.read(v(3) { it.with("filler", filler) }))
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        kotlin.test.assertFailsWith<IllegalArgumentException> { block() }
    }
}
