// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ChannelIdentity
import tv.stationbreak.core.domain.lineup.ChannelIdentityReader
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH7
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.CH9
import tv.stationbreak.core.domain.lineup.builder.PlaysItem
import tv.stationbreak.core.domain.lineup.builder.PlaysKind
import tv.stationbreak.core.domain.lineup.builder.PlaysRow
import tv.stationbreak.core.model.ChannelId

private const val LOOKUP_MS = 5_000L

/** Who each channel is (#777): read when the guide opens, for the channel box at once, with no rest on a channel. */
class GuideChannelIdentityTest : GuideRig() {
    private class Identities(
        private val shown: Map<ChannelId, ChannelIdentity>,
    ) : ChannelIdentityReader {
        var reads = 0
        var fails = false

        override fun identities(): Flow<Map<ChannelId, ChannelIdentity>> =
            flow {
                reads++
                check(!fails) { "the lineup is out of reach" }
                emit(shown.mapValues { it.value.copy(plays = null) })
                delay(LOOKUP_MS)
                emit(shown)
            }
    }

    private val sevens = ChannelIdentity(ChannelKind.Starter, kid = false, ceiling = null, "Rain on the glass.", null)
    private val nines =
        ChannelIdentity(
            ChannelKind.Custom,
            kid = false,
            ceiling = null,
            null,
            PlaysRow(PlaysKind.Genres, listOf(PlaysItem.Name("Comedy"))),
        )
    private val reader = Identities(mapOf(CH7 to sevens, CH9 to nines))

    @Test
    fun theIdentitiesAreReadOnceAtTheOpenWithNoRestOnAChannel() {
        identities = reader
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            after(LOOKUP_MS)
            assertEquals(mapOf(CH7 to sevens, CH9 to nines), vm.channelIdentities.value)
            vm.move(GuideDirection.Left, repeat = false, atMs = 0)
            runCurrent()
            assertEquals(sevens, vm.channelIdentities.value[CH7])
            assertEquals(1, reader.reads)
        }
    }

    @Test
    fun whatNeedsNoLookupShowsAtOnceAndThePlaysRowFollows() {
        identities = reader
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            assertEquals(sevens, vm.channelIdentities.value[CH7])
            assertEquals(nines.copy(plays = null), vm.channelIdentities.value[CH9])
            after(LOOKUP_MS)
            assertEquals(nines, vm.channelIdentities.value[CH9])
        }
    }

    @Test
    fun aFailedReadKeepsWhatTheBoxHad() {
        identities = reader
        guideTest { vm ->
            vm.open(CH7)
            runCurrent()
            after(LOOKUP_MS)
            reader.fails = true
            vm.open(CH9)
            runCurrent()
            assertEquals(2, reader.reads)
            assertEquals(mapOf(CH7 to sevens, CH9 to nines), vm.channelIdentities.value)
        }
    }
}
