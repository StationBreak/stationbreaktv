// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.rolled
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** "Use my collections" (spec 23): a lineup flag, on by default, starting at the next day boundary. */
class HouseholdSettingTest {
    private val dayMs = 86_400_000L
    private val atMs = NOON.toEpochMilliseconds() + 2 * 3_600_000L // 14:00; today began at 06:00
    private val tomorrow = atMs + dayMs

    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private var nowMs = atMs
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
        }
    private var service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val days = LineupDays(TestCatalog)

    // A household list and a server's box set, each of three films of a genre no recipe asks for.
    private val household = id(900)
    private val serverSet = id(901)
    private val householdFilms = (810..812).map { movie(it, "Drama", rating = "G") }
    private val serverFilms = (820..822).map { movie(it, "Drama", rating = "G") }
    private val items = fillingLibrary() + householdFilms + serverFilms

    private fun collectionOf(
        id: CanonicalId,
        films: List<RuleItem>,
        name: String,
    ) = RuleCollection(id, films.map { it.item.id }.toSet(), emptySet(), emptySet(), emptySet(), name)

    private fun snapshotOf(cutoff: Long) =
        RuleSnapshot(
            cutoff,
            setOf(LIBRARY_ID),
            items,
            emptyMap(),
            mapOf(
                household to collectionOf(household, householdFilms, "Box Set 2"),
                serverSet to collectionOf(serverSet, serverFilms, "Box Set 1 Collection"),
            ),
            collectionsSynced = true,
        )

    // The starter's own genre holds nothing: it airs the two collections.
    private fun lineupFor(user: UserId = UserId(USER.value)): Lineup {
        val laughs =
            starter(
                "laughs",
                110,
                ChannelState.On,
                rules("Laughs", Source.Genre("Unused"), Source.Collection(household), Source.Collection(serverSet)),
            )
        val channels = listOf(laughs, leftovers("odds-and-ends", 199, null))
        return Lineup(SERVER, CanonicalId.parse(user.value), 1, 0, true, channels)
    }

    private fun accountOf(user: UserId) = LibraryAccount(ServerId(SERVER.value), user)

    private val other = UserId("b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1")

    private suspend fun seed(
        account: LibraryAccount = ACCOUNT,
        lineup: Lineup = lineupFor(account.user),
    ) {
        store.inner.save(account, LineupCodec.encode(lineup))
    }

    private suspend fun flag(account: LibraryAccount = ACCOUNT) = service.useMyCollections(account).first()

    private suspend fun toggle(
        on: Boolean,
        account: LibraryAccount = ACCOUNT,
    ) = service.edit(account, LineupEdit.UseMyCollections(on))

    private fun poolAt(
        lineup: Lineup,
        at: Long,
    ): Set<CanonicalId> {
        val scheduled = days.scheduled(lineup, at, null)
        val snapshots = scheduled.map { it.cutoffMs }.distinct().associateWith { snapshotOf(it) }
        val all = days.days(lineup, scheduled, snapshots)
        return all
            .single { it.channel.id == LineupIds.starter("laughs") }
            .pool
            .map { it.id }
            .toSet()
    }

    private suspend fun held(account: LibraryAccount = ACCOUNT): Lineup = checkNotNull(service.loaded(account)).lineup

    private val householdIds = householdFilms.map { it.item.id }.toSet()
    private val serverIds = serverFilms.map { it.item.id }.toSet()

    @Test
    fun itDefaultsToOn() =
        runBlocking<Unit> {
            assertTrue(flag(accountOf(other)), "no lineup yet")
            seed()
            assertTrue(flag())
            assertEquals(householdIds + serverIds, poolAt(held(), atMs))
        }

    @Test
    fun offDropsTheHouseholdCollectionFromTomorrowsDays() =
        runBlocking<Unit> {
            seed()
            toggle(false)

            assertEquals(false, flag())
            assertEquals(serverIds, poolAt(held(), tomorrow), "the server's box set stays")
        }

    @Test
    fun householdToggleTakesEffectAtTheNextBoundary() =
        runBlocking<Unit> {
            seed()
            val before = poolAt(held(), atMs)

            toggle(false)

            val lineup = held()
            assertEquals(before, poolAt(lineup, atMs), "today's days are unchanged")
            assertEquals(householdIds + serverIds, poolAt(lineup, atMs))
            assertEquals(serverIds, poolAt(lineup, tomorrow), "tomorrow's days drop the household collection")
        }

    @Test
    fun backOnRestoresIt() =
        runBlocking<Unit> {
            seed()
            toggle(false)
            toggle(true)

            assertTrue(flag())
            assertEquals(householdIds + serverIds, poolAt(held(), tomorrow))
        }

    @Test
    fun itIsPerAccount() =
        runBlocking<Unit> {
            val second = accountOf(other)
            seed()
            seed(second)

            toggle(false)

            assertEquals(false, flag())
            assertTrue(flag(second), "the other account's flag is untouched")
        }

    @Test
    fun itSurvivesARestart() =
        runBlocking<Unit> {
            seed()
            toggle(false)

            service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)

            assertEquals(false, flag())
            nowMs = tomorrow
            assertEquals(serverIds, poolAt(held(), tomorrow))
        }

    // A play key holds only what changes the day played (PR 5 final round, item 2): a toggle waits for the boundary,
    // so today's key stays; the day after it, as the roll leaves the lineup, has another key, and the guide reloads.
    @Test
    fun togglingKeepsTodaysPlayKeyAndMovesTheKeyFromTheBoundary() =
        runBlocking<Unit> {
            seed()
            val laughs = LineupIds.starter("laughs")
            val before =
                service
                    .channels(ACCOUNT)
                    .first()
                    .first { it.id == laughs }
                    .playKey

            toggle(false)

            val today =
                service
                    .channels(ACCOUNT)
                    .first()
                    .first { it.id == laughs }
                    .playKey
            assertEquals(before, today, "the toggle starts at the boundary")
            val rolled = held().rolled(tomorrow)
            val channel = rolled.channels.first { it.id == laughs }
            assertNotEquals(before, days.playKeyOf(channel, rolled, tomorrow), "the day after the boundary differs")
        }
}
