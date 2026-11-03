// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.engine.lineup.LineupDefaults
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.FillerMode
import tv.stationbreak.core.rules.FilterKind
import tv.stationbreak.core.rules.Filters
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.OrderingCaps
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import tv.stationbreak.core.rules.normalised

/**
 * A [ChannelDraft] to rules and back. The builder writes back-to-back schedules over 24-hour epochs, and the lowest
 * format version that holds the rules.
 *
 * What the builder cannot show (a built-in's `chronological` or `randomInOrder` order, year-range sources, block
 * length, a grid layout, filler, and every setting or extra it does not know) is carried over from `previous` while the
 * draft's [KeptSettings] is set. "Use the builder's settings instead" replaces only what the builder has controls for
 * (the play order and the caps: a kept order, a kept uncapped Shuffle, a day cut shorter than 24 hours); the rest stays
 * kept, so it is still carried over (see [ChannelDraft.keepsReplaceable]). A decade copy's year range stays its pool.
 * Without [KeptSettings] none of it is written. The version is always the lowest that holds the rules, so an edited
 * version 2 file that uses only version 1 features is written as version 1 (by design).
 */
@Suppress("TooManyFunctions") // One step of the draft to rules and back, each.
internal object DraftRules {
    private const val EPOCH_HOURS = 24
    private val NO_EXTRAS = JsonObject(emptyMap())

    /** The rules of [draft], under the lineup's [utcOffsetMinutes]. [previous] is the stored rules of an edit. */
    fun toRules(
        draft: ChannelDraft,
        utcOffsetMinutes: Int,
        previous: ChannelRules? = null,
    ): ChannelRules {
        require(draft.kept == null || previous != null) { "Kept settings come from the rules they were read from" }
        val carry = previous.takeIf { draft.kept != null }
        // Version 2 first, with the carried extras version 2 knows left out (a version 1 copy's stale `filters` or
        // `caps` extra makes room for the real ones).
        val v2 = assemble(draft, utcOffsetMinutes, 2, carry?.withVersion(2))
        val staysV1 = carry != null && carry.formatVersion == 1 && v2.requiredVersion == 1
        return if (staysV1) assemble(draft, utcOffsetMinutes, 1, carry) else v2.normalised()
    }

    private fun assemble(
        draft: ChannelDraft,
        utcOffsetMinutes: Int,
        version: Int,
        carry: ChannelRules?,
    ): ChannelRules {
        val ordering = orderingOf(draft, carry)
        return ChannelRules(
            formatVersion = version,
            name = draft.name.trim().ifEmpty { null },
            seed = draft.seed,
            sources = withYearRanges(draft.sources.map(consuming(carry?.sources)), carry?.sources),
            exclusions = leftOut(draft, carry),
            maxRating = draft.filters.maxRating,
            ordering = ordering,
            timing = timingOf(utcOffsetMinutes, carry, ordering),
            filler = carry?.filler,
            filters = filtersOf(draft.filters, carry?.filters),
            extras = carry?.extras ?: NO_EXTRAS,
        )
    }

    private fun consuming(stored: List<Source>?): (DraftSource) -> Source {
        val remaining = stored?.toMutableList()
        return { sourceOf(it, remaining) }
    }

    private fun leftOut(
        draft: ChannelDraft,
        carry: ChannelRules?,
    ): List<Source>? {
        val built = draft.leaveOut.map(consuming(carry?.exclusions))
        return withYearRanges(built, carry?.exclusions).ifEmpty { null }
    }

    // The year ranges of the stored list go back at their places: the builder cannot show them.
    private fun withYearRanges(
        built: List<Source>,
        stored: List<Source>?,
    ): List<Source> {
        val out = built.toMutableList()
        stored?.forEachIndexed { index, source ->
            if (source is Source.YearRange) out.add(index.coerceAtMost(out.size), source)
        }
        return out
    }

    private fun orderingOf(
        draft: ChannelDraft,
        carry: ChannelRules?,
    ): Ordering? {
        val keepsOrder = draft.keepsOrder()
        val uncapped = draft.keepsUncapped()
        val order = draft.order ?: PlayOrder.Shuffle
        val extras = carry?.ordering?.extras ?: NO_EXTRAS
        return when {
            keepsOrder && carry != null -> {
                carry.ordering
            }

            uncapped && carry != null -> {
                carry.ordering?.let { Ordering(OrderingMode.Shuffle, null, it.extras) }
            }

            order == PlayOrder.InOrder -> {
                Ordering(OrderingMode.InOrder, null, extras)
            }

            else -> {
                val mode = if (order == PlayOrder.Rotation) OrderingMode.Rotation else OrderingMode.Shuffle
                val capExtras = carry?.ordering?.caps?.extras ?: NO_EXTRAS
                val series = draft.seriesCap ?: ChannelDraft.DEFAULT_SERIES_CAP
                val film = draft.filmCap ?: ChannelDraft.DEFAULT_FILM_CAP
                Ordering(mode, OrderingCaps(series, film, capExtras), extras)
            }
        }
    }

