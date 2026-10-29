// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.concurrent.Volatile

/**
 * The rule engine's whole input for one day, as this TV held its library before a cut-off (spec 10, Unit 3): the items,
 * the series they belong to and the collections, in one plain value (docs/rules-format, rule 5, the *library
 * snapshot*). Nothing in it is read again later, so one read gives one state.
 *
 * It carries its own validity, because the type is all the engine sees:
 * - [cutoffMs] and [libraries] let the `ScheduleSource` that composes the day check that this is the snapshot for the
 *   day it is building (the cut-off must equal that day's `S - G`) and for the libraries it asked for;
 * - [collectionsSynced] lets a reader tell a collection that was deleted from one this TV has not listed yet.
 *
 * It holds no names: series and collection names are not rule inputs, and spec 12 reads them through its own
 * current-state read. Values are kept exactly as stored and nothing is compared here; that is the evaluator's job. It
 * does carry match-ready derived data: [RuleItem.foldedGenres] and its like are the stored names through the ASCII fold
 * of docs/rules-format.md, "String matching", shared across the snapshot (see [NameFolds]), so a pass does not fold.
 *
 * @property cutoffMs the cut-off the snapshot was taken at, in milliseconds since 1970-01-01T00:00:00Z: the cut-off
 *   rounded up to a whole millisecond. It is exclusive: a version first seen at `seenFromMs < cutoffMs` is held, and
 *   one seen at exactly `cutoffMs` is not.
 * @property libraries the libraries the items were selected from: only the requested IDs that are canonical.
 * @property items the usable items, in canonical-ID order, which is the scheduler's order. The evaluator iterates this
 *   and nothing else.
 * @property series the series that a returned episode points to, by ID. A lookup only: it carries no order meaning.
 *   An episode whose series is unknown has no entry.
 * @property collections the collections held before the cut-off, by ID, whatever the selected [libraries] are, because
 *   their members are IDs. A lookup only.
 * @property collectionsSynced false until a full pass over the account's collections has succeeded.
 */
data class RuleSnapshot(
    val cutoffMs: Long,
    val libraries: Set<CanonicalId>,
    val items: List<RuleItem>,
    val series: Map<CanonicalId, RuleSeries>,
    val collections: Map<CanonicalId, RuleCollection>,
    val collectionsSynced: Boolean,
)

/**
 * One item of a [RuleSnapshot]: the scheduler's [item], composed rather than copied so it is built once, through
 * `ScheduleItem`'s own checks, with what the rules read beside it. [officialRating] is as stored: null, `""` and `" "`
 * are three values. [genres], [tags] and [studios] keep the server's order; [studios] is empty until the library
 * holds them.
 */
data class RuleItem(
    val item: ScheduleItem,
    val libraryId: CanonicalId,
    val seasonId: CanonicalId?,
    val officialRating: String?,
    val genres: List<String>,
    val tags: List<String>,
    val studios: List<String> = emptyList(),
) {
    @Volatile private var foldedGenresCache: List<String>? = null

    @Volatile private var foldedTagsCache: List<String>? = null

    @Volatile private var foldedStudiosCache: List<String>? = null

    /** [genres] folded (`foldNameAscii`) in order, worked out on first use and kept: a rule match reads this. */
    val foldedGenres: List<String>
        get() = foldedGenresCache ?: foldNames(genres).also { foldedGenresCache = it }

    /** [tags] folded, as [foldedGenres]. */
    val foldedTags: List<String>
        get() = foldedTagsCache ?: foldNames(tags).also { foldedTagsCache = it }

    /** [studios] cleaned ([cleanStudioName]) and folded, as [foldedGenres]. */
    val foldedStudios: List<String>
        get() = foldedStudiosCache ?: foldStudioNames(studios).also { foldedStudiosCache = it }

    /** Sets [foldedGenres], [foldedTags] and [foldedStudios] from [folds], shared with the rest of the snapshot. */
    fun primeFolds(folds: NameFolds) {
        foldedGenresCache = folds.of(genres)
        foldedTagsCache = folds.of(tags)
        foldedStudiosCache = folds.ofStudios(studios)
    }
}

/** A series as held before the cut-off, with the fields a rule reads. [officialRating] is as stored. */
data class RuleSeries(
    val id: CanonicalId,
    val officialRating: String?,
    val genres: List<String>,
    val tags: List<String>,
    val studios: List<String> = emptyList(),
) {
    @Volatile private var foldedGenresCache: List<String>? = null

    @Volatile private var foldedTagsCache: List<String>? = null

    @Volatile private var foldedStudiosCache: List<String>? = null

    /** [genres] folded (`foldNameAscii`) in order, worked out on first use and kept. */
    val foldedGenres: List<String>
        get() = foldedGenresCache ?: foldNames(genres).also { foldedGenresCache = it }

    /** [tags] folded, as [foldedGenres]. */
    val foldedTags: List<String>
        get() = foldedTagsCache ?: foldNames(tags).also { foldedTagsCache = it }

    /** [studios] cleaned ([cleanStudioName]) and folded, as [foldedGenres]. */
    val foldedStudios: List<String>
        get() = foldedStudiosCache ?: foldStudioNames(studios).also { foldedStudiosCache = it }

    /** Sets [foldedGenres], [foldedTags] and [foldedStudios] from [folds], shared with the rest of the snapshot. */
    fun primeFolds(folds: NameFolds) {
        foldedGenresCache = folds.of(genres)
        foldedTagsCache = folds.of(tags)
        foldedStudiosCache = folds.ofStudios(studios)
    }
}

/**
 * A collection as held before the cut-off: its members by kind. A member is an ID and may be outside the selected
 * libraries. The sets carry no order meaning.
 */
data class RuleCollection(
    val id: CanonicalId,
    val movies: Set<CanonicalId>,
    val episodes: Set<CanonicalId>,
    val series: Set<CanonicalId>,
    val seasons: Set<CanonicalId>,
)
