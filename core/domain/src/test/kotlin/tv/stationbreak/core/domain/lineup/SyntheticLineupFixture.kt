// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.engine.ExtrasFilter
import tv.stationbreak.core.engine.GrownUpHold
import tv.stationbreak.core.engine.KidHold
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.engine.lineup.curation.Curation
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.schedule.ScheduleContent
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.RulesLimits
import tv.stationbreak.core.rules.RulesWriter
import tv.stationbreak.core.testing.SyntheticLibrary
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * `docs/rules-format/fixtures/synthetic-lineup.json`: a published reference fixture for implementers of the rules
 * format. A fully synthetic library goes through the app's own path (`LineupService.refresh`, which calls
 * `LineupPlanner.plan`, then `BuiltInScheduleSource.channelDays`, which builds each switched-on channel's day with the
 * engine and `buildDay`), and the lineup and the days are written out. Nothing here is a reimplementation of either.
 * `SyntheticLineupFixtureTest` checks the file against this and rewrites it on `-PupdateFixtures=true`.
 */
internal object SyntheticLineupFixture {
    /** Items in the generated library; 3,000 keeps the file under a megabyte (the size test uses 15,000). */
    const val ITEMS = 3_000
    const val SEED = 0L
    const val DAYS = 3
    const val FORMAT = 1

    private val NOW: Instant = NOON
    private val LIBRARY = ItemId(LIBRARY_ID.value)

    private const val DESCRIPTION =
        "A published reference fixture for implementers of the rules format (docs/rules-format.md, " +
            "\"Verify your client\"). A synthetic library (versions.generator; nothing real) is given to the lineup " +
            "planner at first setup, and the days of every switched-on channel are built from the lineup it plans. " +
            "snapshot is the planner's input: items, series and collections as the engine reads them; the library's " +
            "other kinds (videos, music videos) and every item with no runtime are left out, as the app's own " +
            "snapshot leaves them out. lineup is what the planner chose: per channel its id, number, source, state " +
            "and seed (decimal text), and for a starter or custom channel its rules in the exact text RulesWriter " +
            "writes. A leftovers channel has no rules: its pool is the engine's, from the other channels. days[i] is " +
            "the broadcast day i of every switched-on channel: the day that holds inputs.nowMs is day 0. A channel's " +
            "blocks tile [epochStartMs, epochEndMs) and are half-open; a block is {startMs, endMs, itemId, " +
            "mediaStartOffsetMs} for a program or {startMs, endMs, slate: true}. Every millisecond count is decimal " +
            "text. A mismatch of versions (catalog, kid hold, grown-up hold, curation) means the files are not " +
            "comparable."

    /** The fixture's text. */
    fun build(): String = FixtureJson.write(document())

    private fun document(): JsonObject {
        val media = syntheticMedia()
        val items = media.map(::ruleItemOf)
        val series = seriesOf(media)
        val library = CountingLibrary()
        library.seed(items)
        library.delegate.seedSeries(ACCOUNT, *series.toTypedArray())
        val clock =
            object : Clock {
                override fun now(): Instant = NOW
            }
        val service = LineupService(RecordingStore(), library, clock, { 0 }, {}, StarterCatalog)
        val source = BuiltInScheduleSource(service, library) { selectionOf(LIBRARIES) }
        return runBlocking {
            library.delegate.useAccount(ACCOUNT)
            service.refresh(ACCOUNT, LIBRARIES)
            val lineup = checkNotNull(service.lineup(ACCOUNT).value) { "no lineup was planned" }.lineup
            jsonOf(
                "description" to json(DESCRIPTION),
                "fixtureFormat" to json(FORMAT),
                "versions" to versions(),
                "inputs" to inputs(),
                "snapshot" to snapshot(items, series),
                "lineup" to lineupOf(lineup),
                "days" to jsonArray((0 until DAYS).map { dayOf(source, it) }),
            )
        }
    }

    private fun versions() =
        jsonOf(
            "generator" to json("SyntheticLibrary size $ITEMS seed $SEED"),
            "catalog" to json(StarterCatalog.VERSION),
            "rulesFormat" to json(RulesLimits.SUPPORTED_FORMAT_VERSION.toInt()),
            "kidHold" to json(KidHold.VERSION_NAME),
            "grownUpHold" to json(GrownUpHold.VERSION_NAME),
            "curation" to json(Curation.VERSION_NAME),
            "extras" to json(ExtrasFilter.VERSION_NAME),
        )

