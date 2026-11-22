// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.domain.lineup.LineupAccess

/** A channel the viewer just switched on shows "Getting ready" until its day is built (#776). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsGettingReadyTest {
    @Test
    fun aPendingRowGetsReadyAndOffersNoWatch() =
        channelsTest(listOf(summary(A, 5), summary(B, 7))) { rig ->
            rig.schedule.channel(A, 5, emptyList())
            rig.schedule.channel(B, 7, emptyList())
            rig.readiness.held.value = setOf(A)
            rig.open(this)

            val row = rig.ui.rows.first { it.id == A }
            assertTrue(row.gettingReady)
            assertFalse(
                rig.ui.rows
                    .first { it.id == B }
                    .gettingReady,
            )
            assertFalse(actionsOf(row, LineupAccess.Editable, reasonsRead = true).contains(Choice.Watch))
        }

    @Test
    fun aStaleReasonIsNotShownWhileTheChannelIsGettingReady() =
        channelsTest(listOf(summary(A, 5))) { rig ->
            rig.schedule.channel(A, 5, emptyList(), HiddenReason.EmptyToday)
            rig.readiness.held.value = setOf(A)
            rig.open(this)

            assertEquals(
                null,
                rig.ui.rows
                    .single()
                    .reason,
            )
        }

    @Test
    fun whenTheChannelStopsGettingReadyItsReasonIsReadAgain() =
        channelsTest(listOf(summary(A, 5))) { rig ->
            rig.schedule.channel(A, 5, emptyList())
            rig.readiness.held.value = setOf(A)
            rig.open(this)
            val before = rig.schedule.airingsCalls

            rig.schedule.channels
                .getValue(A)
                .hidden = HiddenReason.NoMatches
            rig.readiness.held.value = emptySet()
            runCurrent()
            advanceTimeBy(1_100)
            runCurrent()

            assertEquals(before + 1, rig.schedule.airingsCalls)
            val row = rig.ui.rows.single()
            assertFalse(row.gettingReady)
            assertEquals(HiddenReason.NoMatches, row.reason)
        }
}
