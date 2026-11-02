// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val ITEM_ID = "22222222222222222222222222222222"
private const val OTHER_ITEM_ID = "33333333333333333333333333333333"

class V2ReaderTest {
    private fun v2() = minimalRules().with("formatVersion", JsonPrimitive(2))

    private fun withOrdering(vararg fields: Pair<String, JsonElement>) =
        v2().with("ordering", obj("mode" to JsonPrimitive("rotation"), *fields))

    private fun withCaps(series: JsonElement) = withOrdering("caps" to obj("series" to series))

    private fun invalid(root: JsonObject): RulesResult.Invalid {
        val result = RulesReader.read(root.text())
        assertIs<RulesResult.Invalid>(result, "expected Invalid, got $result")
        return result
    }

    private fun filtersOf(vararg fields: Pair<String, JsonElement>) = v2().with("filters", obj(*fields))

    @Test
    fun aVersion2FileWithEveryFeatureReadsOk() {
        val root =
            v2()
                .with("sources", array(libraryJson(), source("item", "id" to JsonPrimitive(ITEM_ID))))
                .with("exclusions", array(source("item", "id" to JsonPrimitive(OTHER_ITEM_ID))))
                .with(
                    "ordering",
                    obj(
                        "mode" to JsonPrimitive("rotation"),
                        "caps" to obj("series" to JsonPrimitive(4), "film" to JsonPrimitive(2)),
                    ),
                ).with(
                    "filters",
                    obj(
                        "genres" to array(JsonPrimitive("Comedy"), JsonPrimitive("Drama")),
                        "years" to obj("from" to JsonPrimitive(1990), "to" to JsonPrimitive(1999)),
                        "runtime" to obj("minMinutes" to JsonPrimitive(10), "maxMinutes" to JsonPrimitive(40)),
                        "kind" to JsonPrimitive("episode"),
                    ),
                ).edit("timing") { it.with("epochHours", JsonPrimitive(24)) }
        val rules = readOk(root.text())
        assertEquals(2, rules.formatVersion)
        assertEquals(Source.Item(CanonicalId.parse(ITEM_ID)), rules.sources[1])
        assertEquals(Source.Item(CanonicalId.parse(OTHER_ITEM_ID)), rules.exclusions?.single())
        assertEquals(OrderingMode.Rotation, rules.orderingMode)
        assertEquals(4, rules.ordering?.caps?.series)
        assertEquals(2, rules.ordering?.caps?.film)
        val filters = rules.filters
        assertEquals(listOf("Comedy", "Drama"), filters?.genres)
        assertEquals(1990, filters?.yearFrom)
        assertEquals(1999, filters?.yearTo)
        assertEquals(10, filters?.minRuntimeMinutes)
        assertEquals(40, filters?.maxRuntimeMinutes)
        assertEquals(FilterKind.Episode, filters?.kind)
        assertEquals(root, Json.parseToJsonElement(RulesWriter.write(rules)))
    }

    @Test
    fun inOrderAndACappedShuffleRead() {
        val inOrder = v2().with("ordering", obj("mode" to JsonPrimitive("inOrder")))
        assertEquals(OrderingMode.InOrder, readOk(inOrder.text()).orderingMode)
        val shuffle = v2().with("ordering", obj("mode" to JsonPrimitive("shuffle"), "caps" to obj()))
        assertEquals(3, readOk(shuffle.text()).effectiveCaps?.effectiveSeries)
    }

    @Test
    fun capsDefaultWhenPartsAreMissing() {
        val rules = readOk(withOrdering("caps" to obj()).text())
        assertEquals(3, rules.ordering?.caps?.effectiveSeries)
        assertEquals(1, rules.ordering?.caps?.effectiveFilm)
        assertNull(rules.ordering?.caps?.series)
    }

    @Test
    fun capsOutOfRangeAreInvalid() {
        val bad = listOf("0", "11", "\"3\"", "3.0", "null")
        for (value in bad) {
            val series = Json.parseToJsonElement(value)
            val result = invalid(withCaps(series))
            assertEquals(true, result.reason.contains("ordering.caps.series"), value)
            assertEquals(false, result.reason.contains("3.0"), value)
        }
        val film = withOrdering("caps" to obj("film" to JsonPrimitive(11)))
        assertEquals(true, invalid(film).reason.contains("ordering.caps.film"))
        assertIs<RulesResult.Invalid>(RulesReader.read(withOrdering("caps" to JsonPrimitive(3)).text()))
    }

    @Test
    fun capsAreValidatedWhateverTheMode() {
        val caps = obj("series" to JsonPrimitive(0))
        val root = v2().with("ordering", obj("mode" to JsonPrimitive("chronological"), "caps" to caps))
        assertEquals(true, invalid(root).reason.contains("ordering.caps.series"))
    }

    @Test
    fun capsWithEpochHours12AreInvalid() {
        val root = withOrdering("caps" to obj()).edit("timing") { it.with("epochHours", JsonPrimitive(12)) }
        assertEquals("Caps need a 24-hour epoch", invalid(root).reason)
        val bare = v2().with("ordering", obj("mode" to JsonPrimitive("rotation")))
        invalid(bare.edit("timing") { it.with("epochHours", JsonPrimitive(12)) })
    }

