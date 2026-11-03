// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.lineup.BuiltInScheduleSource
import tv.stationbreak.core.domain.lineup.Ceilings
import tv.stationbreak.core.domain.lineup.LineupDays
import tv.stationbreak.core.domain.lineup.LineupService
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.domain.lineup.RuledDay
import tv.stationbreak.core.domain.lineup.SharedSnapshots
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
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.scheduler.Day
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

/** What a draft would air. */
sealed interface ChannelPreview {
    /**
     * [itemCount] and [totalRuntimeMinutes] of the epoch's pool, the [next] items in the order the scheduler lays them
     * out, and the [skipped] picks.
     */
    data class Ready(
        val itemCount: Int,
        val totalRuntimeMinutes: Long,
        val next: List<PreviewItem>,
        val skipped: List<SkippedPick>,
    ) : ChannelPreview

    /** The pool is empty: nothing in the library fits the draft. */
    data class NothingFits(
        val skipped: List<SkippedPick>,
    ) : ChannelPreview

    /**
     * The preview could not be worked out (no lineup, no chosen library, a failed read, kept settings whose origin is
     * gone): that is not "nothing fits", and the screen says something else.
     */
    data object Unavailable : ChannelPreview
}

/** Shows what a channel being built would air, from the real scheduler (spec 22). */
interface ChannelPreviews {
    /**
     * The channel [draft] would air from [at]: the next [NEXT] items laid out by the same `buildDay`, the pool's size
     * and runtime, and the picks the kids hold leaves out. A draft with kept settings reads them from the rules of its
     * [ChannelDraft.origin]; with none, or one that is gone, the preview is [ChannelPreview.Unavailable]. Main-safe and
     * cancellable; the caller debounces. Nothing is saved.
     */
    suspend fun preview(
        account: LibraryAccount,
        draft: ChannelDraft,
        at: Instant,
    ): ChannelPreview

    companion object {
        /** How many items a preview lists. */
        const val NEXT = 12
    }
}

/**
 * The preview over the same functions the lineup's days use: [ruledDayOf] for the pool, the ceiling and the policy,
 * and `buildDay` for the layout, on a synthetic channel built from the draft with the draft's own seed. The snapshots
 * are read through [shared], the schedule source's own cache, under the same key, so a preview does not read the
 * library again for a cut-off the lineup has read. Its pool provider is a plain evaluator call, not the lineup's
 * cache: a draft is not a lineup channel and must not pollute it. Runs on [dispatcher].
 */
