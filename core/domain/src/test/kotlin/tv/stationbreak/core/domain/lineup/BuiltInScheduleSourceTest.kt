// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class BuiltInScheduleSourceTest {
    private val library = CountingLibrary()
    private val store = RecordingStore()
    private var offset = 0
    private var now = NOON
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val events = mutableListOf<LineupEvent>()
    private val service = LineupService(store, library, clock, { offset }, { events += it }, TestCatalog)
    private var chosen: Set<ItemId>? = LIBRARIES
    private val source = BuiltInScheduleSource(service, library) { chosen }

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
    }

    /** 2026-10-19T06:00Z: the broadcast day that holds [NOON] at offset 0 starts then. */
    private val dayStart = Instant.parse("2026-10-19T06:00:00Z")

    /** Its cut-off: the day's start minus the default six-hour grace window. */
    private val cutoff = Instant.parse("2026-10-19T00:00:00Z")

    private fun freshSource(): BuiltInScheduleSource {
        val fresh = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
        return BuiltInScheduleSource(fresh, library) { LIBRARIES }
    }

    private fun holding(lineup: Lineup) = runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineup)) }

    private fun airings(at: Instant = NOON) = runBlocking { source.airings(at) }

    private fun pool(
        channel: ChannelId,
        at: Instant = NOON,
    ): Set<CanonicalId> =
        runBlocking { source.channelDays(ACCOUNT, LIBRARIES, at) }
            .first { it.channel.id == channel }
            .pool
            .mapTo(HashSet()) { it.id }

    private val oddsAndEnds = LineupIds.leftovers("odds-and-ends")
    private val kidsOddsAndEnds = LineupIds.leftovers("kids-odds-and-ends")

    private fun laughs(state: ChannelState = ChannelState.On): LineupChannel {
        val comedy = rules("Laughs", Source.Genre("Comedy"))
        return starter("laughs", 110, state, comedy)
    }

    private fun afterHours(state: ChannelState = ChannelState.Shelf) =
        starter("after-hours", 590, state, rules("After Hours", Source.Tag("late")))

    @Test
    fun starterChannelsShareOneSnapshotRead() =
        runBlocking<Unit> {
            library.seed(fillingLibrary())
            service.refresh(ACCOUNT, LIBRARIES)
            library.snapshotCutoffs.clear()

            val airings = source.airings(NOON)

            assertEquals(listOf(cutoff), library.snapshotCutoffs, "one ruleSnapshot for the whole lineup")
            assertEquals(0, library.scheduleItemsCalls, "never scheduleItems (#275)")
            val on = checkNotNull(service.lineup(ACCOUNT).value).lineup.channels.filter { it.state == ChannelState.On }
            assertEquals(on.mapNotNull { it.number }.sorted(), airings.map { it.number })
            assertTrue(airings.size > 3)
            assertTrue(airings.all { it.epochStart == dayStart })
        }

    @Test
    fun oneReadAfterClockChange() =
        runBlocking<Unit> {
            // Rockets does not fill yet, so it waits on the shelf.
            library.seed(fillingLibrary().filter { "Science Fiction" !in it.genres })
            library.seed((0 until 3).map { movie(300 + it, "Science Fiction", rating = "R") })
            service.refresh(ACCOUNT, LIBRARIES)

            // Summer time (or a move): the device's standard offset is now an hour east, and Rockets fills.
            offset = 60
            now = NOON + 24.hours
            library.seed((3 until 7).map { movie(300 + it, "Science Fiction", rating = "R") })
            service.refresh(ACCOUNT, LIBRARIES)
            library.snapshotCutoffs.clear()

            val airings = source.airings(now)

            assertTrue(airings.any { it.id == LineupIds.starter("rockets") }, "Rockets switched on")
            assertEquals(1, library.snapshotCutoffs.size, "every channel is still on the lineup's one offset")
            assertEquals(setOf(dayStart + 24.hours), airings.map { it.epochStart }.toSet())
        }

    @Test
    fun customChannelWithOtherDayStartAddsOneRead() {
        library.seed(fillingLibrary())
        val evening = rules("Evening", Source.Genre("Crime"), timing = timing(start = "18:00"))
        holding(
            lineupOf(
                laughs(),
                starter("frights", 310, ChannelState.On, rules("Frights", Source.Genre("Horror"))),
                channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(evening)),
            ),
        )

        val airings = airings()

        assertEquals(2, library.snapshotCutoffs.size)
        assertEquals(setOf(cutoff, Instant.parse("2026-10-18T12:00:00Z")), library.snapshotCutoffs.toSet())
        assertEquals(Instant.parse("2026-10-18T18:00:00Z"), airings.first { it.number == 600 }.epochStart)
        assertEquals(dayStart, airings.first { it.number == 110 }.epochStart)
    }

    @Test
    fun airingsCarryTheCeilingTierSoSpellingsOnOneTierAreEqual() {
        library.seed(fillingLibrary())

        fun custom(
            id: String,
            number: Int,
            maxRating: String?,
        ): LineupChannel {
            val ruled = rules(id, Source.Genre("Comedy"), maxRating = maxRating)
            return channel(id, number, ChannelState.On, ChannelSource.Custom(ruled))
        }
        holding(
            lineupOf(
                custom("a", 600, "TV-PG"),
                custom("b", 601, "PG"),
                custom("c", 602, "TV-Y"),
                custom("d", 603, null),
            ),
        )

        val ranks = airings().associate { it.number to it.ceilingRank }

        assertEquals(ChannelAiring.PG_TIER, ranks[600])
        assertEquals(ranks[600], ranks[601], "TV-PG and PG are one tier")
        assertEquals(0, ranks[602])
        assertNull(ranks[603], "no ceiling")
    }

    @Test
    fun airingsAtCrossesTheDayBoundary() {
        library.seed(fillingLibrary())
        holding(lineupOf(laughs()))
        val early = Instant.parse("2026-10-19T03:00:00Z")

        val before = airings(early).single()
        val after = airings(early + 6.hours).single()

        assertEquals(Instant.parse("2026-10-18T06:00:00Z"), before.epochStart)
        assertEquals(dayStart, after.epochStart)
        assertEquals(2, library.snapshotCutoffs.toSet().size, "each day reads its own cut-off")
    }

    @Test
    fun emptyChannelIsHiddenWithReason() {
        val western = Source.Genre("Western")
        library.delegate.setCollectionsSynced(ACCOUNT, false)
        library.seed(fillingLibrary())
        library.seed(movie(900, "Fresh", createdAtMs = NOON.toEpochMilliseconds()))
        holding(
            lineupOf(
                laughs(),
                starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"), maxRating = "G")),
                starter("westerns", 220, ChannelState.On, rules("W", western, exclusions = listOf(western))),
                starter("frights", 310, ChannelState.On, rules("Frights", Source.Genre("Nothing like it"))),
                starter("rockets", 320, ChannelState.On, rules("Rockets", Source.Collection(id(77)))),
                channel("custom-1", 600, ChannelState.On, ChannelSource.Custom(rules("Fresh", Source.Genre("Fresh")))),
            ),
        )

        val hidden = airings().associate { it.number to it.hidden }

        assertNull(hidden[110])
        assertEquals(HiddenReason.AllBlockedByCeiling, hidden[210])
        assertEquals(HiddenReason.AllExcluded, hidden[220])
        assertEquals(HiddenReason.NoMatches, hidden[310])
        assertEquals(HiddenReason.CollectionsNotSynced, hidden[320])
        assertEquals(HiddenReason.EmptyToday, hidden[600])
    }

    @Test
    fun aMissingCollectionAfterAFullPassMatchesNothing() {
        library.seed(fillingLibrary())
        library.delegate.setCollectionsSynced(ACCOUNT, true)
        holding(lineupOf(starter("rockets", 320, ChannelState.On, rules("Rockets", Source.Collection(id(77))))))

        assertEquals(HiddenReason.NoMatches, airings().single().hidden)
    }

    @Test
    fun aCollectionChannelAirsItsMembers() {
        library.seed(fillingLibrary())
        library.delegate.setCollectionsSynced(ACCOUNT, true)
        val collection = RuleCollection(id(77), setOf(id(100)), emptySet(), emptySet(), emptySet())
        library.delegate.seedCollection(ACCOUNT, collection)
        val rockets = starter("rockets", 320, ChannelState.On, rules("Rockets", Source.Collection(id(77))))
        holding(lineupOf(rockets))

        assertNull(airings().single().hidden)
        assertEquals(setOf(id(100)), pool(rockets.id))
    }

    @Test
    fun afterHoursContentNeverReachesLeftovers() {
        library.seed(movie(500, "Drama", rating = "TV-MA", tags = listOf("late")), movie(501, "Drama"))
        for (lineup in listOf(
            lineupOf(afterHours(), leftovers("odds-and-ends", 199, null)),
            // Not on the lineup at all: the catalog's recipe still claims it.
            lineupOf(leftovers("odds-and-ends", 199, null)),
            // Unreadable on the lineup: the catalog's recipe still claims it.
            lineupOf(
                channel(LineupIds.starter("after-hours").value, 590, ChannelState.Shelf, ChannelSource.Unusable("{}")),
                leftovers("odds-and-ends", 199, null),
            ),
        )) {
            holding(lineup)
            val fresh = freshSource()
            val odds = runBlocking { fresh.channelDays(ACCOUNT, LIBRARIES, NOON) }.single().pool.map { it.id }
            assertEquals(listOf(id(501)), odds, lineup.channels.map { it.id }.toString())
        }
    }

    @Test
    fun hiddenChannelContentNeverReachesLeftovers() {
        library.seed(fillingLibrary())
        holding(lineupOf(laughs(ChannelState.UserHidden), leftovers("odds-and-ends", 199, null)))

        val odds = pool(oddsAndEnds)

        assertTrue(odds.isNotEmpty())
        val comedy = fillingLibrary().filter { "Comedy" in it.genres }.map { it.item.id }
        assertTrue(comedy.none { it in odds }, "a hidden channel's content stays hidden")
    }

    @Test
    fun shelvedChannelContentGoesToLeftovers() {
        library.seed(fillingLibrary())
        holding(lineupOf(laughs(ChannelState.Shelf), leftovers("odds-and-ends", 199, null)))

        val comedy = fillingLibrary().filter { "Comedy" in it.genres }.map { it.item.id }
        assertTrue(pool(oddsAndEnds).containsAll(comedy))
    }

    @Test
    fun switchedOnChannelContentIsNotLeftOver() {
        library.seed(fillingLibrary())
        holding(lineupOf(laughs(), leftovers("odds-and-ends", 199, null)))

        val comedy = fillingLibrary().filter { "Comedy" in it.genres }.map { it.item.id }
        assertTrue(comedy.none { it in pool(oddsAndEnds) })
    }

    @Test
    fun kidsLeftoversItemNotOnGrownUpLeftovers() {
        library.seed(movie(600, "Puppets", rating = "G"), movie(601, "Thriller", rating = "R"))
        library.seed(movie(602, "Odd", rating = null))
        for (kidsState in listOf(ChannelState.On, ChannelState.UserHidden)) {
            val kids = leftovers("kids-odds-and-ends", 99, "PG", kidsState)
            holding(lineupOf(kids, leftovers("odds-and-ends", 199, null)))
            val fresh = freshSource()
            val days = runBlocking { fresh.channelDays(ACCOUNT, LIBRARIES, NOON) }.associateBy { it.channel.id }

            assertEquals(
                setOf(id(601), id(602)),
                days
                    .getValue(oddsAndEnds)
                    .pool
                    .map { it.id }
                    .toSet(),
                "$kidsState",
            )
            if (kidsState == ChannelState.On) {
                assertEquals(listOf(id(600)), days.getValue(kidsOddsAndEnds).pool.map { it.id }, "unrated stays off")
            }
        }
    }

    @Test
    fun airingNowhereIsZeroWithLeftoversOn() =
        runBlocking<Unit> {
            library.seed(fillingLibrary())
            library.seed(movie(600, "Puppets", rating = "G"), movie(601, "Thriller", rating = "R"))
            library.seed(movie(500, "Drama", rating = "TV-MA", tags = listOf("late")))
            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(0, service.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))

            // Without the leftovers channels the two orphans air nowhere; the opt-in item is held back on purpose.
            val lineup = checkNotNull(service.lineup(ACCOUNT).value).lineup
            holding(lineup.copy(channels = lineup.channels.filter { it.source !is ChannelSource.Leftovers }))
            val fresh = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
            assertEquals(2, fresh.airingNowhereCount(ACCOUNT, LIBRARIES, NOON))
        }

    @Test
    fun shelfAndUserHiddenAreNotScheduled() {
        library.seed(fillingLibrary())
        holding(
            lineupOf(
                laughs(),
                starter("whodunit", 210, ChannelState.Shelf, rules("Whodunit", Source.Genre("Crime"))),
                starter("frights", 310, ChannelState.UserHidden, rules("Frights", Source.Genre("Horror"))),
                starter("rockets", null, ChannelState.Shelf, rules("Rockets", Source.Genre("Science Fiction"))),
                channel("custom-9", 601, ChannelState.On, ChannelSource.Unusable("{}")),
            ),
        )

        assertEquals(listOf(110), airings().map { it.number })
    }

    @Test
    fun airingsCarryTheChannelsNameAndNumber() {
        library.seed(fillingLibrary())
        val renamed = laughs().copy(numberOverride = 120, nameOverride = "Chuckles")
        holding(lineupOf(renamed, leftovers("odds-and-ends", 199, null)))

        val airings = airings()

        assertEquals(listOf(120 to "Chuckles", 199 to "Odds"), airings.map { it.number to it.name })
        assertEquals(listOf(LineupIds.starter("laughs"), oddsAndEnds), airings.map { it.id })
    }

    @Test
    fun newerVersionStillAirsInMemoryLineup() =
        runBlocking<Unit> {
            library.seed(fillingLibrary())
            store.inner.save(ACCOUNT, """{"version":2}""".encodeToByteArray())
            service.refresh(ACCOUNT, LIBRARIES)

            assertTrue(source.airings(NOON).isNotEmpty())
            assertEquals(listOf(LineupEvent.NewerVersionLeftAlone), events)
        }

    @Test
    fun airingsAreForTheActiveAccountAndTheChosenLibraries() =
        runBlocking<Unit> {
            library.seed(fillingLibrary())
            service.refresh(ACCOUNT, LIBRARIES)
            assertTrue(source.airings(NOON).isNotEmpty(), "the active account's lineup")

            val other = LibraryAccount(ServerId(id(7).value), UserId(id(8).value))
            library.delegate.useAccount(other)
            assertEquals(emptyList(), source.airings(NOON), "the new active account has no lineup")

            library.delegate.useAccount(ACCOUNT)
            assertTrue(source.airings(NOON).isNotEmpty(), "back to the first account")
        }

    @Test
    fun noChosenLibrariesOrNoActiveAccountGivesNoAiringsAndNoRead() =
        runBlocking<Unit> {
            library.seed(fillingLibrary())
            service.refresh(ACCOUNT, LIBRARIES)
            library.snapshotCutoffs.clear()

            chosen = null
            assertEquals(emptyList(), source.airings(NOON))
            chosen = emptySet()
            assertEquals(emptyList(), source.airings(NOON))

            chosen = LIBRARIES
            library.delegate.forget(ACCOUNT)
            assertEquals(emptyList(), source.airings(NOON))
            assertEquals(emptyList(), library.snapshotCutoffs)
        }

    @Test
    fun noLineupGivesNoAiringsAndNoRead() {
        assertEquals(emptyList(), airings())
        assertEquals(emptyList(), library.snapshotCutoffs)
    }

    @Test
    fun aDayIsBuiltOncePerLineupRevision() =
        runBlocking<Unit> {
            library.seed(fillingLibrary())
            service.refresh(ACCOUNT, LIBRARIES)
            library.snapshotCutoffs.clear()

            source.airings(NOON)
            source.airings(NOON + 1.hours)
            assertEquals(1, library.snapshotCutoffs.size, "the same day is not read twice")

            service.refresh(ACCOUNT, LIBRARIES)
            library.snapshotCutoffs.clear()
            source.airings(NOON)
            assertEquals(1, library.snapshotCutoffs.size, "a new revision builds the day again")
            assertFalse(events.isNotEmpty())
        }
}
