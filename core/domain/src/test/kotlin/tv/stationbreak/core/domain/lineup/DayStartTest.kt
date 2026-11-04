// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.LibraryNames
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.isDayStart
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.scheduler.Epochs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The engine's `isDayStart` (which `withNewRules` checks a boundary with) agrees with the scheduler's `Epochs`, which
 * the service works the boundary out with: the engine cannot see the scheduler, so the two are compared here.
 */
class DayStartTest {
    // Whole, negative, half-hour and three-quarter-hour offsets, at both ends of the range.
    private val offsets = listOf(-720, -570, -300, -210, 0, 330, 345, 570, 765, 840)

    // Around the daylight-saving changes of 2026 (US 8 March and 1 November, EU 29 March and 25 October), the turn of
    // the year and a leap day; a rules offset is fixed, so none of them may move a day start.
    private val dates =
        listOf(
            "2026-03-07T12:00:00Z",
            "2026-03-08T07:30:00Z",
            "2026-03-29T00:59:00Z",
            "2026-03-29T01:00:00Z",
            "2026-10-25T00:30:00Z",
            "2026-10-25T01:00:00Z",
            "2026-11-01T06:00:00Z",
            "2026-11-01T09:00:00Z",
            "2026-12-31T23:59:59Z",
            "2028-02-29T05:45:00Z",
        ).map { Instant.parse(it).toEpochMilliseconds() }

    private val minute = 60_000L

    @Test
    fun isDayStartAgreesWithTheSchedulersEpochStarts() {
        for (offset in offsets) {
            val rules = rules("Day", Source.Genre("Comedy"), timing = timing(offset))
            val settings = rules.toSchedulerSettings()
            for (t in dates) {
                val n = Epochs.epochNumber(settings, t)
                for (start in listOf(Epochs.epochStart(settings, n), Epochs.epochStart(settings, n + 1))) {
                    val at = "offset $offset, ${Instant.fromEpochMilliseconds(start)}"
                    assertEquals(true, isDayStart(rules, start), at)
                    assertEquals(false, isDayStart(rules, start - minute), at)
                    assertEquals(false, isDayStart(rules, start + minute), at)
                    assertEquals(false, isDayStart(rules, start + 3_600_000L), at)
                    assertEquals(false, isDayStart(rules, start - 1L), at)
                }
                // And the other way round: t is a day start exactly when Epochs puts one there.
                assertEquals(Epochs.epochStart(settings, n) == t, isDayStart(rules, t), "offset $offset at $t")
            }
        }
    }

    // The boundary the service passes to the planner (from a leftovers channel's settings) is a day start of every
    // starter's resolved rules, at every offset.
    @Test
    fun theServicesBoundaryIsADayStartOfAResolvedStarter() {
        val names = CollectionNames(emptyMap())
        val library = LibraryNames(RuleSnapshot(0L, emptySet(), emptyList(), emptyMap(), emptyMap(), true), names)
        for (offset in offsets) {
            val service = LineupService(RecordingStore(), CountingLibrary(), Clock.System, { offset }, {}, TestCatalog)
            for (recipe in TestCatalog.recipes) {
                val rules = RecipeResolver.resolve(recipe, SERVER, offset, names, library)
                for (t in dates) {
                    val boundary = service.nextBoundary(null, t)
                    assertEquals(true, isDayStart(rules, boundary), "${recipe.key}, offset $offset after $t")
                    assertEquals(true, boundary > t)
                    assertEquals(true, boundary - t <= DAY_MS, "the next one, not a later one")
                }
            }
        }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
