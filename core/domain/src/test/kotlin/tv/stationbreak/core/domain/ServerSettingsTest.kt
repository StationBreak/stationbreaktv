// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerSettingsTest {
    private val https = ServerAddress(Scheme.Https, "nas.example.test", 8920)
    private val http = ServerAddress(Scheme.Http, "nas.example.test", 8096)
    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 3 })

    @Test
    fun aPinIsNeverMadeOverHttp() {
        assertFailsWith<IllegalArgumentException> { ServerRecord(http, pin) }
        assertEquals(http, ServerRecord(http).address)
    }

    @Test
    fun theRecordShowsNeitherTheAddressNorThePin() {
        val text = ServerRecord(https, pin).toString()
        assertFalse("nas.example.test" in text, text)
        assertFalse(pin.hex in text, text)
    }

    @Test
    fun clearingThePinKeepsTheAddress() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(https, pin))
            assertTrue(settings.clearPin())
            assertEquals(ServerRecord(https, null), settings.load())
            assertEquals(1, settings.pinsCleared)
        }

    @Test
    fun clearingWhenThereIsNoPinOrNoRecordChangesNothing() =
        runBlocking {
            val none = InMemoryServerSettings()
            assertTrue(none.clearPin())
            assertNull(none.load())
            val noPin = InMemoryServerSettings(ServerRecord(https))
            assertTrue(noPin.clearPin())
            assertEquals(0, noPin.pinsCleared)
        }

    @Test
    fun saveReplacesTheWholeRecordAtOnce() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(https, pin))
            val other = ServerAddress(Scheme.Https, "other.example.test", 443)
            assertTrue(settings.save(ServerRecord(other)))
            assertEquals(ServerRecord(other, null), settings.load())
        }

    @Test
    fun aFailedWriteReportsFalseAndKeepsWhatWasThere() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(https, pin))
            settings.failSave = true
            assertFalse(settings.save(ServerRecord(https)))
            assertFalse(settings.clearPin())
            assertEquals(ServerRecord(https, pin), settings.load())
        }

    private val movies = ItemId("movies")
    private val shows = ItemId("shows")

    @Test
    fun theLibrarySelectionIsStoredWithTheRecordAndLoadsBack() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(https))
            assertNull(settings.loadLibraries())
            assertTrue(settings.saveLibraries(setOf(movies, shows)))
            assertEquals(setOf(movies, shows), settings.loadLibraries())
        }

    @Test
    fun aSelectionNeedsARecordAndIsNeverEmpty() =
        runBlocking {
            assertFalse(InMemoryServerSettings().saveLibraries(setOf(movies)))
            val settings = InMemoryServerSettings(ServerRecord(https))
            assertFalse(settings.saveLibraries(emptySet()))
            assertNull(settings.loadLibraries())
        }

    @Test
    fun theSelectionSurvivesClearingThePinAndSavingTheSameAddress() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(https, pin), setOf(movies))
            assertTrue(settings.clearPin())
            assertEquals(setOf(movies), settings.loadLibraries())
            assertTrue(settings.save(ServerRecord(https, pin)))
            assertEquals(setOf(movies), settings.loadLibraries())
        }

    @Test
    fun theSelectionIsDroppedWhenAnotherServerIsSaved() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(https), setOf(movies))
            assertTrue(settings.save(ServerRecord(ServerAddress(Scheme.Https, "other.example.test", 443))))
            assertNull(settings.loadLibraries())
        }

    @Test
    fun aFailedSelectionWriteKeepsTheOldSelection() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(https), setOf(movies))
            settings.failSave = true
            assertFalse(settings.saveLibraries(setOf(shows)))
            assertEquals(setOf(movies), settings.loadLibraries())
        }

    @Test
    fun clearingTheSelectionKeepsTheRecordAndItsPin() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(https, pin), setOf(movies))

            assertTrue(settings.clearLibraries())

            assertNull(settings.loadLibraries())
            assertEquals(ServerRecord(https, pin), settings.load())
        }

    @Test
    fun clearingWithNoSelectionOrNoRecordIsAnAnswerOfYes() =
        runBlocking {
            assertTrue(InMemoryServerSettings().clearLibraries())
            assertTrue(InMemoryServerSettings(ServerRecord(https)).clearLibraries())
        }

    @Test
    fun aFailedClearKeepsTheSelection() =
        runBlocking {
            val settings = InMemoryServerSettings(ServerRecord(https), setOf(movies))
            settings.failSave = true
            assertFalse(settings.clearLibraries())
            assertEquals(setOf(movies), settings.loadLibraries())
        }
}
