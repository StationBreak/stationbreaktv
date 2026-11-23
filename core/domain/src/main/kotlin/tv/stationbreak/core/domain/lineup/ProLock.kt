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
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/**
 * The channels a free lineup keeps but does not air or edit (ADR-0023, #99): the viewer's own custom channels beyond
 * the [FreeTier.CUSTOM_CHANNELS] with the lowest IDs, whatever state they are in. Nothing is stored: it is a rule over
 * the lineup, so a refund, or an offline grace that ran out (#1065), locks the extras, buying Pro again unlocks them
 * untouched, and no channel is ever deleted.
 * The rank is the ID because it is the one thing of a custom channel that never changes: the lineup is re-sorted by
 * number at every plan, the number is the viewer's to change, and a channel's `onSince` moves each time it is switched
 * on. A custom ID is random, so the five that stay are a fixed but arbitrary set, not the first five made; the
 * creation time is not stored, and storing it would need a lineup-file change. Every `custom:` ID of [ids] is ranked,
 * one this app cannot read too (#1063): it holds its place among the five, as it does under the cap, and is never
 * deleted.
 */
internal fun lockedCustoms(ids: List<ChannelId>): Set<ChannelId> {
    val ranked = ids.filter(LineupIds::isCustom).sortedBy { it.value }
    return ranked.drop(FreeTier.CUSTOM_CHANNELS).toSet()
}

internal fun List<LineupChannel>.lockedCustomIds(): Set<ChannelId> = lockedCustoms(map { it.id })

private fun List<ChannelSummary>.lockedIds(): Set<ChannelId> = lockedCustoms(map { it.id })

/**
 * The lineup as features see it while Pro may be locked (#99): [delegate]'s lists without the locked custom channels,
 * so the guide, flipping and channel management never show one. Each list is emitted again when Pro changes. The
 * locked set is ranked over [delegate]'s whole lists, so [delegate] must not be filtered by anything else (Kids Mode
 * wraps this one, not the other way round).
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
 * The IDs of the active account's locked custom channels, ranked over [lineups]' loaded lineup (the whole lineup, not a
 * filtered view) with no channel list built (#1065). Empty while Pro is on, with no active account, or with no lineup
 * yet.
 */
class ProLockedIds(
    private val pro: ProEntitlement,
    private val library: LibraryRepository,
    private val lineups: LineupService,
) {
    suspend fun now(): Set<ChannelId> {
        val account = if (pro.pro.value) null else library.activeAccount().first()
        return if (account == null) emptySet() else lineups.lockedCustomIds(account)
    }
}

/**
 * What a channel holds while Pro may be locked (#1063): nothing for a locked custom channel. No screen lists one, but
 * the guide reads the focused channel's holdings again when its day ends, and a refund can lock that channel first.
 */
class ProLockedHoldings(
    private val delegate: ChannelHoldingsReader,
    private val locked: ProLockedIds,
) : ChannelHoldingsReader {
    override suspend fun read(
        id: ChannelId,
        at: Instant,
    ): ChannelHoldings? = if (id in locked.now()) null else delegate.read(id, at)
}

/**
 * What the properties sheet reads while Pro may be locked (#1067): nothing for a locked custom channel, so a sheet left
 * open across a refund shows no rules when it reads again. The locked set is read afresh at each call.
 */
class ProLockedProperties(
    private val delegate: ChannelPropertiesReader,
    private val locked: ProLockedIds,
) : ChannelPropertiesReader {
    override suspend fun read(
        id: ChannelId,
        at: Instant,
    ): ChannelProperties? = if (id in locked.now()) null else delegate.read(id, at)
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
