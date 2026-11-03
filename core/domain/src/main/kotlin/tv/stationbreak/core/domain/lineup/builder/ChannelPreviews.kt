// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.Ceilings
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.domain.lineup.RuledDay
import tv.stationbreak.core.domain.lineup.SnapshotCache
import tv.stationbreak.core.domain.lineup.policyRules
import tv.stationbreak.core.domain.lineup.ruledDayOf
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.PoolEvaluator
import tv.stationbreak.core.engine.PoolPolicy
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.KidStatus
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.LineupDefaults
import tv.stationbreak.core.engine.lineup.LineupEdits
import tv.stationbreak.core.engine.toSchedulerSettings
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleBlock
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.scheduler.Epochs
import tv.stationbreak.core.scheduler.buildDay
import kotlin.time.Instant

/** One item the previewed channel airs. Structured: the screen words the line. */
data class PreviewItem(
    val id: CanonicalId,
    val name: String,
    val seriesName: String?,
    val season: Int?,
    val episode: Int?,
    val minutes: Int,
)

/** Why the kids hold leaves a pick out. */
enum class SkipReason { NotMadeForKids, AboveKidsRating }

/** A show or film the viewer picked that a kids channel does not air; [label] is the source's label. */
data class SkippedPick(
    val label: String,
    val reason: SkipReason,
)

/**
 * What a draft would air: [itemCount] and [totalRuntimeMinutes] of the epoch's pool, the [next] items in the order
 * the scheduler lays them out, the [skipped] picks, and [nothingFits] when the pool is empty.
 */
data class ChannelPreview(
    val itemCount: Int,
    val totalRuntimeMinutes: Long,
    val next: List<PreviewItem>,
    val skipped: List<SkippedPick>,
    val nothingFits: Boolean,
)

/** Shows what a channel being built would air, from the real scheduler (spec 22). */
interface ChannelPreviews {
    /**
     * The channel [draft] would air from [at]: the next [NEXT] items laid out by the same `buildDay`, the pool's size
     * and runtime, and the picks the kids hold leaves out. [source] is the channel the draft was opened from (a
     * built-in for a copy, the channel itself for an edit): where its kept settings are read from. Main-safe; the
     * caller debounces. Nothing is saved.
     */
    suspend fun preview(
        account: LibraryAccount,
        draft: ChannelDraft,
        at: Instant,
        source: ChannelId? = null,
    ): ChannelPreview

    companion object {
        /** How many items a preview lists. */
        const val NEXT = 12
    }
}

/**
 * The preview over the same functions the lineup's days use: [ruledDayOf] for the pool, the ceiling and the policy,
 * and `buildDay` for the layout, on a synthetic channel built from the draft with the draft's own seed. The snapshots
 * are read through [snapshots], the schedule source's own cache, under the same key, so a preview does not read the
 * library again for a cut-off the lineup has read. Its pool provider is a plain evaluator call, not the lineup's
 * cache: a draft is not a lineup channel and must not pollute it. Runs on [dispatcher].
 */