    private fun timingOf(
        utcOffsetMinutes: Int,
        carry: ChannelRules?,
        ordering: Ordering?,
    ): Timing {
        if (carry == null) {
            return Timing(
                blockLength = null,
                alignToGrid = false,
                epochHours = EPOCH_HOURS,
                graceHours = null,
                gridMinutes = null,
                minRuntimeSeconds = null,
                broadcastDay = BroadcastDay(utcOffsetMinutes, LineupDefaults.DAY_START),
            )
        }
        // Caps need a 24-hour epoch; a shorter carried one gives way when caps are written.
        val capped = ordering?.caps != null
        val shortEpoch = carry.timing.effectiveEpochHours < EPOCH_HOURS
        return if (capped && shortEpoch) carry.timing.copy(epochHours = EPOCH_HOURS) else carry.timing
    }

    private fun filtersOf(
        draft: DraftFilters,
        carried: Filters?,
    ): Filters? {
        val years = draft.yearFrom != null && draft.yearTo != null
        val hasRuntime = draft.minMinutes != null || draft.maxMinutes != null
        val built =
            Filters(
                genres = draft.genres.ifEmpty { null },
                yearFrom = draft.yearFrom.takeIf { years },
                yearTo = draft.yearTo.takeIf { years },
                minRuntimeMinutes = draft.minMinutes,
                maxRuntimeMinutes = draft.maxMinutes,
                kind =
                    when (draft.only) {
                        OnlyKind.Films -> FilterKind.Movie
                        OnlyKind.Shows -> FilterKind.Episode
                        null -> null
                    },
                extras = carried?.extras ?: NO_EXTRAS,
                yearsExtras = if (years) carried?.yearsExtras ?: NO_EXTRAS else NO_EXTRAS,
                runtimeExtras = if (hasRuntime) carried?.runtimeExtras ?: NO_EXTRAS else NO_EXTRAS,
            )
        return built.takeUnless { it.isEmpty && it.extras.isEmpty() }
    }

    // The source of [draft], with the unknown fields of the stored source it came from (a copy keeps them). Each stored
    // source is used once, in order, so duplicates with different extras keep their own. A source removed and added
    // again keeps its stored extras, which is intended.
    private fun sourceOf(
        draft: DraftSource,
        remaining: MutableList<Source>?,
    ): Source {
        val fresh = bareSourceOf(draft)
        val at = remaining?.indexOfFirst { bare(it) == fresh } ?: -1
        return if (at < 0) fresh else withExtras(fresh, checkNotNull(remaining).removeAt(at).extras)
    }

    private fun bareSourceOf(source: DraftSource): Source =
        when (source) {
            is DraftSource.Library -> Source.Library(CanonicalId.parse(source.key))
            is DraftSource.Collection -> Source.Collection(CanonicalId.parse(source.key))
            is DraftSource.Tag -> Source.Tag(source.key)
            is DraftSource.Genre -> Source.Genre(source.key)
            is DraftSource.Show -> Source.Series(CanonicalId.parse(source.key))
            is DraftSource.Pick -> Source.Item(CanonicalId.parse(source.key))
        }

    private fun bare(source: Source): Source = withExtras(source, NO_EXTRAS)

    private fun withExtras(
        source: Source,
        extras: JsonObject,
    ): Source =
        when (source) {
            is Source.Library -> source.copy(extras = extras)
            is Source.Collection -> source.copy(extras = extras)
            is Source.Tag -> source.copy(extras = extras)
            is Source.Genre -> source.copy(extras = extras)
            is Source.Series -> source.copy(extras = extras)
            is Source.Item -> source.copy(extras = extras)
            is Source.YearRange -> source.copy(extras = extras)
        }

    /**
     * The draft of [rules] at [number]. What the builder cannot show goes to [ChannelDraft.kept] as structured items;
     * [labels] gives each source the words the viewer sees. The draft's seed is the rules' seed.
     */
    fun fromRules(
        rules: ChannelRules,
        number: Int?,
        labels: (DraftSource) -> String,
    ): ChannelDraft {
        val caps = rules.effectiveCaps
        val kept = KeptReader.itemsOf(rules)
        // What the builder cannot show stays in force until the viewer chooses: no order, or no caps, says so.
        val order =
            when {
                kept.any { it is KeptItem.Order } -> null
                rules.orderingMode == OrderingMode.Rotation -> PlayOrder.Rotation
                rules.orderingMode == OrderingMode.InOrder -> PlayOrder.InOrder
                else -> PlayOrder.Shuffle
            }
        val uncapped = KeptItem.NoCaps in kept
        return ChannelDraft(
            name = rules.name.orEmpty(),
            sources = rules.sources.mapNotNull { draftSourceOf(it, labels) },
            leaveOut = rules.effectiveExclusions.mapNotNull { draftSourceOf(it, labels) },
            filters = draftFiltersOf(rules),
            order = order,
            seriesCap = if (uncapped) null else caps?.effectiveSeries ?: ChannelDraft.DEFAULT_SERIES_CAP,
            filmCap = if (uncapped) null else caps?.effectiveFilm ?: ChannelDraft.DEFAULT_FILM_CAP,
            kids = number != null && number in Band.Kids.numbers,
            number = number,
            kept = kept.takeIf { it.isNotEmpty() }?.let { KeptSettings(rules.name.orEmpty(), it) },
            numberChosen = false,
            seed = rules.seed,
        )
    }

