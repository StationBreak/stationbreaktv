// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.NameFolds
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.RulesReader
import tv.stationbreak.core.rules.RulesResult

/**
 * The `audit-snapshot` file, version 1 (tools/audit-export/FORMAT.md): a library as `RuleSnapshot` holds it, in JSON.
 * The file is untrusted input: its size is checked before anything is parsed, every field is typed and strict, an
 * unknown key is refused, and a [SnapshotResult.Invalid] reason names the place and the rule but holds none of the
 * file's text.
 */
internal object AuditSnapshotCodec {
    const val FORMAT = "audit-snapshot"
    const val VERSION = 1

    /** The longest text read (256 Mi characters), the most items, and the most members of one collection. */
    const val MAX_CHARS = 256 * 1024 * 1024
    const val MAX_ITEMS = 2_000_000
    const val MAX_SERIES = 500_000
    const val MAX_COLLECTIONS = 100_000
    const val MAX_MEMBERS = 2_000_000
    const val MAX_CUSTOM_CHANNELS = 64

    /** Reads [text], or says why it is not a snapshot. [maxChars] is a seam for tests. */
    fun read(
        text: String,
        maxChars: Int = MAX_CHARS,
    ): SnapshotResult =
        try {
            if (text.length > maxChars) throw SnapshotProblem("The file is too big (over $maxChars characters)")
            val parsed = Json.parseToJsonElement(text)
            val root = parsed as? JsonObject ?: throw SnapshotProblem("The file is not an object")
            SnapshotResult.Ok(readRoot(root))
        } catch (problem: SnapshotProblem) {
            SnapshotResult.Invalid(problem.message.orEmpty())
        } catch (
            @Suppress("SwallowedException") notJson: SerializationException,
        ) {
            SnapshotResult.Invalid("The file is not valid JSON")
        } catch (
            @Suppress("SwallowedException") notJson: IllegalArgumentException,
        ) {
            SnapshotResult.Invalid("The file is not valid JSON")
        }

    /** The file for [input]: deterministic, in canonical order (see [SnapshotWriter]). */
    fun write(input: AuditInput): String = SnapshotWriter.write(input)

    private fun readRoot(root: JsonObject): AuditInput {
        val top = SnapshotFields(root, "file")
        if (top.text("format") != FORMAT) throw SnapshotProblem("file.format is not \"$FORMAT\"")
        if (top.long("version") != VERSION.toLong()) throw SnapshotProblem("file.version is not $VERSION")
        val cutoffMs = top.long("cutoffMs")
        val synced = top.bool("collectionsSynced")
        val libraries = top.ids("libraries").toSet()
        val folds = NameFolds()
        val items = readItems(top, folds)
        val series = SeriesAndCollections.readSeries(top, folds)
        val collections = SeriesAndCollections.readCollections(top)
        val viewer = top.objOrNull("viewer")?.let(::readViewer)
        top.done()
        val snapshot = RuleSnapshot(cutoffMs, libraries, items, series, collections, synced)
        return AuditInput(snapshot, viewer)
    }

    private fun readItems(
        top: SnapshotFields,
        folds: NameFolds,
    ): List<RuleItem> {
        val items = top.objects("items", MAX_ITEMS).map { readItem(it, folds) }.sortedBy { it.item.id.value }
        for (i in 1 until items.size) {
            if (items[i].item.id == items[i - 1].item.id) throw SnapshotProblem("file.items has an ID twice")
        }
        return items
    }

    private fun readItem(
        f: SnapshotFields,
        folds: NameFolds,
    ): RuleItem {
        val kind =
            when (f.text("kind")) {
                "Movie" -> ScheduleItemKind.Movie
                "Episode" -> ScheduleItemKind.Episode
                else -> throw SnapshotProblem("${f.where}.kind is not Movie or Episode")
            }
        val runtimeMs = f.long("runtimeMs")
        if (runtimeMs < 1) throw SnapshotProblem("${f.where}.runtimeMs is not a runtime (1 or more)")
        val schedule =
            scheduleItem(
                f,
                kind,
                runtimeMs,
            )
        val item =
            RuleItem(
                item = schedule,
                libraryId = f.id("libraryId"),
                seasonId = f.idOrNull("seasonId"),
                officialRating = f.textOrNull("officialRating"),
                genres = f.texts("genres"),
                tags = f.texts("tags"),
                studios = f.texts("studios"),
                name = f.textOrNull("name"),
            )
        f.done()
        item.primeFolds(folds)
        return item
    }

    private fun scheduleItem(
        f: SnapshotFields,
        kind: ScheduleItemKind,
        runtimeMs: Long,
    ): ScheduleItem {
        val id = f.id("id")
        val createdAtMs = f.long("createdAtMs")
        val deletedAtMs = f.longOrNull("deletedAtMs")
        val premiereDate = f.textOrNull("premiereDate")
        val productionYear = f.intOrNull("productionYear")
        val seriesId = f.idOrNull("seriesId")
        val seasonNumber = f.intOrNull("seasonNumber")
        val episodeNumber = f.intOrNull("episodeNumber")
        val problem = ScheduleItem.problem(kind, premiereDate, productionYear, seriesId, seasonNumber, episodeNumber)
        if (problem != null) throw SnapshotProblem("${f.where}: ${problem.message}")
        return ScheduleItem(
            id,
            kind,
            runtimeMs,
            createdAtMs,
            deletedAtMs,
            premiereDate,
            productionYear,
            seriesId,
            seasonNumber,
            episodeNumber,
        )
    }

    private fun readViewer(f: SnapshotFields): Viewer {
        val switchedOn = f.texts("switchedOn")
        val customs =
            f.objects("customChannels", MAX_CUSTOM_CHANNELS).map { c ->
                val name = c.text("name")
                val rules = RulesReader.read(c.plainObject("rules").toString())
                c.done()
                if (rules !is RulesResult.Ok) throw SnapshotProblem("${c.where}.rules are not usable rules")
                CustomChannel(name, rules.rules)
            }
        f.done()
        return Viewer(switchedOn, customs)
    }
}
