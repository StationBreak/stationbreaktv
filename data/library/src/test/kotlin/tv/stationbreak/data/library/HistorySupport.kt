// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.SeriesInfo
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Writes [items] as a page of [library] with no checkpoint (a changed-since page): the cheapest way to edit rows. */
internal suspend fun RoomLibraryRepository.put(
    account: LibraryAccount,
    library: ItemId,
    vararg items: MediaItem,
): PageWrite = upsertItems(account, library, items.toList())

/**
 * Spec 09 Vimes H1, for every item of [account]: its versions have strictly increasing, positive-length intervals
 * `[seenFromMs, supersededAtMs)` that touch end to start, the first one starts at the item's `firstSeenMs`, and the
 * current row starts where the last one ended (or is the only one).
 */
internal suspend fun RoomLibraryRepository.assertIntervalsContiguous(account: LibraryAccount) {
    val versions = versions(account).groupBy { it.itemId }
    currentRows(account).forEach { current ->
        val id = current.itemId
        val own = versions[id].orEmpty().sortedBy { it.seenFromMs }
        if (own.isEmpty()) {
            assertEquals("$id: no versions, so it starts when first seen", current.firstSeenMs, current.seenFromMs)
        } else {
            assertEquals("$id: the first version starts at firstSeenMs", current.firstSeenMs, own.first().seenFromMs)
            own.forEach { assertTrue("$id: an empty interval $it", it.seenFromMs < it.supersededAtMs) }
            own.zipWithNext { a, b -> assertEquals("$id: a gap or an overlap", a.supersededAtMs, b.seenFromMs) }
            assertEquals("$id: the current row starts at the last cut", own.last().supersededAtMs, current.seenFromMs)
        }
        assertTrue("$id: firstSeenMs is not after seenFromMs", current.firstSeenMs <= current.seenFromMs)
    }
}

/** A canonical item ID (32 lower-case hex digits) for the number [n]. */
internal fun hexId(n: Int): String = n.toString(16).padStart(32, '0')

/** A movie with a canonical ID, created a day before [EPOCH]; the shape the as-of read accepts. */
internal fun sitem(
    n: Int,
    runtime: kotlin.time.Duration? = 90.minutes,
    library: ItemId = MOVIES,
) = MediaItem(
    id = ItemId(hexId(n)),
    name = "Name of $n",
    kind = ItemKind.Movie,
    runtime = runtime,
    parentId = library,
    dateCreated = EPOCH - 1.days,
)

/** A series as the server describes it, with a rating and lists given or empty. */
internal fun series(
    id: String,
    rating: String? = null,
    genres: List<String> = emptyList(),
    tags: List<String> = emptyList(),
    name: String = "Series $id",
) = SeriesInfo(ItemId(id), name, rating, genres, tags)

/** As [put], for series. */
internal suspend fun RoomLibraryRepository.putSeries(
    account: LibraryAccount,
    library: ItemId,
    vararg series: SeriesInfo,
): PageWrite = upsertSeries(account, library, series.toList())

/** [assertIntervalsContiguous] for the series of [account] and their versions (spec 10, Vimes H1). */
internal suspend fun RoomLibraryRepository.assertSeriesIntervalsContiguous(account: LibraryAccount) {
    val versions = seriesVersions(account).groupBy { it.seriesId }
    currentSeries(account).forEach { current ->
        val id = current.seriesId
        val own = versions[id].orEmpty().sortedBy { it.seenFromMs }
        if (own.isEmpty()) {
            assertEquals("$id: no versions, so it starts when first seen", current.firstSeenMs, current.seenFromMs)
        } else {
            assertEquals("$id: the first version starts at firstSeenMs", current.firstSeenMs, own.first().seenFromMs)
            own.forEach { assertTrue("$id: an empty interval $it", it.seenFromMs < it.supersededAtMs) }
            own.zipWithNext { a, b -> assertEquals("$id: a gap or an overlap", a.supersededAtMs, b.seenFromMs) }
            assertEquals("$id: the current row starts at the last cut", own.last().supersededAtMs, current.seenFromMs)
        }
        assertTrue("$id: firstSeenMs is not after seenFromMs", current.firstSeenMs <= current.seenFromMs)
    }
}

/** A collection member of [kind] (a movie unless said), for [RoomLibraryRepository.writeCollection]. */
internal fun cm(
    id: String,
    kind: CollectionMemberKind = CollectionMemberKind.Movie,
) = CollectionMember(ItemId(id), kind)

/** [cm] for several movies at once. */
internal fun movies(vararg ids: String): List<CollectionMember> = ids.map { cm(it) }

/**
 * Spec 10 Vimes H1, for every member of every collection of [account]: at most one row has a null `supersededAtMs`
 * (Room cannot declare a partial unique index, so this is asserted after every write), each row's interval is not
 * empty, and a member's rows are in order and never overlap.
 */
internal suspend fun RoomLibraryRepository.assertMemberIntervalsSound(account: LibraryAccount) {
    collectionMembers(account).groupBy { it.collectionId to it.memberId }.forEach { (key, rows) ->
        val label = "${key.first}/${key.second}"
        assertTrue("$label: more than one current row $rows", rows.count { it.supersededAtMs == null } <= 1)
        val ordered = rows.sortedBy { it.seenFromMs }
        ordered.forEach { row ->
            val end = row.supersededAtMs
            assertTrue("$label: an empty interval $row", end == null || row.seenFromMs < end)
        }
        ordered.zipWithNext { a, b ->
            val end = a.supersededAtMs
            assertTrue("$label: $a is open before $b", end != null)
            assertTrue("$label: $a overlaps $b", end!! <= b.seenFromMs)
        }
    }
}

/** [assertIntervalsContiguous] for the collection lifecycles of [account] (spec 10, Vimes H1). */
internal suspend fun RoomLibraryRepository.assertCollectionIntervalsContiguous(account: LibraryAccount) {
    val versions = collectionVersions(account).groupBy { it.collectionId }
    collections(account).forEach { current ->
        val id = current.collectionId
        val own = versions[id].orEmpty().sortedBy { it.seenFromMs }
        if (own.isEmpty()) {
            assertEquals("$id: no versions, so it starts when first seen", current.firstSeenMs, current.seenFromMs)
        } else {
            assertEquals("$id: the first version starts at firstSeenMs", current.firstSeenMs, own.first().seenFromMs)
            own.forEach { assertTrue("$id: an empty interval $it", it.seenFromMs < it.supersededAtMs) }
            own.zipWithNext { a, b -> assertEquals("$id: a gap or an overlap", a.supersededAtMs, b.seenFromMs) }
            assertEquals("$id: the current row starts at the last cut", own.last().supersededAtMs, current.seenFromMs)
        }
        assertTrue("$id: firstSeenMs is not after seenFromMs", current.firstSeenMs <= current.seenFromMs)
    }
}

/** The members of a collection that are current (not superseded), as `id:kind`, sorted: the set a diff sees. */
internal suspend fun RoomLibraryRepository.currentMembers(
    account: LibraryAccount,
    collection: String,
): List<String> =
    collectionMembers(account)
        .filter { it.collectionId == collection && it.supersededAtMs == null }
        .map { "${it.memberId}:${it.memberKind}" }
        .sorted()
