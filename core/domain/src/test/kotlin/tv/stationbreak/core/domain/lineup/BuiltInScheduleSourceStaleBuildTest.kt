// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.Source
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A build that goes stale between two channels (#617): the days it finished are filed for the next build to take over
 * (#589), and nothing of it is kept as a whole. The source is told each finished day ([BuiltInScheduleSource]'s
 * `dayFinished`), and the first one moves the lineup on, so the build stops at its next channel.
 */
class BuiltInScheduleSourceStaleBuildTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)
    private val builds: MutableList<DayBuild> = Collections.synchronizedList(mutableListOf())
    private val finished: MutableList<ChannelId> = Collections.synchronizedList(mutableListOf())
    private val whodunit = LineupIds.starter("whodunit")

    private val source =
        BuiltInScheduleSource(
            service,
            library,
            built = { builds += it },
            dayFinished = { channel ->
                finished += channel
                // The first finished day moves the lineup on: whodunit is switched off, a change of its pool.
                if (finished.size == 1) {
                    val saved = runBlocking { service.edit(ACCOUNT, LineupEdit.SwitchOff(whodunit)) }
                    assertIs<Saved.Ok<*>>(saved)
                }
            },
        ) { selectionOf(LIBRARIES) }

    init {
        runBlocking { library.delegate.useAccount(ACCOUNT) }
        val lineup =
            lineupOf(
                starter("toons", 10, ChannelState.On, rules("Toons", Source.Genre("Animation"), maxRating = "G")),
                starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
                starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
                starter("frights", 310, ChannelState.On, rules("Frights", Source.Genre("Horror"))),
            )
        runBlocking { store.inner.save(ACCOUNT, LineupCodec.encode(lineup)) }
    }

    @Test
    fun aBuildThatGoesStaleBetweenTwoChannelsFilesOnlyItsFinishedDays() =
        runBlocking {
            val days = source.channelDays(ACCOUNT, LIBRARIES, NOON)

            // The caller was sent round to the lineup that replaced the stale build's, so the days are that lineup's.
            assertFalse(days.any { it.channel.id == whodunit }, "whodunit is off: ${days.map { it.channel.id }}")
            assertEquals(3, days.size)
            assertEquals(1, builds.size, "only the build that finished reports itself; the stale one did not")
            // The stale build finished one day before it stopped, and that day, no more, was taken over by the next.
            assertEquals(1, builds.single().reused, "the finished day is taken over, and nothing else")
            assertEquals(3, finished.size, "one day before it went stale, and the two the next build made")
        }
}
