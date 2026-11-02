// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.EditOutcome
import tv.stationbreak.core.engine.lineup.EditRefusal
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** Edits and resets through the service (spec 21): under the account's lock, never losing the lineup. */
class LineupServiceEditTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)

    private val laughs = LineupIds.starter("laughs")
    private val whodunit = LineupIds.starter("whodunit")

    private fun threeOn(): Lineup =
        lineupOf(
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
            starter("frights", 310, ChannelState.On, rules("Frights", Source.Genre("Horror"))),
        )

    private suspend fun seedStored(lineup: Lineup = threeOn()) {
        store.inner.save(ACCOUNT, LineupCodec.encode(lineup))
        store.calls.clear()
    }

    private fun stored(): Lineup? {
        val bytes = store.inner.peek(ACCOUNT) ?: return null
        return (LineupCodec.decode(bytes) as? LineupCodec.Decoded.Loaded)?.lineup
    }

    // The edit's outcome, or null when the service gave none or its save failed.
    private suspend fun edit(change: LineupEdit): EditOutcome? = (service.edit(ACCOUNT, change) as? Saved.Ok)?.value

    // [lineup] with every channel but [keep] put on the shelf.
    private fun onlyOn(
        lineup: Lineup,
        keep: ChannelId,
    ): Lineup {
        val channels = lineup.channels.map { if (it.id == keep) it else it.copy(state = ChannelState.Shelf) }
        return lineup.copy(channels = channels)
    }

    private fun state(
        lineup: Lineup?,
        id: ChannelId,
    ) = lineup?.channels?.firstOrNull { it.id == id }?.state

    @Test
    fun anAppliedEditIsSavedAndPublishedWithANewRevision() =
        runBlocking<Unit> {
            seedStored()
            val before = checkNotNull(service.loaded(ACCOUNT))

            val outcome = edit(LineupEdit.SwitchOff(laughs))

            assertIs<EditOutcome.Applied>(outcome)
            val after = checkNotNull(service.lineup(ACCOUNT).value)
            assertTrue(after.revision > before.revision)
            assertEquals(ChannelState.UserHidden, state(after.lineup, laughs))
            assertEquals(ChannelState.UserHidden, state(stored(), laughs), "and saved")
            assertTrue(after.writable)
        }

    @Test
    fun aRefusalPublishesNothing() =
        runBlocking<Unit> {
            seedStored(onlyOn(threeOn(), laughs))
            val before = checkNotNull(service.loaded(ACCOUNT))
            store.calls.clear()

            val outcome = edit(LineupEdit.SwitchOff(laughs))

            assertEquals(EditOutcome.Refused(EditRefusal.LastOn), outcome)
            assertEquals(before, service.lineup(ACCOUNT).value)
            assertFalse("save" in store.calls, "calls were ${store.calls}")
        }

    @Test
    fun aNewerAppsFileIsNeverWrittenAndEditReturnsNull() =
        runBlocking<Unit> {
            val newer = """{"version":2,"whatever":"a newer app wrote"}""".encodeToByteArray()
            store.inner.save(ACCOUNT, newer)
            store.calls.clear()
            service.refresh(ACCOUNT, LIBRARIES)
            val before = service.lineup(ACCOUNT).value

            val outcome = service.edit(ACCOUNT, LineupEdit.SwitchOff(laughs))

            assertNull(outcome)
            assertEquals(before, service.lineup(ACCOUNT).value, "nothing published")
            assertTrue("save" !in store.calls && "setAside" !in store.calls, "calls were ${store.calls}")
            assertFalse(checkNotNull(before).writable)
        }

    @Test
    fun aFailedSaveOnEditPublishesNothing() =
        runBlocking<Unit> {
            seedStored()
            val before = checkNotNull(service.loaded(ACCOUNT))
            store.inner.failWrites = true

            assertEquals(Saved.Failed, service.edit(ACCOUNT, LineupEdit.SwitchOff(laughs)))

            assertEquals(before, service.lineup(ACCOUNT).value, "the lineup in use is the one on disk")
            store.inner.failWrites = false
            assertIs<EditOutcome.Applied>(edit(LineupEdit.SwitchOff(whodunit)))
            val held = checkNotNull(service.lineup(ACCOUNT).value).lineup
            assertEquals(ChannelState.On, state(held, laughs), "the failed edit was not kept")
            assertEquals(held, stored())
        }

    @Test
    fun aFailedSaveOnResetKeepsTheOldLineupAndDays() =
        runBlocking<Unit> {
            seedStored()
            val days = MemoryDayStore()
            days.write(ACCOUNT, "day", byteArrayOf(1))
            val withDays = LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog, derived = days))
            val before = checkNotNull(withDays.loaded(ACCOUNT))
            store.inner.failWrites = true

            assertEquals(Saved.Failed, withDays.reset(ACCOUNT, LIBRARIES))

            assertEquals(before, withDays.lineup(ACCOUNT).value)
            assertEquals(listOf("day"), days.names(ACCOUNT), "the days built from the old lineup stay")
            assertEquals(before.lineup, stored())
        }

    @Test
    fun aChangeSurvivesTheNextRefresh() =
        runBlocking<Unit> {
            seedStored()
            service.loaded(ACCOUNT)
            assertIs<EditOutcome.Applied>(edit(LineupEdit.SwitchOff(laughs)))

            service.refresh(ACCOUNT, LIBRARIES)

            assertEquals(ChannelState.UserHidden, state(checkNotNull(service.lineup(ACCOUNT).value).lineup, laughs))
            assertEquals(ChannelState.UserHidden, state(stored(), laughs))
        }

    @Test
    fun aNoOpEditPublishesNothing() =
        runBlocking<Unit> {
            seedStored()
            val before = checkNotNull(service.loaded(ACCOUNT))
            store.calls.clear()

            assertIs<EditOutcome.Applied>(edit(LineupEdit.SwitchOn(laughs)))

            assertEquals(before, service.lineup(ACCOUNT).value)
            assertFalse("save" in store.calls, "calls were ${store.calls}")
        }

    @Test
    fun resetNeverPublishesNoLineup() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val seen = mutableListOf<Revisioned?>()
            val watcher = launch { service.lineup(ACCOUNT).collect { seen += it } }
            delay(50)

            val fresh = service.reset(ACCOUNT, LIBRARIES)
            delay(50)
            watcher.cancel()

            assertIs<Saved.Ok<Lineup>>(fresh)
            assertTrue(seen.isNotEmpty() && seen.none { it == null }, "saw $seen")
            assertEquals(fresh.value, service.lineup(ACCOUNT).value?.lineup)
            assertEquals(fresh.value, stored())
        }

    @Test
    fun resetCompletesWhenItsCallerIsCancelled() =
        runBlocking<Unit> {
            seedStored()
            val gated = GatedLibrary(library)
            val locked = LineupService(store, gated, clock, { 0 }, {}, TestCatalog)
            locked.loaded(ACCOUNT)

            val caller = launch { locked.reset(ACCOUNT, LIBRARIES) }
            withTimeout(WAIT_MS) { gated.entered.await() }
            caller.cancel()
            gated.release.complete(Unit)
            withTimeout(WAIT_MS) { caller.join() }

            val published = checkNotNull(locked.lineup(ACCOUNT).value).lineup
            // The seeded lineup has three starters and no leftovers channel; a fresh choice has the catalog's.
            assertTrue(
                published.channels.any { it.id == LineupIds.leftovers("odds-and-ends") },
                "the reset ran to its end",
            )
            assertEquals(published, stored())
        }

    @Test
    fun writableIsPublishedWithTheLineup() =
        runBlocking<Unit> {
            seedStored()
            assertTrue(checkNotNull(service.loaded(ACCOUNT)).writable, "a readable lineup of this version")
            service.refresh(ACCOUNT, LIBRARIES)
            assertTrue(checkNotNull(service.lineup(ACCOUNT).value).writable)

            val other = LibraryAccount(ACCOUNT.server, UserId(id(9).value))
            store.inner.save(other, """{"version":2}""".encodeToByteArray())
            service.refresh(other, LIBRARIES)
            assertTrue(service.lineup(other).value?.writable != true)
        }

    @Test
    fun anEditDuringARefreshKeepsBoth() =
        runBlocking<Unit> {
            // From an older catalog, so the refresh has something of its own to add.
            seedStored(threeOn().copy(catalogVersion = 0))
            val gated = GatedLibrary(library)
            val locked = LineupService(store, gated, clock, { 0 }, {}, TestCatalog)
            val loaded = checkNotNull(locked.loaded(ACCOUNT))

            val refresh = launch { locked.refresh(ACCOUNT, LIBRARIES) }
            withTimeout(WAIT_MS) { gated.entered.await() }
            val edit = launch { locked.edit(ACCOUNT, LineupEdit.SwitchOff(laughs)) }
            delay(200)
            assertEquals(loaded, locked.lineup(ACCOUNT).value, "the edit waits for the refresh")
            gated.release.complete(Unit)
            withTimeout(WAIT_MS) { joinAll(refresh, edit) }

            val after = checkNotNull(locked.lineup(ACCOUNT).value)
            assertEquals(loaded.revision + 2, after.revision, "the refresh published, then the edit")
            assertEquals(ChannelState.UserHidden, state(after.lineup, laughs), "the edit is on the planner's lineup")
            assertEquals(1, after.lineup.catalogVersion, "and the planner's pass")
            assertTrue(after.lineup.channels.any { it.id == LineupIds.leftovers("odds-and-ends") })
            assertEquals(after.lineup, stored())
        }

    @Test
    fun editableIsFalseWithNoLineup() =
        runBlocking<Unit> {
            val editable = ChannelManager(service, { LIBRARIES }, clock).editable(ACCOUNT).first()
            assertFalse(editable)
        }
}

private const val WAIT_MS = 10_000L
