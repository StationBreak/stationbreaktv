// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.LIBRARIES
import tv.stationbreak.core.domain.lineup.LineupCodec
import tv.stationbreak.core.domain.lineup.LineupSeams
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.NOON
import tv.stationbreak.core.domain.lineup.RecordingStore
import tv.stationbreak.core.domain.lineup.SavedSelection
import tv.stationbreak.core.domain.lineup.TestCatalog
import tv.stationbreak.core.domain.lineup.fillingLibrary
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.domain.lineup.savedSelectionOf
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The app's saved-selection provider (spec 26 Part 4, §3): one lock-free selection read of the settings gives the
 * chosen libraries, the seq and the reset seq saved with them, and the playable ones (null when gone, never the adds).
 * They reach the schedule source and, through it, the service's prune and trim ([LineupService.savedSeqFrom]).
 */
internal class SavedSelectionProviderTest : RepairTestBase() {
    private val address = ServerAddress(Scheme.Https, "server.example.test", 8920)

    private fun settingsWith(selection: StoredSelection?): InMemoryServerSettings {
        val settings = InMemoryServerSettings(ServerRecord(address))
        if (selection != null) assertTrue(runBlocking { settings.saveSelection(selection) })
        return settings
    }

    @Test
    fun theProvider_carriesTheSeq_theResetSeq_andTheChosenAsPlayable_neverTheAdds() {
        val stored = StoredSelection(setOf(libA, libB), seq = 7L, adding = mapOf(libC to 6L), resetSeq = 4L)
        val read = runBlocking { savedSelectionOf(settingsWith(stored))() }

        assertEquals(SavedSelection(setOf(libA, libB), seq = 7L, playable = setOf(libA, libB), resetSeq = 4L), read)
    }

    @Test
    fun aGoneSelection_isStillGiven_withNothingPlayable() {
        val stored = StoredSelection(setOf(libA), seq = 3L, gone = true, resetSeq = 2L)
        val read = assertNotNull(runBlocking { savedSelectionOf(settingsWith(stored))() })

        assertEquals(setOf(libA), read.libraries)
        assertEquals(3L, read.seq)
        assertEquals(2L, read.resetSeq)
        assertNull(read.playable)
    }

    @Test
    fun noSelection_isNull() {
        assertNull(runBlocking { savedSelectionOf(settingsWith(null))() })
    }

    @Test
    fun theSeqAndTheResetSeq_reachTheSource() {
        // A selection saved with no seq yet.
        val settings = InMemoryServerSettings(ServerRecord(address), setOf(libA, libB))
        val rig =
            Rig(
                lineupOf(laughs, whodunit),
                comedies + crimes + comediesB,
                setOf(libA, libB),
                settings = settings,
            )
        val seq = assertNotNull(rig.changed(before = setOf(libA, libB), after = setOf(libA))).seq
        // The save Part 4 makes with the change: the new libraries and the change's seq.
        assertTrue(runBlocking { settings.saveSelection(StoredSelection(setOf(libA), seq = seq)) })

        assertEquals(
            seq,
            rig
                .day(laughs.id)
                .record
                ?.repair
                ?.seq,
            "the saved seq shows the change's repair",
        )

        // A later setup's reset seq hides it.
        val setup = StoredSelection(setOf(libA), seq = seq + 1, resetSeq = seq + 1)
        assertTrue(runBlocking { settings.saveSelection(setup) })
        assertNull(rig.day(laughs.id).record?.repair, "below the reset seq: never shown")
    }

    @Test
    fun theGonePlayable_reachesWhatTheRepairerReads() {
        val settings = settingsWith(StoredSelection(setOf(libA, libB), seq = 2L, gone = true))
        val rig =
            Rig(lineupOf(laughs), comedies, setOf(libA, libB), settings = settings)

        val read = assertNotNull(runBlocking { rig.source.savedSelection() })
        assertNull(read.playable)
        assertEquals(2L, read.seq)
    }

    @Test
    fun theSeqAndTheResetSeq_reachTheServicesRoll_throughTheSource() =
        runBlocking<Unit> {
            val library = CountingLibrary().apply { seed(fillingLibrary()) }
            library.delegate.useAccount(ACCOUNT)
            val store = RecordingStore()
            val clock =
                object : Clock {
                    override fun now(): Instant = NOON
                }
            val nowMs = NOON.toEpochMilliseconds()
            // A refreshed lineup, stored with a shown repair (seq 4), one below the reset seq 3 (seq 2), and an ended
            // day.
            LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog)).refresh(ACCOUNT, LIBRARIES)
            val base = LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))) as LineupCodec.Decoded.Loaded
            val shown = repairOf(n = 1L, atMs = nowMs + 60_000L, dayEndMs = nowMs + 4 * 3_600_000L, seq = 4L)
            val below = repairOf(channel = "starter:other", n = 2L, dayEndMs = nowMs + 6 * 3_600_000L, seq = 2L)
            val endedAt = nowMs - 7_200_000L
            val ended = repairOf(channel = "starter:gone", n = 0L, atMs = endedAt, dayEndMs = nowMs, seq = 4L)
            val record = TodayRepairs(listOf(shown, below, ended))
            store.inner.save(ACCOUNT, LineupCodec.encode(base.lineup, base.meta, record))

            val settings = settingsWith(StoredSelection(LIBRARIES, seq = 4L, resetSeq = 3L))
            val service = LineupService(store, library, clock, { 0 }, {}, LineupSeams(TestCatalog))
            BuiltInScheduleSource(service, library, saved = savedSelectionOf(settings))
            val held = checkNotNull(service.loaded(ACCOUNT))

            service.rollSoon(ACCOUNT, held)
            service.awaitRolls()

            assertEquals(listOf(shown), service.repairs(ACCOUNT).value.selection, "pruned at seq 4, reset seq 3")
        }
}
