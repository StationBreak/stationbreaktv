// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.scheduler.buildDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The channels the app creates run back to back (they write `alignToGrid: false`), while a rules file that omits the
 * field still takes the format's version 1 default, the grid.
 */
class BackToBackLayoutTest {
    private val server = CanonicalId.parseCanonicalOrNull("0000000000000000000000000000005e")!!

    // Five 7-minute programs: on a 30-minute grid each leaves 23 minutes of slate.
    private val items =
        (1..5).map {
            ScheduleItem(
                id = CanonicalId.parseCanonicalOrNull("000000000000000000000000000000a$it")!!,
                kind = ScheduleItemKind.Movie,
                runtimeMs = 7 * 60_000L,
                createdAtMs = -1_000_000_000_000L,
                deletedAtMs = null,
                premiereDate = null,
                productionYear = null,
                seriesId = null,
                seasonNumber = null,
                episodeNumber = null,
            )
        }

    private fun blocksOf(settings: SchedulerSettings): List<ScheduleBlock> = buildDay(settings, items, 0L).blocks

    private fun assertBackToBack(blocks: List<ScheduleBlock>) {
        val slates = blocks.withIndex().filter { it.value.content !is ScheduleContent.Program }
        assertTrue(slates.all { it.index == blocks.lastIndex }, "only the closing slate is a slate: $slates")
        blocks.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        assertEquals(7 * 60_000L, blocks.first().lengthMs)
        assertEquals(blocks.first().endMs, blocks[1].startMs, "the next run starts when the first ends")
    }

    @Test
    fun aResolvedStarterRunsBackToBack() {
        val rules = RecipeResolver.resolve(StarterCatalog.recipes.first(), server, 0, CollectionNames(emptyMap()))
        assertEquals(false, rules.timing.alignToGrid)
        val settings = rules.toSchedulerSettings()
        assertFalse(settings.alignToGrid)
        assertBackToBack(blocksOf(settings))
    }

    @Test
    fun aLeftoversChannelRunsBackToBack() {
        val settings = RecipeResolver.leftoversSettings(StarterCatalog.leftovers.first(), server, 0)
        assertFalse(settings.alignToGrid)
        assertBackToBack(blocksOf(settings))
    }

    @Test
    fun rulesThatOmitAlignToGridStillAlignToTheGrid() {
        val resolved = RecipeResolver.resolve(StarterCatalog.recipes.first(), server, 0, CollectionNames(emptyMap()))
        val omitted = resolved.copy(timing = resolved.timing.copy(alignToGrid = null))
        val settings = omitted.toSchedulerSettings()
        assertTrue(settings.alignToGrid)
        val blocks = blocksOf(settings)
        assertEquals(30 * 60_000L, blocks[2].startMs - blocks[0].startMs, "the second run starts on the next mark")
        assertTrue(blocks[1].content !is ScheduleContent.Program, "a slate fills the gap to the next mark")
    }
}
