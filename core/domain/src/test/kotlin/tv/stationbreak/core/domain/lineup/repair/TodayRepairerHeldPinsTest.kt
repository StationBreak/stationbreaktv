// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.lineupOf
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.scheduler.Epochs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The holds a selection write gives the libraries it removes (spec 26, Part 4 §2): for each library a shown repair
 * pinned on air, until its pin's end plus the pause hold, the latest of them; only while that is still ahead.
 */
internal class TodayRepairerHeldPinsTest : RepairTestBase() {
    private val hold get() = rig.repairer.pauseHold.inWholeMilliseconds

    // A repair of [channel]'s day at the change's moment: the pin to [pinEndMs], then a slate to the day's end.
    private fun repair(
        channel: ChannelId,
        seq: Long,
        pinLibrary: ItemId,
        pinEndMs: Long,
    ): ChannelRepair {
        val settings = laughsRules.toSchedulerSettings()
        val n = Epochs.epochNumber(settings, atMs)
        val end = Epochs.epochStart(settings, n + 1)
        val blocks = listOf(KeptBlock(noonMs, pinEndMs, pinned.item.id, 0), KeptBlock(pinEndMs, end, null, 0))
        return ChannelRepair(channel, n, end, atMs, seq, setOf(libA), noonMs, pinEndMs, pinLibrary, blocks, null)
    }

    private val laughsPin = noonMs + hour
    private val ownPin = noonMs + 90 * minute
    private val whodunitPin = noonMs + 2 * hour
    private val hiddenPin = noonMs + 3 * hour

    private val record =
        TodayRepairs(
            selection =
                listOf(
                    // Shown at the saved seq 3: two of libA's programmes, the later end counts.
                    repair(laughs.id, 3L, libA, laughsPin),
                    repair(ownB.id, 3L, libA, ownPin),
                    // Of seq 1: shown unless a setup's reset seq is above it.
                    repair(whodunit.id, 1L, libB, whodunitPin),
                    // Of seq 5: a change not saved, never shown.
                    repair(laughs.id, 5L, libC, hiddenPin),
                ),
            lastSeq = 5L,
        )

    private val rig =
        Rig(lineupOf(laughs, whodunit, ownB), lostItems + comediesB, setOf(libA, libB), record).also {
            it.applied = 3L
        }

    private fun held(at: Long = atMs): Map<ItemId, Long> = runBlocking { rig.repairer.heldPins(ACCOUNT, at) }

    @Test
    fun theShownPins_holdTheirLibrary_untilThePinsEndPlusThePauseHold_theLatestOfThem() {
        assertEquals(mapOf(libA to ownPin + hold, libB to whodunitPin + hold), held())
    }

    @Test
    fun aPinBelowTheResetSeq_holdsNothing() {
        rig.resetSeq = 2L
        rig.applied = 3L
        assertEquals(mapOf(libA to ownPin + hold), held())
    }

    @Test
    fun aHoldThatHasEnded_isLeftOut() {
        assertEquals(mapOf(libB to whodunitPin + hold), held(at = ownPin + hold))
        assertEquals(mapOf(libB to whodunitPin + hold), held(at = ownPin + hold + 1))
        assertEquals(emptyMap(), held(at = whodunitPin + hold))
    }

    @Test
    fun withNoSavedSelectionRead_everyRepairsPinHolds() {
        // Unknown which repair shows: holding a library longer than needed is harmless, dropping one a paused
        // programme needs is not.
        rig.unknown = true
        assertEquals(mapOf(libA to ownPin + hold, libB to whodunitPin + hold, libC to hiddenPin + hold), held())
    }

    @Test
    fun noLineup_holdsNothing() {
        runBlocking { rig.service.forget(ACCOUNT) }
        assertEquals(emptyMap(), held())
    }
}
