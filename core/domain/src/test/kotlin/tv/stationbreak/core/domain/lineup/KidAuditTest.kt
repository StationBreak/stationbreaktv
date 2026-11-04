// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.KidStatus.isKidChannel
import tv.stationbreak.core.engine.lineup.LineupChannel
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

    // The near misses, by the ID the seed gave them: an oracle that does not ask KidHold (the cases the signal must
    // refuse, and a live-action family G film, which is not made for kids by any list).
    private val nearMisses = mutableSetOf<CanonicalId>()

    // Made for kids, and over every kid ceiling: it may air on no channel at all (grown-up-hold-2).
    private val overEveryCeiling = mutableSetOf<CanonicalId>()

    private fun film(
        rating: String?,
        vararg genres: String,
        nearMiss: Boolean = false,
    ) {
        val entry = movie(next++, *genres, rating = rating)
        items += entry
        if (nearMiss) nearMisses += entry.item.id
    }

    private fun showOf(
        rating: String?,
        vararg genres: String,
        nearMiss: Boolean = false,
    ) {
        val showId = id(next++)
        shows[showId] = RuleSeries(showId, rating, genres.toList(), emptyList())
        repeat(EPISODES) {
            val kind = ScheduleItemKind.Episode
            val item = ScheduleItem(id(next++), kind, HALF_HOUR_MS, ADDED_MS, null, null, 2000, showId, 1, it + 1)
            items += RuleItem(item, LIBRARY_ID, null, null, genres.toList(), emptyList())
            if (nearMiss) nearMisses += item.id
        }
    }

    private fun seed() {
        repeat(FILMS) {
            film("G", "Animation", "Family")
            film("TV-G", "Animation", "Family")
            film(null, "Animation", "Family")
            film("PG", "Animation", "Family", nearMiss = true)
            film("PG-13", "Animation", "Family", "Comedy", nearMiss = true)
            film("R", "Animation", "Comedy")
            film("G", "Family", "Comedy", nearMiss = true)
            film("PG", "Family")
            film("G", "Animation", "Kids")
            film("TV-Y7", "Adventure")
            film("R", "Drama")
            film("R", "Kids")
            overEveryCeiling += items.last().item.id
        }
        showOf("TV-G", "Animation", "Family")
        showOf(null, "Animation", "Family")
        showOf("TV-14", "Animation", "Family", nearMiss = true)
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
                        assertTrue(scheduled.id !in nearMisses, "a seeded near miss on a kid channel: $where")
                        audited++
                        if (KidHold.isAnimatedFamily(entry, show)) animatedFamilyAired++
                    }
                }
            }
            assertTrue(audited > 0, "the audit saw nothing")
            assertTrue(animatedFamilyAired > 0, "the new signal never reached a kid channel")
        }

    @Test
    fun noGrownUpStarterOrLeftoversAirsAnythingMadeForKids() =
        runBlocking<Unit> {
            seed()
            val service = LineupService(RecordingStore(), library, clock, { 0 }, {}, StarterCatalog)
            service.refresh(ACCOUNT, LIBRARIES)
            val source = BuiltInScheduleSource(service, library) { LIBRARIES }
            val byId = items.associateBy { it.item.id }
            var audited = 0
            for (day in 0 until WEEK) {
                for (channelDay in source.channelDays(ACCOUNT, LIBRARIES, NOON + day.days)) {
                    if (!isGrownUpStarterOrLeftovers(channelDay.channel)) continue
                    for (scheduled in channelDay.pool) {
                        val entry = checkNotNull(byId[scheduled.id])
                        val show = scheduled.seriesId?.let { shows[it] }
                        val where = "${channelDay.name} day $day ${scheduled.id}"
                        assertTrue(!KidHold.isMadeForKids(entry, show), "made for kids on a grown-up channel: $where")
                        audited++
                    }
                }
            }
            assertTrue(audited > 0, "the audit saw nothing")
        }

    @Test
    fun aKidTitleThatFailsEveryCeilingAirsNowhere() =
        runBlocking<Unit> {
            seed()
            val service = LineupService(RecordingStore(), library, clock, { 0 }, {}, StarterCatalog)
            service.refresh(ACCOUNT, LIBRARIES)
            val source = BuiltInScheduleSource(service, library) { LIBRARIES }
            for (day in 0 until WEEK) {
                for (channelDay in source.channelDays(ACCOUNT, LIBRARIES, NOON + day.days)) {
                    for (scheduled in channelDay.pool) {
                        assertTrue(scheduled.id !in overEveryCeiling, "${channelDay.name} day $day airs it")
                    }
                }
            }
        }

    private fun isGrownUpStarterOrLeftovers(channel: LineupChannel): Boolean =
        !isKidChannel(channel) && (channel.source is ChannelSource.Starter || channel.source is ChannelSource.Leftovers)

    private companion object {
        const val FILMS = 4
        const val EPISODES = 6
        const val WEEK = 7
        const val HALF_HOUR_MS = 1_800_000L
    }
}