class BuiltInChannelPreviews(
    private val service: LineupService,
    private val library: LibraryRepository,
    shared: SharedSnapshots,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val libraries: suspend () -> Set<ItemId>?,
) : ChannelPreviews {
    private val snapshots = shared.cache

    private val ceilings = Ceilings(service.catalog)

    // Any failure other than cancellation (a draft the scheduler refuses, a library read) is an unavailable preview.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun preview(
        account: LibraryAccount,
        draft: ChannelDraft,
        at: Instant,
    ): ChannelPreview =
        withContext(dispatcher) {
            try {
                // A kids draft previews under the ceiling create and save would write.
                previewOf(account, draft.kidsNormalised(), at) ?: ChannelPreview.Unavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ChannelPreview.Unavailable
            }
        }

    private suspend fun previewOf(
        account: LibraryAccount,
        draft: ChannelDraft,
        at: Instant,
    ): ChannelPreview? {
        val lineup = service.loaded(account)?.lineup
        val chosen = libraries()?.takeIf { it.isNotEmpty() }
        val previous = lineup?.let { previousRules(it, draft) }
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
        val reading = Reading(account, chosen, channel, rules, settings, sharedCutoffs(lineup, at))
        val days = ArrayList<Today>()
        val aired = ArrayList<Aired>()
        var epoch = n
        // The epoch that holds `at`, and the next ones until there are enough programs (not blocks: a slate is none).
        while (aired.size < ChannelPreviews.NEXT && days.size < MAX_EPOCHS) {
            // A preview nobody waits for any more stops here, before it reads and lays out another epoch.
            currentCoroutineContext().ensureActive()
            val day = reading.dayOf(epoch)
            days += day
            aired += airedFrom(day.layout, day.ruled.day, if (epoch == n) at.toEpochMilliseconds() else null)
            epoch++
            // An empty pool today stops it: the next epochs would be read (one uncached) to find the same nothing.
            if (day.ruled.day.isEmpty()) break
        }
        val shown = aired.take(ChannelPreviews.NEXT)
        val info = if (shown.isEmpty()) emptyMap() else library.programInfo(account, shown.mapTo(HashSet()) { it.id })
        val next = shown.mapNotNull { program -> info[program.id]?.let { itemOf(it, program.runtimeMs) } }
        val today = days.first()
        val pool = today.ruled.day
        val skipped = if (KidStatus.isKidChannel(channel)) reading.skipped(draft, today.snapshot) else emptyList()
        return if (pool.isEmpty()) {
            ChannelPreview.NothingFits(skipped)
        } else {
            ChannelPreview.Ready(pool.size, pool.sumOf { it.runtimeMs } / MS_PER_MINUTE, next, skipped)
        }
    }

    // The cut-offs the lineup's own days read (today's and the following day's): only these go through the shared
    // cache, so a draft with an odd epoch (a kept short one) cannot push the lineup's snapshots out of it.
    private fun sharedCutoffs(
        lineup: Lineup,
        at: Instant,
    ): Set<Long> {
        val days = LineupDays(service.catalog)
        val atMs = at.toEpochMilliseconds()
        return (days.scheduled(lineup, atMs) + days.scheduled(lineup, atMs + DAY_MS)).mapTo(HashSet()) { it.cutoffMs }
    }

    // The rules the draft was opened from; null for a new channel, or when its origin is gone.
    private fun previousRules(
        lineup: Lineup,
        draft: ChannelDraft,
    ): ChannelRules? {
        val origin = draft.origin
        if (draft.kept == null || origin == null) return null
        return when (val held = lineup.channels.firstOrNull { it.id == origin }?.source) {
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
        runtimeMs: Long,
    ) = PreviewItem(info.id, info.title, info.seriesName, info.seasonNumber, info.episodeNumber, minutes(runtimeMs))

    private fun minutes(runtimeMs: Long): Int = (runtimeMs / MS_PER_MINUTE).toInt().coerceAtLeast(1)

    // The day of the draft for one epoch, with the snapshot it was built from.
    private class Today(
        val ruled: RuledDay,
        val snapshot: RuleSnapshot,
        val layout: Day,
    )

    private inner class Reading(
        val account: LibraryAccount,
        val chosen: Set<ItemId>,
        val channel: LineupChannel,
        val rules: ChannelRules,
        val settings: SchedulerSettings,
        val shared: Set<Long>,
    ) {
        suspend fun dayOf(n: Long): Today {
            val cutoff = Epochs.cutoff(settings, n)
            val at = Instant.fromEpochMilliseconds(cutoff)
            val snapshot =
                if (cutoff in shared) {
                    // The generation is read before the snapshot, as the schedule source does, so the key is the same.
                    val key = SnapshotCache.Key(account, chosen, cutoff, library.contentGeneration(account))
                    snapshots.get(key) { library.ruleSnapshot(account, chosen, at) }.snapshot
                } else {
                    library.ruleSnapshot(account, chosen, at)
                }
            val ruled =
                ruledDayOf(ceilings, rules, channel, snapshot, settings, n) { r, policy ->
                    PoolEvaluator.evaluate(r, snapshot, policy)
                }
            return Today(ruled, snapshot, buildDay(settings, ruled.day, n, ruled.films))
        }

        // Picks the kids hold leaves out: a show or film source that has items under no policy and none under the hold.
        // Both pools are evaluated once, over all the picks, and split by source.
        suspend fun skipped(
            draft: ChannelDraft,
            snapshot: RuleSnapshot,
        ): List<SkippedPick> {
            val picks = draft.sources.mapNotNull { pick -> pickSource(pick)?.let { pick to it } }
            if (picks.isEmpty()) return emptyList()
            currentCoroutineContext().ensureActive()
            val all = rules.copy(sources = picks.map { it.second }, maxRating = null)
            val open = PoolEvaluator.evaluate(all, snapshot, PoolPolicy.Open).items
            currentCoroutineContext().ensureActive()
            val (held, policy) = ceilings.policyRules(all, channel)
            val keptIds = PoolEvaluator.evaluate(held, snapshot, policy).items.mapTo(HashSet()) { it.id }
            return picks.mapNotNull { (pick, source) ->
                val own = open.filter { belongs(source, it) }
                val refused = own.isNotEmpty() && own.none { it.id in keptIds }
                if (refused) SkippedPick(pick.label, reasonOf(own.map { it.id }, snapshot)) else null
            }
        }

        private fun belongs(
            source: Source,
            item: ScheduleItem,
        ): Boolean =
            when (source) {
                is Source.Series -> item.seriesId == source.id
                is Source.Item -> item.id == source.id
                else -> false
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
        val PREVIEW_ID = ChannelId("preview")
        const val MS_PER_MINUTE = 60_000L
        const val DAY_MS = 86_400_000L

        /** The most epochs a preview reads to find its programs: a day with nothing to air must not loop. */
        const val MAX_EPOCHS = 3
    }
}

/** A program a layout airs: the item and its runtime, as the very day it is in holds it. */
internal data class Aired(
    val id: CanonicalId,
    val runtimeMs: Long,
)

/**
 * The programs of [layout] (built from [items]) from [atMs] on, the one on air first: slates are not programs. When
 * [atMs] is null the day is listed from its start; one at or after the day's end lists nothing (the caller goes on to
 * the next epoch), one before its start lists it all.
 */
internal fun airedFrom(
    layout: Day,
    items: List<ScheduleItem>,
    atMs: Long?,
): List<Aired> {
    val runtimes = items.associate { it.id to it.runtimeMs }
    val from = atMs ?: layout.startMs
    return layout.blocks.filter { it.endMs > from }.mapNotNull { block ->
        val id = (block.content as? ScheduleContent.Program)?.itemId
        id?.let { runtimes[it] }?.let { Aired(id, it) }
    }
}