class BuiltInChannelPreviews internal constructor(
    private val service: LineupService,
    private val library: LibraryRepository,
    private val snapshots: SnapshotCache,
    private val libraries: suspend () -> Set<ItemId>?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ChannelPreviews {
    constructor(
        service: LineupService,
        library: LibraryRepository,
        schedule: BuiltInScheduleSource,
        libraries: suspend () -> Set<ItemId>?,
    ) : this(service, library, schedule.snapshots, libraries)

    private val ceilings = Ceilings(service.catalog)

    // Any failure other than cancellation (a draft the scheduler refuses, a library read) is an empty preview.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun preview(
        account: LibraryAccount,
        draft: ChannelDraft,
        at: Instant,
        source: ChannelId?,
    ): ChannelPreview =
        withContext(dispatcher) {
            try {
                previewOf(account, draft, at, source) ?: NOTHING
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NOTHING
            }
        }

    private suspend fun previewOf(
        account: LibraryAccount,
        draft: ChannelDraft,
        at: Instant,
        source: ChannelId?,
    ): ChannelPreview? {
        val lineup = service.loaded(account)?.lineup
        val chosen = libraries()?.takeIf { it.isNotEmpty() }
        val previous = lineup?.let { previousRules(it, draft, source) }
        // Kept settings come from the rules they were read from: without them the draft cannot be previewed.
        val keptReadable = draft.kept == null || previous != null
        return if (lineup == null || chosen == null || !keptReadable) {
            null
        } else {
            previewFor(account, draft, at, Inputs(lineup, chosen, previous))
        }
    }

    // What a preview reads before it starts: the lineup, the chosen libraries and the rules the kept settings are of.
    private data class Inputs(
        val lineup: Lineup,
        val chosen: Set<ItemId>,
        val previous: ChannelRules?,
    )

    private suspend fun previewFor(
        account: LibraryAccount,
        draft: ChannelDraft,
        at: Instant,
        inputs: Inputs,
    ): ChannelPreview {
        val (lineup, chosen, previous) = inputs
        val rules = DraftRules.toRules(draft, lineup.utcOffsetMinutes, previous)
        val channel = syntheticChannel(lineup, draft, rules, at)
        val settings = rules.toSchedulerSettings()
        val n = Epochs.epochNumber(settings, at.toEpochMilliseconds())
        val reading = Reading(account, chosen, channel, rules, settings)
        val today = reading.dayOf(n)
        val blocks = ArrayList(reading.blocksFrom(today, at.toEpochMilliseconds()))
        if (blocks.size < ChannelPreviews.NEXT) {
            blocks += reading.blocksFrom(reading.dayOf(n + 1), null).take(ChannelPreviews.NEXT - blocks.size)
        }
        val items = (today.ruled.day).associateBy { it.id }
        val ids = blocks.mapNotNull { (it.content as? ScheduleContent.Program)?.itemId }.distinct()
        val info = if (ids.isEmpty()) emptyMap() else library.programInfo(account, ids.toSet())
        val next =
            blocks.take(ChannelPreviews.NEXT).mapNotNull { block ->
                val id = (block.content as? ScheduleContent.Program)?.itemId
                val named = id?.let { info[it] }
                named?.let { itemOf(it, items[id]?.runtimeMs) }
            }
        val pool = today.ruled.day
        return ChannelPreview(
            itemCount = pool.size,
            totalRuntimeMinutes = pool.sumOf { it.runtimeMs } / MS_PER_MINUTE,
            next = next,
            skipped = if (KidStatus.isKidChannel(channel)) reading.skipped(draft, today.snapshot) else emptyList(),
            nothingFits = pool.isEmpty(),
        )
    }

    // The rules a copy or an edit was opened from; null for a new channel, or one that is gone.
    private fun previousRules(
        lineup: Lineup,
        draft: ChannelDraft,
        source: ChannelId?,
    ): ChannelRules? {
        if (draft.kept == null || source == null) return null
        return when (val held = lineup.channels.firstOrNull { it.id == source }?.source) {
            is ChannelSource.Starter -> held.rules
            is ChannelSource.Custom -> held.rules
            else -> null
        }
    }

    // A channel as the lineup would hold it: the draft's number (a kids draft in the Kids band, so the hold and the
    // PG cap follow from it, as for a built channel), or the first free one.
    private fun syntheticChannel(
        lineup: Lineup,
        draft: ChannelDraft,
        rules: ChannelRules,
        at: Instant,
    ): LineupChannel {
        val picked = draft.number?.takeIf { (it in Band.Kids.numbers) == draft.kids }
        val free = LineupEdits.firstFreeCustom(lineup, draft.kids, service.catalog)
        val fallback = if (draft.kids) Band.Kids.numbers.first else LineupDefaults.CUSTOM_NUMBERS.first
        val number = picked ?: free ?: fallback
        return LineupChannel(
            PREVIEW_ID,
            null,
            number,
            null,
            ChannelState.On,
            ChannelSource.Custom(rules),
            at.toEpochMilliseconds(),
        )
    }

    private fun itemOf(
        info: ProgramInfo,
        runtimeMs: Long?,
    ) = PreviewItem(info.id, info.title, info.seriesName, info.seasonNumber, info.episodeNumber, minutes(runtimeMs))

    private fun minutes(runtimeMs: Long?): Int = ((runtimeMs ?: 0L) / MS_PER_MINUTE).toInt().coerceAtLeast(1)

    // The day of the draft for one epoch, with the snapshot it was built from.
    private class Today(
        val ruled: RuledDay,
        val snapshot: RuleSnapshot,
        val layout: tv.stationbreak.core.scheduler.Day,
    )

    private inner class Reading(
        val account: LibraryAccount,
        val chosen: Set<ItemId>,
        val channel: LineupChannel,
        val rules: ChannelRules,
        val settings: SchedulerSettings,
    ) {
        suspend fun dayOf(n: Long): Today {
            val cutoff = Epochs.cutoff(settings, n)
            // The generation is read before the snapshot, as the schedule source does, so the key is the same.
            val generation = library.contentGeneration(account)
            val key = SnapshotCache.Key(account, chosen, cutoff, generation)
            val at = Instant.fromEpochMilliseconds(cutoff)
            val snapshot = snapshots.get(key) { library.ruleSnapshot(account, chosen, at) }.snapshot
            val ruled =
                ruledDayOf(ceilings, rules, channel, snapshot, settings, n) { r, policy ->
                    PoolEvaluator.evaluate(r, snapshot, policy)
                }
            return Today(ruled, snapshot, buildDay(settings, ruled.day, n, ruled.films))
        }

        // The blocks of [today] from [atMs] (the start of the day when null), the one on air first.
        fun blocksFrom(
            today: Today,
            atMs: Long?,
        ): List<ScheduleBlock> {
            val layout = today.layout
            if (layout.blocks.isEmpty()) return emptyList()
            val from = atMs?.takeIf { it >= layout.startMs && it < layout.endMs } ?: layout.startMs
            val now = layout.whatsOn(from, ChannelPreviews.NEXT)
            return listOf(now.block) + now.next
        }

        // Picks the kids hold leaves out: a show or film source that has items under no policy and none under the hold.
        fun skipped(
            draft: ChannelDraft,
            snapshot: RuleSnapshot,
        ): List<SkippedPick> =
            draft.sources.mapNotNull { pick ->
                val source = pickSource(pick) ?: return@mapNotNull null
                val own = rules.copy(sources = listOf(source), maxRating = null)
                val open = PoolEvaluator.evaluate(own, snapshot, PoolPolicy.Open).items
                val (held, policy) = ceilings.policyRules(own, channel)
                val kept = PoolEvaluator.evaluate(held, snapshot, policy).items
                val refused = open.isNotEmpty() && kept.isEmpty()
                if (refused) SkippedPick(pick.label, reasonOf(open.map { it.id }, snapshot)) else null
            }

        // Not made for kids when none of the source's items is; otherwise it is the rating that holds it back.
        private fun reasonOf(
            ids: List<CanonicalId>,
            snapshot: RuleSnapshot,
        ): SkipReason {
            val wanted = ids.toSet()
            val madeForKids =
                snapshot.items.any { entry ->
                    val series = entry.item.seriesId?.let { snapshot.series[it] }
                    entry.item.id in wanted && KidHold.isMadeForKids(entry, series)
                }
            return if (madeForKids) SkipReason.AboveKidsRating else SkipReason.NotMadeForKids
        }
    }

    // Only a show or a single film is a pick; the other sources are not listed as skipped.
    private fun pickSource(source: DraftSource): Source? {
        val id = CanonicalId.parseOrNull(source.key)
        return when {
            id == null -> null
            source is DraftSource.Show -> Source.Series(id)
            source is DraftSource.Pick -> Source.Item(id)
            else -> null
        }
    }

    private companion object {
        val NOTHING = ChannelPreview(0, 0L, emptyList(), emptyList(), nothingFits = true)
        val PREVIEW_ID = ChannelId("preview")
        const val MS_PER_MINUTE = 60_000L
    }
}
