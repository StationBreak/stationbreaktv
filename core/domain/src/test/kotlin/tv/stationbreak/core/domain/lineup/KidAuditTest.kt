// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.GrownUpHold
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.KidStatus.isKidChannel
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.StarterCatalogView
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The kid audit (spec 23, kid-hold-3): over a week of the shipped catalog's lineup (catalog 3, programmed), and of one
 * upgraded to it in the middle of a day, everything on every kid channel is made for kids and within the channel's
 * ceiling, whatever the library holds. The library mixes the animated-family signal
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

    // Made for kids, and over every kid ceiling (an unrated animated family film, and a film rated R in a kids genre):
    // no kid channel takes it, so a grown-up channel keeps it (grown-up-hold-2, option C).
    private val overEveryCeiling = mutableSetOf<CanonicalId>()

    // Made for kids by a list or the signal, and seeded as such: never on a grown-up channel.
    private val madeForKidsSeeded = mutableSetOf<CanonicalId>()

    // Family-friendly but not made for kids: allowed on a grown-up channel.
    private val liveActionFamilyG = mutableSetOf<CanonicalId>()

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
            madeForKidsSeeded += items.takeLast(3).map { entry -> entry.item.id }
            overEveryCeiling += items.last().item.id
            film("PG", "Animation", "Family", nearMiss = true)
            film("PG-13", "Animation", "Family", "Comedy", nearMiss = true)
            film("R", "Animation", "Comedy")
            film("G", "Family", "Comedy", nearMiss = true)
            liveActionFamilyG += items.last().item.id
            film("PG", "Family")
            film("G", "Animation", "Kids")
            film("TV-Y7", "Adventure")
            film("R", "Drama")
            film("R", "Kids")
            overEveryCeiling += items.last().item.id
            madeForKidsSeeded += items.last().item.id
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
                        if (scheduled.id in madeForKidsSeeded) animatedFamilyAired++
                    }
                }
            }
            assertTrue(audited > 0, "the audit saw nothing")
            assertTrue(animatedFamilyAired > 0, "the new signal never reached a kid channel")
        }

    @Test
    fun noGrownUpStarterOrLeftoversAirsAnythingAKidChannelCouldTake() =
        runBlocking<Unit> {
            seed()
            val service = LineupService(RecordingStore(), library, clock, { 0 }, {}, StarterCatalog)
            service.refresh(ACCOUNT, LIBRARIES)
            val source = BuiltInScheduleSource(service, library) { LIBRARIES }
            val byId = items.associateBy { it.item.id }
            var audited = 0
            var familyAired = 0
            for (day in 0 until WEEK) {
                for (channelDay in source.channelDays(ACCOUNT, LIBRARIES, NOON + day.days)) {
                    if (!isGrownUpStarterOrLeftovers(channelDay.channel)) continue
                    for (scheduled in channelDay.pool) {
                        val entry = checkNotNull(byId[scheduled.id])
                        val show = scheduled.seriesId?.let { shows[it] }
                        val where = "${channelDay.name} day $day ${scheduled.id}"
                        assertTrue(!GrownUpHold.dropsFromGrownUp(entry, show), "a kid channel could take it: $where")
                        assertTrue(
                            scheduled.id !in madeForKidsSeeded || scheduled.id in overEveryCeiling,
                            "seeded kids title a kid channel could take, on grown-up: $where",
                        )
                        if (scheduled.id in liveActionFamilyG) familyAired++
                        audited++
                    }
                }
            }
            assertTrue(audited > 0, "the audit saw nothing")
            assertTrue(familyAired > 0, "the live-action family G film aired on no grown-up channel")
        }

    @Test
    fun aKidTitleThatFailsEveryCeilingAirsOnGrownUpChannelsOnly() =
        runBlocking<Unit> {
            seed()
            val service = LineupService(RecordingStore(), library, clock, { 0 }, {}, StarterCatalog)
            service.refresh(ACCOUNT, LIBRARIES)
            val source = BuiltInScheduleSource(service, library) { LIBRARIES }
            val aired = HashSet<CanonicalId>()
            for (day in 0 until WEEK) {
                for (channelDay in source.channelDays(ACCOUNT, LIBRARIES, NOON + day.days)) {
                    val kid = isKidChannel(channelDay.channel)
                    for (scheduled in channelDay.pool) {
                        if (scheduled.id !in overEveryCeiling) continue
                        assertTrue(!kid, "${channelDay.name} day $day is a kid channel and airs it")
                        aired += scheduled.id
                    }
                }
            }
            assertEquals(overEveryCeiling, aired, "each title no kid channel can take airs on a grown-up channel")
        }

    // The shipped catalog with every recipe programmed: the day build curates every starter (spec 23). The curation
    // only takes items out of pools the holds already narrowed, so nothing else may reach a kid channel.
    @Test
    fun curatedKidChannelsStillAirOnlyMadeForKids() =
        runBlocking<Unit> {
            seed()
            val curated =
                object : StarterCatalogView by StarterCatalog {
                    override val recipes = StarterCatalog.recipes.map { it.copy(ordering = OrderingMode.Curated) }
                }
            val service = LineupService(RecordingStore(), library, clock, { 0 }, {}, curated)
            service.refresh(ACCOUNT, LIBRARIES)
            val source = BuiltInScheduleSource(service, library) { LIBRARIES }
            val ceilings = Ceilings(curated)
            val byId = items.associateBy { it.item.id }
            var audited = 0
            var weighted = 0
            var kidCurated = 0
            for (day in 0 until WEEK) {
                for (channelDay in source.channelDays(ACCOUNT, LIBRARIES, NOON + day.days)) {
                    if (!channelDay.weights.isEmpty) weighted++
                    if (!isKidChannel(channelDay.channel)) continue
                    // A kid starter's day with weights took part in the curation.
                    if (!channelDay.weights.isEmpty) kidCurated++
                    val ceiling = ceilings.of(channelDay.channel)
                    for (scheduled in channelDay.pool) {
                        val entry = checkNotNull(byId[scheduled.id])
                        val show = scheduled.seriesId?.let { shows[it] }
                        val where = "${channelDay.name} day $day ${scheduled.id}"
                        assertTrue(KidHold.isMadeForKids(entry, show), "not made for kids: $where")
                        assertTrue(KidHold.passesKidCeiling(entry, show, ceiling), "over its ceiling: $where")
                        assertTrue(scheduled.id !in nearMisses, "a seeded near miss on a kid channel: $where")
                        audited++
                    }
                }
            }
            assertTrue(audited > 0, "the audit saw nothing")
            assertTrue(weighted > 0, "no day was curated")
            assertTrue(kidCurated > 0, "no kid channel took part in the curation")
        }

    // Catalog 2's own recipes are test code of core:engine, out of reach here: its real kid rules on the day of the
    // swap are audited there (`LineupPlannerMigrationTest.kidChannelsOnlyEverSeeKidHoldThree`).
    // A lineup chosen under catalog 2 (a stand-in: the shipped recipes, plain) moves to catalog 3 at noon on day 0:
    // day 0 airs the old rules, days 1 to 6 the programmed ones. Every day, every kid channel airs only what is made
    // for kids and within its ceiling, and kid channels do take part in the curation once it applies.
    @Test
    fun aCatalogThreeLineupPassesTheKidAuditForSevenDays() =
        runBlocking<Unit> {
            seed()
            assertEquals(3, StarterCatalog.VERSION)
            val catalogTwo =
                object : StarterCatalogView by StarterCatalog {
                    override val version = 2
                    override val selectsByIdentity = false
                    override val recipes =
                        StarterCatalog.recipes.map { it.copy(theme = null, ordering = null, filters = null) }
                }
            val store = RecordingStore()
            val old = LineupService(store, library, clock, { 0 }, {}, catalogTwo)
            old.refresh(ACCOUNT, LIBRARIES)
            assertEquals(2, old.loaded(ACCOUNT)?.lineup?.catalogVersion)
            val service = LineupService(store, library, clock, { 0 }, {}, StarterCatalog)
            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(3, service.loaded(ACCOUNT)?.lineup?.catalogVersion)
            val source = BuiltInScheduleSource(service, library) { LIBRARIES }
            val ceilings = Ceilings(StarterCatalog)
            val byId = items.associateBy { it.item.id }
            var audited = 0
            var kidCurated = 0
            for (day in 0 until WEEK) {
                for (channelDay in source.channelDays(ACCOUNT, LIBRARIES, NOON + day.days)) {
                    if (!isKidChannel(channelDay.channel)) continue
                    if (!channelDay.weights.isEmpty) kidCurated++
                    val ceiling = ceilings.of(channelDay.channel)
                    for (scheduled in channelDay.pool) {
                        val entry = checkNotNull(byId[scheduled.id])
                        val show = scheduled.seriesId?.let { shows[it] }
                        val where = "${channelDay.name} day $day ${scheduled.id}"
                        assertTrue(KidHold.isMadeForKids(entry, show), "not made for kids: $where")
                        assertTrue(KidHold.passesKidCeiling(entry, show, ceiling), "over its ceiling: $where")
                        assertTrue(scheduled.id !in nearMisses, "a seeded near miss on a kid channel: $where")
                        audited++
                    }
                }
            }
            assertTrue(audited > 0, "the audit saw nothing")
            assertTrue(kidCurated > 0, "no kid channel took part in the curation")
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
