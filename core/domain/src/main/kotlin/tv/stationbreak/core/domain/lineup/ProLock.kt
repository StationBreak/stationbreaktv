// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import tv.stationbreak.core.domain.FreeTier
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.ProEntitlement
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.isViewerCustom
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/**
 * The channels a free lineup keeps but does not air or edit (ADR-0023, #99): the viewer's own custom channels beyond
 * the [FreeTier.CUSTOM_CHANNELS] oldest. [customs] is each custom channel's ID and the time it was made, whatever
 * state it is in. Nothing is stored: it is a rule over the lineup, so a refund locks the extras, buying Pro again
 * unlocks them untouched, and no channel is ever deleted.
 */
internal fun lockedCustoms(customs: List<Pair<ChannelId, Long>>): Set<ChannelId> =
    customs
        .sortedWith(compareBy({ it.second }, { it.first.value }))
        .drop(FreeTier.CUSTOM_CHANNELS)
        .mapTo(HashSet()) { it.first }

internal fun List<LineupChannel>.lockedCustomIds(): Set<ChannelId> =
    lockedCustoms(filter { it.isViewerCustom() }.map { it.id to it.onSinceMs })

private fun List<ChannelSummary>.lockedIds(): Set<ChannelId> =
    lockedCustoms(filter { it.viewerMade }.map { it.id to it.onSince.toEpochMilliseconds() })

/**
 * The lineup as features see it while Pro may be locked (#99): [delegate]'s lists without the locked custom channels,
 * so the guide, flipping and channel management never show one. Each list is emitted again when Pro changes.
 */
class ProLockedChannelRepository(
    private val delegate: ChannelRepository,
    private val pro: ProEntitlement,
) : ChannelRepository {
    override fun channels(account: LibraryAccount): Flow<List<ChannelSummary>> = allowed(delegate.channels(account))

    override fun activeChannels(): Flow<List<ChannelSummary>> = allowed(delegate.activeChannels())

    private fun allowed(lists: Flow<List<ChannelSummary>>): Flow<List<ChannelSummary>> =
        combine(lists, pro.pro) { list, isPro ->
            if (isPro) list else list.lockedIds().let { out -> list.filterNot { it.id in out } }
        }
}

/**
 * The IDs of the active account's locked custom channels, read from [channels] (the whole lineup, not a filtered
 * view). Empty while Pro is on, with no active account, or with no lineup yet.
 */
class ProLockedIds(
    private val pro: ProEntitlement,
    private val library: LibraryRepository,
    private val channels: ChannelRepository,
) {
    suspend fun now(): Set<ChannelId> {
        val account = if (pro.pro.value) null else library.activeAccount().first()
        return if (account == null) emptySet() else channels.channels(account).first().lockedIds()
    }
}

/**
 * The schedule as live TV and the guide read it while Pro may be locked (#99): a locked custom channel is not among
 * the [airings], has no rows in a [schedule] window, is not on the air for [onAir] and has no stand-ins. The locked
 * set is read afresh at each call, so a purchase or a refund applies to the next one.
 */
class ProLockedScheduleSource(
    private val delegate: ScheduleSource,
    private val locked: ProLockedIds,
) : ScheduleSource {
    override suspend fun onAir(
        channel: ChannelId,
        at: Instant,
        next: Int,
    ): ChannelOnAir? = if (channel in locked.now()) null else delegate.onAir(channel, at, next)

    override suspend fun standIns(
        channel: ChannelId,
        block: AiringBlock,
        exclude: Set<CanonicalId>,
    ): List<AiringBlock> = if (channel in locked.now()) emptyList() else delegate.standIns(channel, block, exclude)

    override suspend fun airings(at: Instant): List<ChannelAiring> {
        val out = locked.now()
        return delegate.airings(at).filter { it.id !in out }
    }

    override suspend fun schedule(
        from: Instant,
        until: Instant,
        build: ScheduleBuild,
    ): ScheduleWindow? {
        val out = locked.now()
        val window = delegate.schedule(from, until, build) ?: return null
        return if (out.isEmpty()) window else window.copy(rows = window.rows.filter { it.channel.id !in out })
    }
}
