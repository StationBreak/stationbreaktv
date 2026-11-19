// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A refresh whose save fails does not publish what it planned (#648, Phil's decision): the lineup that is still on
 * disk stays the one in use, so the screen matches what is saved, and the next refresh plans again.
 */
class LineupServiceFailedRefreshTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
    }

    private fun onDisk() = checkNotNull(store.inner.peek(ACCOUNT)).toList()

    @Test
    fun aRefreshWhoseSaveFailsKeepsTheOldLineupAndTheNextRefreshPlansAgain() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = checkNotNull(service.lineup(ACCOUNT).value)
            val file = onDisk()
            // The library moved on, so the next plan records a newer generation.
            library.delegate.noteContentChange(ACCOUNT)
            val generation = library.delegate.contentGeneration(ACCOUNT)
            assertTrue(generation != before.meta.plannedGeneration, "the library's generation moved")

            store.inner.failWrites = true
            service.refresh(ACCOUNT, LIBRARIES)
            store.inner.failWrites = false

            assertEquals(before, service.lineup(ACCOUNT).value, "the lineup in use is the one on disk")
            assertEquals(file, onDisk(), "nothing was written")

            service.refresh(ACCOUNT, LIBRARIES)

            val after = checkNotNull(service.lineup(ACCOUNT).value)
            assertTrue(after.revision > before.revision, "the retry published")
            assertEquals(generation, after.meta.plannedGeneration, "and planned against the newer library")
            assertTrue(file != onDisk(), "and saved")
        }

    @Test
    fun aRebuildWhoseSaveFailsChangesNothingAndSaysSo() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val before = checkNotNull(service.lineup(ACCOUNT).value)
            val file = onDisk()

            store.inner.failWrites = true
            val rebuilt = service.rebuildToday(ACCOUNT, LIBRARIES)
            store.inner.failWrites = false

            assertFalse(rebuilt, "the rebuild did not happen")
            assertEquals(before, service.lineup(ACCOUNT).value)
            assertEquals(file, onDisk())
        }

    @Test
    fun aFirstRefreshWhoseSaveFailsStillPublishesWhatItPlanned() =
        runBlocking<Unit> {
            store.inner.failWrites = true
            service.refresh(ACCOUNT, LIBRARIES)
            store.inner.failWrites = false

            // There is no lineup on disk to fall back to, so what was planned is what the screen gets.
            assertNotNull(service.lineup(ACCOUNT).value)
        }
}
