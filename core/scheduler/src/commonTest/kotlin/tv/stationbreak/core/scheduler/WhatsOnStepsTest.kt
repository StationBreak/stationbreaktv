// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.scheduler

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `Day.whatsOn` answers from a built day by binary search: at most `ceil(log2 B) + c` comparisons for a day of `B`
 * blocks (spec 08, Success Metrics 3). A counter is injected into the day, so no clock is involved.
 */
class WhatsOnStepsTest {
    private val library = SyntheticLibrary(size = 10_000, seed = 7L)

    /** The library's movies and episodes as scheduler items; [runtimeOf] may change a runtime to make shorter blocks. */
    private fun scheduleItems(runtimeOf: (MediaItem) -> Long): List<ScheduleItem> =
        library.items
            .filter { it.kind == ItemKind.Movie || it.kind == ItemKind.Episode }
            .map { media ->
                val episode = media.kind == ItemKind.Episode
                ScheduleItem(
                    id = CanonicalId.parse(media.id.value),
                    kind = if (episode) ScheduleItemKind.Episode else ScheduleItemKind.Movie,
                    runtimeMs = runtimeOf(media),
                    createdAtMs = checkNotNull(media.dateCreated).toEpochMilliseconds(),
                    deletedAtMs = null,
                    premiereDate = null,
                    productionYear = media.productionYear,
                    seriesId = media.seriesId?.let { CanonicalId.parse(it.value) },
                    seasonNumber = media.seasonNumber,
                    episodeNumber = media.episodeNumber,
                )
            }.toList()

    private fun ceilLog2(value: Int): Int {
        var bits = 0
        while ((1L shl bits) < value) bits++
        return bits
    }

    private fun assertLogarithmic(
        mode: OrderingMode,
        items: List<ScheduleItem>,
        minimumBlocks: Int,
    ) {
        val settings =
            testSettings(orderingMode = mode, alignToGrid = false, epochHours = 24, startMinuteOfDay = 360, blockLength = 1)
        var comparisons = 0
        // A day long after every item was created, so the whole library is in the pool.
        val day = buildDayCounting(settings, items, 30_000L, onCompare = { comparisons++ })
        val blocks = day.blocks.size
        assertTrue(blocks >= minimumBlocks, "the day has only $blocks blocks")
        val allowed = ceilLog2(blocks) + SLACK
        val times =
            day.blocks.flatMap { listOf(it.startMs, it.endMs - 1) } + day.startMs + (day.endMs - day.startMs) / 2
        var worst = 0
        for (t in times) {
            comparisons = 0
            day.whatsOn(t, 3)
            worst = maxOf(worst, comparisons)
            assertTrue(comparisons <= allowed, "$comparisons comparisons for $blocks blocks at $t (allowed $allowed)")
        }
        assertTrue(worst >= ceilLog2(blocks) - 1, "the counter did not count: worst was $worst")
    }

    @Test
    fun aTenThousandItemLibraryOnItsOwnRuntimes() {
        // About 17 blocks in a 24-hour day: the library's programs average an hour and a half.
        assertLogarithmic(OrderingMode.Chronological, scheduleItems { (it.runtime?.inWholeMilliseconds ?: 0L) }, 10)
    }

    @Test
    fun aTenThousandItemLibraryWithShortProgramsGivesADayOfThousandsOfBlocks() {
        val short = scheduleItems { 1_000L + ((it.runtime?.inWholeMilliseconds ?: 0L) % 90_000L) }
        assertLogarithmic(OrderingMode.Shuffle, short, 1_000)
        assertLogarithmic(OrderingMode.RandomInOrder, short, 1_000)
    }

    private companion object {
        /** Binary search with the two checks of the final index: a constant, not a function of the day's size. */
        const val SLACK = 2
    }
}