    @Test
    fun filtersWrongTypesAreInvalid() {
        val year = { from: Int, to: Int -> obj("from" to JsonPrimitive(from), "to" to JsonPrimitive(to)) }
        val cases =
            listOf(
                filtersOf("genres" to JsonPrimitive("Comedy")),
                filtersOf("genres" to array()),
                filtersOf("genres" to array(JsonPrimitive(1))),
                filtersOf("genres" to array(JsonPrimitive(""))),
                filtersOf("years" to year(0, 5)),
                filtersOf("years" to year(2000, 1999)),
                filtersOf("years" to obj("from" to JsonPrimitive(2000))),
                filtersOf("runtime" to obj("minMinutes" to JsonPrimitive(0))),
                filtersOf("runtime" to obj("maxMinutes" to JsonPrimitive(1441))),
                filtersOf("runtime" to obj()),
                filtersOf("runtime" to obj("minMinutes" to JsonPrimitive(30), "maxMinutes" to JsonPrimitive(20))),
                filtersOf("kind" to JsonPrimitive(7)),
                v2().with("filters", JsonPrimitive("x")),
            )
        for ((i, root) in cases.withIndex()) {
            val result = invalid(root)
            assertEquals(true, result.reason.contains("filters"), "case $i: ${result.reason}")
        }
    }

    @Test
    fun anUnknownFilterKindIsUnsupported() {
        val result = RulesReader.read(filtersOf("kind" to JsonPrimitive("season")).text())
        assertIs<RulesResult.Unsupported>(result)
        assertEquals(true, result.reason.contains("season"))
    }

    @Test
    fun anItemSourceNeedsACanonicalId() {
        invalid(v2().with("sources", array(source("item"))))
        invalid(v2().with("sources", array(source("item", "id" to JsonPrimitive("ABC")))))
        invalid(v2().with("sources", array(source("item", "id" to JsonPrimitive(7)))))
    }

    @Test
    fun anItemCannotBeFiller() {
        val item = source("item", "id" to JsonPrimitive(ITEM_ID))
        val filler = obj("mode" to JsonPrimitive("padToGrid"), "source" to item)
        invalid(v2().with("filler", filler))
    }

    @Test
    fun anEmptyFiltersObjectReadsAndWritesBack() {
        val rules = readOk(filtersOf().text())
        assertEquals(true, rules.filters?.isEmpty)
        val written = RulesWriter.write(rules)
        assertEquals(true, written.contains("\"filters\": {}"))
        assertEquals(rules, readOk(written))
    }

    @Test
    fun aVersion2FileWritesFieldsInTheDocumentedOrder() {
        val root =
            v2()
                .with(
                    "ordering",
                    obj(
                        "mode" to JsonPrimitive("shuffle"),
                        "caps" to obj("film" to JsonPrimitive(2), "series" to JsonPrimitive(4)),
                    ),
                ).with(
                    "filters",
                    obj(
                        "kind" to JsonPrimitive("movie"),
                        "runtime" to obj("maxMinutes" to JsonPrimitive(90), "minMinutes" to JsonPrimitive(5)),
                        "years" to obj("to" to JsonPrimitive(2000), "from" to JsonPrimitive(1990)),
                        "genres" to array(JsonPrimitive("Comedy")),
                    ),
                )
        val written = RulesWriter.write(readOk(root.text()))
        val names =
            listOf(
                "formatVersion",
                "seed",
                "sources",
                "ordering",
                "caps",
                "series",
                "film",
                "filters",
                "genres",
                "years",
                "from",
                "to",
                "runtime",
                "minMinutes",
                "maxMinutes",
                "kind",
                "timing",
            )
        val positions = names.map { written.indexOf("\"$it\"") }
        assertEquals(positions.sorted(), positions)
        assertEquals(false, positions.any { it < 0 })
        assertEquals(readOk(root.text()), readOk(written))
    }

    @Test
    fun anUnknownFilterKindDoesNotHideAnInvalidPart() {
        val bad = filtersOf("kind" to JsonPrimitive("season"), "genres" to array())
        assertEquals(true, invalid(bad).reason.contains("filters.genres"))
        val years = obj("from" to JsonPrimitive(2000), "to" to JsonPrimitive(1999))
        invalid(filtersOf("kind" to JsonPrimitive("season"), "years" to years))
    }

    @Test
    fun unknownFieldsInsideFiltersAreKeptAndWrittenBack() {
        val root =
            filtersOf(
                "genres" to array(JsonPrimitive("Comedy")),
                "years" to
                    obj("from" to JsonPrimitive(1990), "to" to JsonPrimitive(1999), "yearsNew" to JsonPrimitive(1)),
                "runtime" to obj("minMinutes" to JsonPrimitive(5), "runtimeNew" to array(JsonPrimitive(true))),
                "kind" to JsonPrimitive("movie"),
                "filtersNew" to obj("deep" to JsonPrimitive("x")),
            )
        val rules = readOk(root.text())
        assertEquals(setOf("filtersNew"), rules.filters?.extras?.keys)
        assertEquals(setOf("yearsNew"), rules.filters?.yearsExtras?.keys)
        assertEquals(setOf("runtimeNew"), rules.filters?.runtimeExtras?.keys)
        assertEquals(root, Json.parseToJsonElement(RulesWriter.write(rules)))
    }
}
