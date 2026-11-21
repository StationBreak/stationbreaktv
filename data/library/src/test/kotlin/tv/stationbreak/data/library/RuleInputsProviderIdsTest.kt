// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/** A series' provider ids reach the snapshot (the kid hold's bump list reads them, #990) and never a log. */
class RuleInputsProviderIdsTest {
    private val libraryId = CanonicalId.parse("0".repeat(30) + "a0")
    private val seriesId = CanonicalId.parse("0".repeat(30) + "b1")

    private val episode =
        RuleItem(
            item =
                ScheduleItem(
                    id = CanonicalId.parse("0".repeat(30) + "c2"),
                    kind = ScheduleItemKind.Episode,
                    runtimeMs = 1_000L,
                    createdAtMs = 0L,
                    deletedAtMs = null,
                    premiereDate = null,
                    productionYear = null,
                    seriesId = seriesId,
                    seasonNumber = null,
                    episodeNumber = null,
                ),
            libraryId = libraryId,
            seasonId = null,
            officialRating = null,
            genres = emptyList(),
            tags = emptyList(),
        )

    @Test
    fun theHeldSeriesProviderIdsAreInTheRuleSeries() {
        val ids = mapOf("tvdb" to "76088")
        val held = HeldSeries(seriesId.value, "TV-Y7", emptyList(), emptyList(), emptyList(), "Name", ids)
        val rule = RuleInputs.series(listOf(episode), listOf(held)).getValue(seriesId)
        assertEquals(ids, rule.providerIds)
        assertFalse("76088" in rule.toString() || "76088" in held.toString())
    }
}
