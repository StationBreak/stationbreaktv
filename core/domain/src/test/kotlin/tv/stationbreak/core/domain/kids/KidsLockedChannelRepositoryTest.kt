// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.ChannelKind
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelStatus
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.PlayKey
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class KidsLockedChannelRepositoryTest {
    private val numbers = listOf(1, 33, 34, 66, 67, 99, 100, 150, null)

    private fun summary(number: Int?) =
        ChannelSummary(
            ChannelId("ch-${number ?: "none"}"),
            number,
            "Test Channel $number",
            if (number == null) ChannelStatus.Shelf else ChannelStatus.On,
            ChannelKind.Starter,
            Instant.fromEpochMilliseconds(0),
            defaultNumber = number,
            kid = number != null && number < 100,
            optIn = false,
            playKey = PlayKey("test"),
        )

    private class Lineup(
        list: List<ChannelSummary>,
    ) : ChannelRepository {
        val flow = MutableStateFlow(list)

        override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = flow

        override fun activeChannels(): Flow<List<ChannelSummary>> = flow
    }

    private val lineup = Lineup(numbers.map(::summary))
    private val lock = FakeKidsLock()
    private val locked = KidsLockedChannelRepository(lineup, lock)
    private val account = LibraryAccount(ServerId("srv1"), UserId("u"))

    private suspend fun shown(lists: Flow<List<ChannelSummary>> = locked.activeChannels()): List<Int?> {
        val list = lists.first()
        return list.map { it.number }
    }

    @Test
    fun offShowsEveryChannel() =
        runTest {
            assertEquals(numbers, shown())
            assertEquals(numbers, shown(lists = locked.channels(account)))
        }

    @Test
    fun eachCapShowsOnlyOneToItsNumberAndNoChannelWithoutOne() =
        runTest {
            lock.lock(KidsCap.Kids)
            assertEquals(listOf(1, 33), shown())
            lock.lock(KidsCap.Family)
            assertEquals(listOf(1, 33, 34, 66), shown())
            lock.lock(KidsCap.Teen)
            assertEquals(listOf(1, 33, 34, 66, 67, 99), shown(lists = locked.channels(account)))
        }

    @Test
    fun turningTheLockOnOrOffOrChangingTheCapEmitsAgain() =
        runTest {
            val seen = mutableListOf<List<Int?>>()
            val job =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    locked.activeChannels().collect { list: List<ChannelSummary> -> seen += list.map { it.number } }
                }
            lock.lock(KidsCap.Kids)
            lock.lock(KidsCap.Teen)
            lock.unlock()
            job.cancel()
            assertEquals(listOf(numbers, listOf(1, 33), listOf(1, 33, 34, 66, 67, 99), numbers), seen)
        }

    @Test
    fun aChangeOfTheLineupUnderTheLockIsFilteredToo() =
        runTest {
            lock.lock(KidsCap.Kids)
            val seen =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    locked.channels(account).take(2).toList<List<ChannelSummary>>()
                }
            lineup.flow.value = listOf(summary(2), summary(40))
            seen.join()
            assertEquals(listOf(2), shown())
        }

    @Test
    fun settingAPinAloneDoesNotEmitAgain() =
        runTest {
            val seen = mutableListOf<List<Int?>>()
            val job =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    locked.activeChannels().collect { list: List<ChannelSummary> -> seen += list.map { it.number } }
                }
            lock.setPin("1234")
            job.cancel()
            assertEquals(1, seen.size)
        }

    @Test
    fun nothingIsShownBeforeTheLockIsRead() =
        runTest {
            val unread =
                object : KidsLock by lock {
                    override val state: Flow<KidsLockState> = kotlinx.coroutines.flow.MutableSharedFlow<KidsLockState>()
                }
            val wrapped = KidsLockedChannelRepository(lineup, unread)
            val first = withTimeoutOrNull(60_000) { wrapped.activeChannels().first() }
            assertEquals(null, first)
        }
}
