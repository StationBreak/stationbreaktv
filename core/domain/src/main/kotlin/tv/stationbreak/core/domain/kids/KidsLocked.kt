// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.kids

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.lineup.AiringBlock
import tv.stationbreak.core.domain.lineup.ChannelAiring
import tv.stationbreak.core.domain.lineup.ChannelOnAir
import tv.stationbreak.core.domain.lineup.ChannelRepository
import tv.stationbreak.core.domain.lineup.ChannelSummary
import tv.stationbreak.core.domain.lineup.ScheduleBuild
import tv.stationbreak.core.domain.lineup.ScheduleSource
import tv.stationbreak.core.domain.lineup.ScheduleWindow
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/** The part of the lock's state that decides what is seen: a PIN being set changes nothing shown. */
private fun KidsLock.filter(): Flow<KidsLockState> = state.map { it.copy(pinSet = false) }.distinctUntilChanged()

/**
 * The lineup as features see it while Kids Mode may be on (#789): [delegate]'s lists with every channel the lock does
 * not allow left out ([KidsLockState.allows]: while it is on, only 1 to the cap, and none without a number). Each list
 * is emitted again when the lock is turned on or off or its cap changes, so the guide and flipping follow at once.
 * Nothing is emitted until the lock has been read, so no list ever shows a hidden channel first.
 */
class KidsLockedChannelRepository(
    private val delegate: ChannelRepository,
    private val lock: KidsLock,
) : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = allowed(delegate.channels(account))

    override fun activeChannels(): Flow<List<ChannelSummary>> = allowed(delegate.activeChannels())

    private fun allowed(lists: Flow<List<ChannelSummary>>): Flow<List<ChannelSummary>> =
        combine(lists, lock.filter()) { list, state ->
            if (state.enabled) list.filter { state.allows(it.number) } else list
        }
}

/**
 * The schedule as live TV and the guide read it while Kids Mode may be on (#789): a channel the lock does not allow is
 * not among the [airings], has no rows in a [schedule] window, is not on the air for [onAir] (so no tune, flip, number
 * entry, guide pick, kept place or start can land on it) and has no stand-ins. The lock is read afresh at each call, so
 * a change applies to the next one.
 */
class KidsLockedScheduleSource(
    private val delegate: ScheduleSource,
    private val lock: KidsLock,
) : ScheduleSource {
    private suspend fun state(): KidsLockState = lock.state.first()

    override suspend fun onAir(
        channel: ChannelId,
        at: Instant,
        next: Int,
    ): ChannelOnAir? {
        val state = state()
        return delegate.onAir(channel, at, next)?.takeIf { state.allows(it.channel.number) }
    }

    override suspend fun standIns(
        channel: ChannelId,
        block: AiringBlock,
        exclude: Set<CanonicalId>,
    ): List<AiringBlock> {
        val state = state()
        if (state.enabled) {
            val at = Instant.fromEpochMilliseconds(block.startMs)
            val number = delegate.airings(at).firstOrNull { it.id == channel }?.number
            if (!state.allows(number)) return emptyList()
        }
        return delegate.standIns(channel, block, exclude)
    }

    override suspend fun airings(at: Instant): List<ChannelAiring> {
        val state = state()
        return delegate.airings(at).filter { state.allows(it.number) }
    }

    override suspend fun schedule(
        from: Instant,
        until: Instant,
        build: ScheduleBuild,
    ): ScheduleWindow? {
        val state = state()
        val window = delegate.schedule(from, until, build) ?: return null
        return if (state.enabled) window.copy(rows = window.rows.filter { state.allows(it.channel.number) }) else window
    }
}
