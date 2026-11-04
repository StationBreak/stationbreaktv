// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.Seed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A source's entry text is exactly what it adds to the written rules, so sizes add up source by source. */
class SourceEntryTest {
    private val id = CanonicalId.parse(PLACEHOLDER_ID)

    private val sources =
        listOf(
            Source.Genre("Comedy"),
            Source.Collection(id),
            Source.Studio("Studio \"A\" \\ é"),
            Source.Tag("tag", extras = JsonObject(mapOf("x-note" to JsonPrimitive("kept")))),
            Source.YearRange(1980, 1989),
            Source.Series(id),
            Source.Item(id),
            Source.Library(id),
        )

    private fun rulesOf(sources: List<Source>) =
        ChannelRules(
            formatVersion = 3,
            name = "Entries",
            seed = Seed(7uL),
            sources = sources,
            exclusions = null,
            maxRating = null,
            ordering = null,
            timing = Timing(null, false, null, null, null, null, BroadcastDay(0, "06:00")),
            filler = null,
        )

    @Test
    fun theEntriesJoinedAreTheWrittenSourcesList() {
        val written = RulesWriter.write(rulesOf(sources))
        val joined = sources.joinToString(RulesWriter.SOURCE_SEPARATOR) { RulesWriter.sourceEntry(it) }
        assertTrue("\"sources\": [\n$joined\n  ]" in written, written)
    }

    @Test
    fun eachSourceAddsItsSeparatorAndEntry() {
        val first = RulesWriter.write(rulesOf(sources.take(1)))
        var size = first.length
        for (count in 2..sources.size) {
            size += RulesWriter.SOURCE_SEPARATOR.length + RulesWriter.sourceEntry(sources[count - 1]).length
            assertEquals(RulesWriter.write(rulesOf(sources.take(count))).length, size, "with $count sources")
        }
    }
}
