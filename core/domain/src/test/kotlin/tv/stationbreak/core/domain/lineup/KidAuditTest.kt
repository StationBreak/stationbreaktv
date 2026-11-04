// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.lineup.KidStatus.isKidChannel
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The kid audit (spec 23, kid-hold-3): over a week of the catalog-2 lineup, everything on every kid channel is made
 * for kids and within the channel's ceiling, whatever the library holds. The library mixes the animated-family signal
 * with its near misses (live action, no Family, PG and above, a grown-up series). Invented names only.
 */
class KidAuditTest {
    private val library = CountingLibrary()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }

    private val items = mutableListOf<RuleItem>()
    private val shows = mutableMapOf<CanonicalId, RuleSeries>()
    private var next = 1

    private fun film(
        rating: String?,
        vararg genres: String,
    ) {
        items += movie(next++, *genres, rating = rating)
    }

    private fun showOf(
        rating: String?,
        vararg genres: String,
    ) {
        val showId = id(next++)
        shows[showId] = RuleSeries(showId, rating, genres.toList(), emptyList())
        repeat(EPISODES) {
            val kind = ScheduleItemKind.Episode
            val item = ScheduleItem(id(next++), kind, HALF_HOUR_MS, ADDED_MS, null, null, 2000, showId, 1, it + 1)
            items += RuleItem(item, LIBRARY_ID, null, null, genres.toList(), emptyList())
        }
    }

    private fun seed() {
        repeat(FILMS) {
            film("G", "Animation", "Family")
            film("TV-G", "Animation", "Family")
            film(null, "Animation", "Family")
            film("PG", "Animation", "Family")
            film("PG-13", "Animation", "Family", "Comedy")
            film("R", "Animation", "Comedy")
            film("G", "Family", "Comedy")
            film("PG", "Family")
            film("G", "Animation", "Kids")
            film("TV-Y7", "Adventure")
            film("R", "Drama")
        }
        showOf("TV-G", "Animation", "Family")
        showOf(null, "Animation", "Family")
        showOf("TV-14", "Animation", "Family")
        showOf("TV-Y7", "Animation")
        showOf("TV-PG", "Family", "Comedy")
        library.seed(items)
        library.seedSeries(*shows.values.toTypedArray())
    }

    @Test
    fun everyItemOnEveryKidChannelIsMadeForKidsAndWithinItsCeiling() =
        runBlocking<Unit> {
            seed()
            val service = LineupService(RecordingStore(), library, clock, { 0 }, {}, StarterCatalog)
            service.refresh(ACCOUNT, LIBRARIES)
            val source = BuiltInScheduleSource(service, library) { LIBRARIES }
            val ceilings = Ceilings(StarterCatalog)
            val byId = items.associateBy { it.item.id }
            var audited = 0
            var animatedFamilyAired = 0
            for (day in 0 until WEEK) {
                for (channelDay in source.channelDays(ACCOUNT, LIBRARIES, NOON + day.days)) {
                    if (!isKidChannel(channelDay.channel)) continue
                    val ceiling = ceilings.of(channelDay.channel)
                    for (scheduled in channelDay.pool) {
                        val entry = checkNotNull(byId[scheduled.id])
                        val show = scheduled.seriesId?.let { shows[it] }
                        val where = "${channelDay.name} day $day ${scheduled.id}"
                        assertTrue(KidHold.isMadeForKids(entry, show), "not made for kids: $where")
                        assertTrue(KidHold.passesKidCeiling(entry, show, ceiling), "over its ceiling: $where")
                        audited++
                        if (KidHold.isAnimatedFamily(entry, show)) animatedFamilyAired++
                    }
                }
            }
            assertTrue(audited > 0, "the audit saw nothing")
            assertTrue(animatedFamilyAired > 0, "the new signal never reached a kid channel")
        }

    private companion object {
        const val FILMS = 4
        const val EPISODES = 6
        const val WEEK = 7
        const val HALF_HOUR_MS = 1_800_000L
    }
}
