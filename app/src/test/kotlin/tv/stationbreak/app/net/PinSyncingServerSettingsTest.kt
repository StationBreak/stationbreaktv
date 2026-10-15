// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.data.jellyfin.trust.PinnedHttpClients
import tv.stationbreak.data.jellyfin.trust.SystemTrust

/**
 * The stored pin and the pinned client move together (spec 05, Unit 3): a pin that is saved, replaced or cleared
 * reaches the client before the call returns, and a stored pin is applied by the first read.
 */
class PinSyncingServerSettingsTest {
    private val address = ServerAddress(Scheme.Https, "nas.example.test", 8920)
    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 1 })
    private val other = SpkiPin(ByteArray(SpkiPin.SIZE) { 2 })

    private val clients = PinnedHttpClients(SystemTrust { _, _, _ -> })
    private val stored = InMemoryServerSettings()
    private val settings = PinSyncingServerSettings(stored, clients)

    @Test
    fun savingAPinBuildsANewClient() =
        runBlocking {
            val before = clients.current()
            assertTrue(settings.save(ServerRecord(address, pin)))
            assertNotSame(before, clients.current())
            assertEquals(ServerRecord(address, pin), stored.current)
        }

    @Test
    fun savingTheSamePinAgainChangesNothing() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            val built = clients.current()
            settings.save(ServerRecord(address, pin))
            assertSame(built, clients.current())
        }

    @Test
    fun replacingAPinBuildsANewClient() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            val built = clients.current()
            settings.save(ServerRecord(address, other))
            assertNotSame(built, clients.current())
        }

    @Test
    fun clearingThePinBuildsANewClientAndKeepsTheAddress() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            val built = clients.current()
            assertTrue(settings.clearPin())
            assertNotSame(built, clients.current())
            assertEquals(ServerRecord(address, null), stored.current)
        }

    @Test
    fun theClientIsClearedEvenWhenTheWriteFails() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            val built = clients.current()
            stored.failSave = true
            assertEquals(false, settings.clearPin())
            assertNotSame(built, clients.current())
        }

    @Test
    fun savingARecordWithoutAPinDropsTheOldPin() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            val built = clients.current()
            settings.save(ServerRecord(address))
            assertNotSame(built, clients.current())
        }

    @Test
    fun loadingAppliesTheStoredPinBeforeReturning() =
        runBlocking {
            val fresh = PinnedHttpClients(SystemTrust { _, _, _ -> })
            val before = fresh.current()
            val loaded = PinSyncingServerSettings(InMemoryServerSettings(ServerRecord(address, pin)), fresh).load()
            assertEquals(ServerRecord(address, pin), loaded)
            assertNotSame(before, fresh.current())
        }

    @Test
    fun loadingNothingLeavesNoPin() =
        runBlocking {
            val before = clients.current()
            assertEquals(null, settings.load())
            assertSame(before, clients.current())
        }

    @Test
    fun aFailedSaveLeavesTheClientTrustingOnlyWhatIsStored() =
        runBlocking {
            val before = clients.current()
            stored.failSave = true
            assertFalse(settings.save(ServerRecord(address, pin)))
            // Nothing was stored, so the pin is not in force (the client may be rebuilt, but holds no pin).
            assertNull(clients.pinFor(address))
            assertSame(before.sslSocketFactory, clients.current().sslSocketFactory)
        }

    /** Writes at once, then waits for [gate] before answering: the window in which another operation can slip in. */
    private class SlowSave(
        private val inner: InMemoryServerSettings,
        val gate: CompletableDeferred<Unit>,
    ) : ServerSettings by inner {
        override suspend fun save(record: ServerRecord): Boolean {
            val written = inner.save(record)
            gate.await()
            return written
        }
    }

    @Test
    fun aClearRacingASaveCannotLeaveTheClientTrustingWhatTheDiskDropped() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val racing = PinSyncingServerSettings(SlowSave(stored, gate), clients)

            val saving = launch(start = CoroutineStart.UNDISPATCHED) { racing.save(ServerRecord(address, pin)) }
            val clearing = launch(start = CoroutineStart.UNDISPATCHED) { racing.clearPin() }
            gate.complete(Unit)
            saving.join()
            clearing.join()

            assertEquals(stored.current?.pin, clients.pinFor(address))
            assertNull(stored.current?.pin)
        }

    @Test
    fun theLibrarySelectionPassesThroughAndLeavesTheClientAlone() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            val built = clients.current()
            assertTrue(settings.saveLibraries(setOf(ItemId("movies"))))
            assertEquals(setOf(ItemId("movies")), settings.loadLibraries())
            assertEquals(setOf(ItemId("movies")), stored.currentLibraries)
            assertSame(built, clients.current())
        }

    @Test
    fun clearingTheSelectionPassesThroughAndLeavesTheClientAlone() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            settings.saveLibraries(setOf(ItemId("movies")))
            val built = clients.current()

            assertTrue(settings.clearLibraries())

            assertNull(stored.currentLibraries)
            assertSame(built, clients.current())
        }
}
