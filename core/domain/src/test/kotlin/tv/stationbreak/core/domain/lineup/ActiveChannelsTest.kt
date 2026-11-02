// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ActiveChannelsTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(RecordingStore(), library, clock, { 0 }, { }, TestCatalog)

    private suspend fun until(
        what: String,
        ready: () -> Boolean,
    ) = withTimeout(WAIT_MS) {
        while (!ready()) delay(POLL_MS)
    }.also { assertTrue(ready(), what) }

    @Test
    fun activeChannelsFollowsTheActiveAccount() =
        runBlocking<Unit> {
            service.refresh(ACCOUNT, LIBRARIES)
            val other = LibraryAccount(ServerId("c0".repeat(16)), UserId(USER.value))
            val seen = java.util.concurrent.CopyOnWriteArrayList<List<ChannelSummary>>()
            val job = launch { service.activeChannels().collect { seen += it } }

            delay(POLL_MS * 5)
            assertTrue(seen.isEmpty(), "nothing while no account is active")
            library.delegate.useAccount(ACCOUNT)
            until("the active account's lineup") { seen.lastOrNull()?.isNotEmpty() == true }
            library.delegate.useAccount(other)
            until("the new account's (empty) lineup replaces it") { seen.lastOrNull()?.isEmpty() == true }
            job.cancel()
        }

    @Test
    fun onLineupIsTheOnChannelsIdsAndNumbersInOrder() {
        fun summary(
            id: String,
            number: Int?,
            state: ChannelStatus,
            name: String = "Test $id",
        ) = ChannelSummary(
            ChannelId(id),
            number,
            name,
            state,
            ChannelKind.Starter,
            Instant.fromEpochMilliseconds(0),
            number,
            kid = false,
            optIn = false,
        )

        val list =
            listOf(
                summary("a", 3, ChannelStatus.On),
                summary("b", null, ChannelStatus.Shelf),
                summary("c", 7, ChannelStatus.On, name = "Renamed"),
                summary("d", 9, ChannelStatus.UserHidden),
            )

        assertEquals(listOf(ChannelId("a") to 3, ChannelId("c") to 7), list.onLineup())
        // A rename or a change on the shelf is not a change to the lineup.
        val renamedWithoutShelf = list.map { it.copy(name = "x") }.filter { it.state != ChannelStatus.Shelf }
        assertEquals(list.onLineup(), renamedWithoutShelf.onLineup())
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