    private fun inputs() =
        jsonOf(
            "serverId" to json(SERVER.value),
            "userId" to json(USER.value),
            "libraries" to jsonStrings(LIBRARIES.map { it.value }),
            "standardOffsetMinutes" to json(0),
            "nowMs" to jsonMs(NOW.toEpochMilliseconds()),
            "days" to json(DAYS),
            "planner" to
                jsonOf(
                    "current" to JsonNull,
                    "useMyCollections" to json(true),
                    "withoutCollections" to json(false),
                    "kept" to jsonStrings(emptyList()),
                    "catalog" to json(StarterCatalog.VERSION),
                ),
        )

    // The library as the app's size tests take it: movies and episodes that have a runtime.
    private fun syntheticMedia(): List<MediaItem> =
        SyntheticLibrary(ITEMS, SEED, LIBRARY)
            .items
            .filter { it.kind == ItemKind.Movie || it.kind == ItemKind.Episode }
            .filter { (it.runtime?.inWholeMilliseconds ?: 0L) > 0L }
            .toList()

    private fun ruleItemOf(media: MediaItem): RuleItem {
        val episode = media.kind == ItemKind.Episode
        return RuleItem(
            item =
                ScheduleItem(
                    id = CanonicalId.parse(media.id.value),
                    kind = if (episode) ScheduleItemKind.Episode else ScheduleItemKind.Movie,
                    runtimeMs = checkNotNull(media.runtime).inWholeMilliseconds,
                    createdAtMs = checkNotNull(media.dateCreated).toEpochMilliseconds(),
                    deletedAtMs = null,
                    premiereDate = null,
                    productionYear = media.productionYear,
                    seriesId = media.seriesId?.let { CanonicalId.parse(it.value) },
                    seasonNumber = media.seasonNumber,
                    episodeNumber = media.episodeNumber,
                ),
            libraryId = LIBRARY_ID,
            seasonId = null,
            officialRating = media.officialRating,
            genres = media.genres,
            tags = media.tags,
        )
    }

    // A series takes its first episode's rating, genres and tags, as the size tests make it.
    private fun seriesOf(media: List<MediaItem>): List<RuleSeries> =
        media
            .filter { it.seriesId != null }
            .groupBy { CanonicalId.parse(checkNotNull(it.seriesId).value) }
            .map { (id, episodes) ->
                val first = episodes.first()
                RuleSeries(id, first.officialRating, first.genres, first.tags)
            }.sortedBy { it.id.value }

    private fun snapshot(
        items: List<RuleItem>,
        series: List<RuleSeries>,
    ) = jsonOf(
        "libraryId" to json(LIBRARY_ID.value),
        "collectionsSynced" to json(true),
        "collections" to jsonArray(emptyList()),
        "series" to
            jsonArray(
                series.map {
                    jsonOf(
                        "id" to json(it.id.value),
                        "rating" to json(it.officialRating),
                        "genres" to jsonStrings(it.genres),
                        "tags" to jsonStrings(it.tags),
                    )
                },
            ),
        "items" to jsonArray(items.map(::itemOf)),
    )

    private fun itemOf(rule: RuleItem): JsonElement {
        val item = rule.item
        val members = LinkedHashMap<String, JsonElement>()
        members["id"] = json(item.id.value)
        members["kind"] = json(if (item.kind == ScheduleItemKind.Episode) "episode" else "movie")
        members["runtimeMs"] = jsonMs(item.runtimeMs)
        members["createdAtMs"] = jsonMs(item.createdAtMs)
        item.productionYear?.let { members["productionYear"] = json(it) }
        rule.officialRating?.let { members["rating"] = json(it) }
        members["genres"] = jsonStrings(rule.genres)
        members["tags"] = jsonStrings(rule.tags)
        item.seriesId?.let { members["seriesId"] = json(it.value) }
        item.seasonNumber?.let { members["season"] = json(it) }
        item.episodeNumber?.let { members["episode"] = json(it) }
        return JsonObject(members)
    }