    private fun draftFiltersOf(rules: ChannelRules): DraftFilters {
        val filters = rules.filters
        return DraftFilters(
            genres = filters?.genres.orEmpty(),
            yearFrom = filters?.yearFrom,
            yearTo = filters?.yearTo,
            minMinutes = filters?.minRuntimeMinutes,
            maxMinutes = filters?.maxRuntimeMinutes,
            only =
                when (filters?.kind) {
                    FilterKind.Movie -> OnlyKind.Films
                    FilterKind.Episode -> OnlyKind.Shows
                    null -> null
                },
            maxRating = rules.maxRating,
        )
    }

    // Null for a year range: the builder cannot show it (it is a kept item).
    private fun draftSourceOf(
        source: Source,
        labels: (DraftSource) -> String,
    ): DraftSource? {
        val plain: DraftSource? =
            when (source) {
                is Source.Library -> DraftSource.Library(source.id.value, "")
                is Source.Collection -> DraftSource.Collection(source.id.value, "")
                is Source.Tag -> DraftSource.Tag(source.name, "")
                is Source.Genre -> DraftSource.Genre(source.name, "")
                is Source.Series -> DraftSource.Show(source.id.value, "")
                is Source.Item -> DraftSource.Pick(source.id.value, "")
                is Source.YearRange -> null
            }
        return plain?.let { it.withLabel(labels(it)) }
    }

    /** Which of a rules value's settings the builder cannot show, as kept items. */
    private object KeptReader {
        fun itemsOf(rules: ChannelRules): List<KeptItem> {
            val items = ArrayList<KeptItem>()
            when (rules.orderingMode) {
                OrderingMode.Chronological -> items += KeptItem.Order(KeptOrder.Chronological)
                OrderingMode.RandomInOrder -> items += KeptItem.Order(KeptOrder.RandomInOrder)
                else -> Unit
            }
            rules.sources.filterIsInstance<Source.YearRange>().forEach { items += KeptItem.YearsSource(it.from, it.to) }
            if (rules.orderingMode == OrderingMode.Shuffle && rules.effectiveCaps == null) items += KeptItem.NoCaps
            val epoch = rules.timing.effectiveEpochHours
            if (epoch != EPOCH_HOURS) items += KeptItem.EpochHours(epoch)
            if (rules.timing.effectiveBlockLength != 1) items += KeptItem.BlockLength(rules.timing.effectiveBlockLength)
            if (rules.timing.effectiveAlignToGrid) items += KeptItem.BackToBackOff
            if (rules.fillerMode != FillerMode.None) items += KeptItem.Filler
            if (hasOtherSettings(rules)) items += KeptItem.OtherSettings
            return items
        }

        // Anything left that the builder would write differently: extras, a year-range exclusion, a filler object that
        // does nothing, or timing the builder writes otherwise.
        private fun hasOtherSettings(rules: ChannelRules): Boolean =
            hasExtras(rules) ||
                timingDiffers(rules.timing) ||
                rules.effectiveExclusions.any { it is Source.YearRange } ||
                (rules.filler != null && rules.fillerMode == FillerMode.None)

        private fun hasExtras(rules: ChannelRules): Boolean {
            val sourceExtras = (rules.sources + rules.effectiveExclusions).any { it.extras.isNotEmpty() }
            val filterExtras = rules.filters?.let { listOf(it.extras, it.yearsExtras, it.runtimeExtras) }.orEmpty()
            val orderingExtras = listOfNotNull(rules.ordering?.extras, rules.ordering?.caps?.extras)
            val timingExtras =
                rules.timing.extras.isNotEmpty() ||
                    rules.timing.broadcastDay.extras
                        .isNotEmpty()
            val nested = (filterExtras + orderingExtras).any { it.isNotEmpty() }
            return rules.extras.isNotEmpty() || sourceExtras || nested || timingExtras
        }

        private fun timingDiffers(timing: Timing): Boolean =
            timing.graceHours != null ||
                timing.gridMinutes != null ||
                timing.minRuntimeSeconds != null ||
                timing.broadcastDay.effectiveStart != LineupDefaults.DAY_START
    }
}
