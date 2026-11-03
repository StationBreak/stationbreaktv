// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed

/** How a channel plays its pool. */
enum class PlayOrder { Shuffle, Rotation, InOrder }

/**
 * Something a channel draws from or leaves out. [key] is what the rules store (an ID, or the exact name of a genre or
 * tag); [label] is what the viewer sees.
 */
sealed interface DraftSource {
    val key: String
    val label: String

    /** The same source under another [label]. */
    fun withLabel(label: String): DraftSource

    data class Library(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }

    data class Collection(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }

    data class Tag(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }

    data class Genre(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }

    /** A show: every episode of the series, live episodes included. */
    data class Show(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }

    /** One film the viewer picked by title. */
    data class Pick(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }
}

/** What narrows the pool beyond its sources. Empty and null parts narrow nothing. */
data class DraftFilters(
    val genres: List<String> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minMinutes: Int? = null,
    val maxMinutes: Int? = null,
    val only: OnlyKind? = null,
    val maxRating: String? = null,
)

enum class OnlyKind { Films, Shows }

/**
 * Settings of a copied built-in channel that the builder cannot show or change, as the screen's read-only "Kept from"
 * lines. Structured values only: the screen words them. [sourceId] is the channel they were read from (the built-in
 * channel of a copy, the channel itself for an edit), so that saving carries them over from its stored rules.
 */
data class KeptSettings(
    val fromName: String,
    val items: List<KeptItem>,
    val sourceId: ChannelId? = null,
)

sealed interface KeptItem {
    data class Order(
        val mode: KeptOrder,
    ) : KeptItem

    data class YearsSource(
        val from: Int,
        val to: Int,
    ) : KeptItem

    data class BlockLength(
        val n: Int,
    ) : KeptItem

    /** A shuffle with no caps: it stays uncapped until the viewer turns caps on or takes the builder's settings. */
    data object NoCaps : KeptItem

    /** The day is cut into epochs shorter than 24 hours; caps would need 24. */
    data class EpochHours(
        val hours: Int,
    ) : KeptItem

    /** The channel is laid out on the grid (the builder's channels play back to back). */
    data object BackToBackOff : KeptItem

    data object Filler : KeptItem

    /** Anything else the builder has no control for. */
    data object OtherSettings : KeptItem
}

enum class KeptOrder { Chronological, RandomInOrder }

/** Why a draft cannot be saved yet. */
enum class DraftProblem {
    NameEmpty,
    NameTooLong,
    NoSources,
    CapOutOfRange,
    YearsIncomplete,
    YearsOutOfRange,
    YearsReversed,
    MinutesOutOfRange,
    MinutesReversed,
}

/**
 * A channel as the builder holds it. [numberChosen] is true only when the viewer picked [number]; otherwise creating
 * works the number out inside the lineup lock. [seed] is the preview's; blank drafts and copies draw a fresh one, an
 * edit keeps the channel's, and Save keeps the STORED seed unless the draft came from a saved Reshuffle.
 */
@Suppress("LongParameterList") // The draft's fields, one for one.
data class ChannelDraft(
    val name: String,
    val sources: List<DraftSource>,
    val leaveOut: List<DraftSource>,
    val filters: DraftFilters,
    val order: PlayOrder = PlayOrder.Shuffle,
    val seriesCap: Int = DEFAULT_SERIES_CAP,
    val filmCap: Int = DEFAULT_FILM_CAP,
    val kids: Boolean = false,
    val number: Int? = null,
    val kept: KeptSettings? = null,
    val numberChosen: Boolean = false,
    val seed: Seed,
) {
    /** The first thing wrong with the draft, or null when it can be saved. */
    fun problem(): DraftProblem? {
        val trimmed = name.trim()
        val points = trimmed.codePointCount(0, trimmed.length)
        return when {
            points == 0 -> DraftProblem.NameEmpty
            points > MAX_NAME_CODE_POINTS -> DraftProblem.NameTooLong
            sources.isEmpty() && !hasKeptYears() -> DraftProblem.NoSources
            order != PlayOrder.InOrder && (seriesCap !in CAPS || filmCap !in CAPS) -> DraftProblem.CapOutOfRange
            else -> yearsProblem() ?: minutesProblem()
        }
    }

    // A year range the copy keeps is a source of the channel too.
    private fun hasKeptYears(): Boolean = kept?.items?.any { it is KeptItem.YearsSource } == true

    /**
     * This draft once the viewer has chosen [order]: a kept order is the builder's now, and so is the lack of caps when
     * the order is not Shuffle.
     */
    fun withOrder(order: PlayOrder): ChannelDraft =
        copy(order = order).keeping { it is KeptItem.Order || (order != PlayOrder.Shuffle && it == KeptItem.NoCaps) }

    /** This draft once the viewer has set caps: an uncapped shuffle and a short epoch are the builder's now. */
    fun withCaps(
        series: Int,
        film: Int,
    ): ChannelDraft {
        val capped = copy(seriesCap = series, filmCap = film)
        return capped.keeping { it == KeptItem.NoCaps || it is KeptItem.EpochHours }
    }

    // The draft without the kept items [dropped] says so for; no kept items left is no kept settings.
    private fun keeping(dropped: (KeptItem) -> Boolean): ChannelDraft {
        val left = kept?.let { k -> k.copy(items = k.items.filterNot(dropped)) }
        return copy(kept = left?.takeIf { it.items.isNotEmpty() })
    }

    // Both ends or neither; in 1..9999; in order.
    private fun yearsProblem(): DraftProblem? {
        val from = filters.yearFrom
        val to = filters.yearTo
        return when {
            (from == null) != (to == null) -> DraftProblem.YearsIncomplete
            from == null || to == null -> null
            from !in YEARS || to !in YEARS -> DraftProblem.YearsOutOfRange
            from > to -> DraftProblem.YearsReversed
            else -> null
        }
    }

    private fun minutesProblem(): DraftProblem? {
        val min = filters.minMinutes
        val max = filters.maxMinutes
        return when {
            listOfNotNull(min, max).any { it !in MINUTES } -> DraftProblem.MinutesOutOfRange
            min != null && max != null && min > max -> DraftProblem.MinutesReversed
            else -> null
        }
    }

    companion object {
        const val DEFAULT_SERIES_CAP = 3
        const val DEFAULT_FILM_CAP = 1
        const val MAX_NAME_CODE_POINTS = 100
        private val CAPS = 1..10
        private val YEARS = 1..9999
        private val MINUTES = 1..1440
    }
}