    private fun lineupOf(lineup: Lineup) =
        jsonOf(
            "serverId" to json(lineup.serverId.value),
            "userId" to json(lineup.userId.value),
            "catalogVersion" to json(lineup.catalogVersion),
            "utcOffsetMinutes" to json(lineup.utcOffsetMinutes),
            "finalised" to json(lineup.finalised),
            "useMyCollections" to json(lineup.useMyCollections),
            "channels" to jsonArray(lineup.channels.map(::channelOf)),
        )

    private fun channelOf(channel: LineupChannel): JsonElement {
        val source = channel.source
        val rules = rulesOf(source)
        return jsonOf(
            "id" to json(channel.id.value),
            "number" to channel.number.let { if (it == null) JsonNull else json(it) },
            "source" to sourceOf(source),
            "state" to json(channel.state.name),
            "shelfReason" to json(channel.shelfReason?.name),
            "onSinceMs" to jsonMs(channel.onSinceMs),
            "seed" to json(rules?.seed?.format()),
            "rules" to json(rules?.let(RulesWriter::write)),
            "previousRules" to json((source as? ChannelSource.Starter)?.previousRules?.let(RulesWriter::write)),
        )
    }

    private fun rulesOf(source: ChannelSource): ChannelRules? =
        when (source) {
            is ChannelSource.Starter -> source.rules
            is ChannelSource.Custom -> source.rules
            is ChannelSource.Leftovers, is ChannelSource.Unusable -> null
        }

    private fun sourceOf(source: ChannelSource): JsonElement =
        when (source) {
            is ChannelSource.Starter -> {
                jsonOf(
                    "kind" to json("starter"),
                    "recipeKey" to json(source.recipeKey),
                    "edited" to json(source.edited),
                )
            }

            is ChannelSource.Leftovers -> {
                jsonOf(
                    "kind" to json("leftovers"),
                    "recipeKey" to json(source.recipeKey),
                    "maxRating" to json(source.maxRating),
                )
            }

            is ChannelSource.Custom -> {
                jsonOf("kind" to json("custom"), "recipeKey" to JsonNull)
            }

            is ChannelSource.Unusable -> {
                jsonOf("kind" to json("unusable"), "recipeKey" to JsonNull)
            }
        }

    // The switched-on channels' days in broadcast day [index] after the one that holds NOW, as the app builds them.
    private suspend fun dayOf(
        source: BuiltInScheduleSource,
        index: Int,
    ): JsonElement {
        val at = NOW + index.days
        val channels =
            source.channelDays(ACCOUNT, LIBRARIES, at).map { day ->
                jsonOf(
                    "id" to json(day.channel.id.value),
                    "number" to json(checkNotNull(day.channel.number)),
                    "name" to json(day.name),
                    "n" to jsonMs(day.n),
                    "epochStartMs" to jsonMs(day.epochStartMs),
                    "epochEndMs" to jsonMs(day.epochEndMs),
                    "ordering" to json(day.settings.orderingMode.name),
                    "seed" to json(day.settings.seed.format()),
                    "ceilingRank" to day.ceilingRank.let { if (it == null) JsonNull else json(it) },
                    "hidden" to json(day.hidden?.name),
                    "blocks" to jsonArray(day.day.blocks.map { blockOf(it.startMs, it.endMs, it.content) }),
                )
            }
        return jsonOf(
            "index" to json(index),
            "atMs" to jsonMs(at.toEpochMilliseconds()),
            "channels" to jsonArray(channels),
        )
    }

    private fun blockOf(
        startMs: Long,
        endMs: Long,
        content: ScheduleContent,
    ): JsonElement =
        when (content) {
            is ScheduleContent.Program -> {
                jsonOf(
                    "startMs" to jsonMs(startMs),
                    "endMs" to jsonMs(endMs),
                    "itemId" to json(content.itemId.value),
                    "mediaStartOffsetMs" to jsonMs(content.mediaStartOffsetMs),
                )
            }

            ScheduleContent.Slate -> {
                jsonOf("startMs" to jsonMs(startMs), "endMs" to jsonMs(endMs), "slate" to json(true))
            }

            ScheduleContent.Break -> {
                jsonOf("startMs" to jsonMs(startMs), "endMs" to jsonMs(endMs), "break" to json(true))
            }
        }
}
