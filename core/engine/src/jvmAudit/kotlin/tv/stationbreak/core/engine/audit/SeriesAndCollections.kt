// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.NameFolds
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleSeries

/** The series and the collections of the `audit-snapshot` file (see [AuditSnapshotCodec]). */
internal object SeriesAndCollections {
    fun readSeries(
        top: SnapshotFields,
        folds: NameFolds,
    ): Map<CanonicalId, RuleSeries> {
        val out = LinkedHashMap<CanonicalId, RuleSeries>()
        val all = top.objects("series", AuditSnapshotCodec.MAX_SERIES).map { readOneSeries(it, folds) }
        for (series in all.sortedBy { it.id.value }) {
            if (out.put(series.id, series) != null) throw SnapshotProblem("file.series has an ID twice")
        }
        return out
    }

    private fun readOneSeries(
        f: SnapshotFields,
        folds: NameFolds,
    ): RuleSeries {
        val series =
            RuleSeries(
                id = f.id("id"),
                officialRating = f.textOrNull("officialRating"),
                genres = f.texts("genres"),
                tags = f.texts("tags"),
                studios = f.texts("studios"),
                name = f.textOrNull("name"),
            )
        f.done()
        series.primeFolds(folds)
        return series
    }

    fun readCollections(top: SnapshotFields): Map<CanonicalId, RuleCollection> {
        val out = LinkedHashMap<CanonicalId, RuleCollection>()
        val all = top.objects("collections", AuditSnapshotCodec.MAX_COLLECTIONS).map(::readOneCollection)
        for (collection in all.sortedBy { it.id.value }) {
            if (out.put(collection.id, collection) != null) throw SnapshotProblem("file.collections has an ID twice")
        }
        return out
    }

    private fun readOneCollection(f: SnapshotFields): RuleCollection {
        val collection =
            RuleCollection(
                id = f.id("id"),
                movies = members(f, "movies"),
                episodes = members(f, "episodes"),
                series = members(f, "series"),
                seasons = members(f, "seasons"),
                name = f.textOrNull("name"),
            )
        f.done()
        return collection
    }

    // A set in canonical-ID order, as the app's read builds it.
    private fun members(
        f: SnapshotFields,
        key: String,
    ): Set<CanonicalId> {
        val ids = f.ids(key)
        if (ids.size >
            AuditSnapshotCodec.MAX_MEMBERS
        ) {
            throw SnapshotProblem("${f.where}.$key has more than $AuditSnapshotCodec.MAX_MEMBERS entries")
        }
        return ids.sortedBy { it.value }.toCollection(LinkedHashSet())
    }
}
