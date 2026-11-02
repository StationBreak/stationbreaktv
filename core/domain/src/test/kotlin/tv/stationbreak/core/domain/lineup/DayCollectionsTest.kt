// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.OrderingCaps
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.scheduler.EpochPool
import tv.stationbreak.core.scheduler.buildDay
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A day carries the film-to-collection map its layout was built with (F2.3), and keeps it on disk: a day read back
 * equals the day built, and a file whose map names something the channel's pool does not hold is never scheduled.
 */
class DayCollectionsTest {
    private val days = LineupDays(TestCatalog)
    private val store = MemoryDayStore()
    private val events = mutableListOf<DaySnapshotEvent>()
    private val atMs = NOON.toEpochMilliseconds()

    private val boxSet = id(900)
    private val boxSetFilms = listOf(movie(1, "Drama"), movie(2, "Drama"), movie(3, "Drama"))
    private val singles = (4..7).map { movie(it, "Comedy") }
    private val crime = movie(8, "Crime")
    private val episode =
        RuleItem(
            ScheduleItem(id(700), ScheduleItemKind.Episode, 1_800_000L, ADDED_MS, null, null, 2001, id(701), 1, 2),
            LIBRARY_ID,
            null,
            "TV-14",
            listOf("Comedy"),
            emptyList(),
        )

    private fun rotation(vararg sources: Source) =
        ChannelRules(
            2,
            "Box Sets",
            Seed(5uL),
            sources.toList(),
            null,
            null,
            Ordering(OrderingMode.Rotation),
            timing(),
            null,
        )

    private fun custom(
        number: Int,
        rules: ChannelRules,
        state: ChannelState = ChannelState.On,
    ) = channel(rules.name.orEmpty() + number, number, state, ChannelSource.Custom(rules))

    private val lineup =
        lineupOf(
            custom(600, rotation(Source.Collection(boxSet), Source.Genre("Comedy"))),
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
        )

    private fun snapshotsOf(scheduled: List<Scheduled>): Map<Long, RuleSnapshot> =
        scheduled.map { it.cutoffMs }.distinct().associateWith {
            val films = boxSetFilms.map { f -> f.item.id }.toSet()
            val members = RuleCollection(boxSet, films, emptySet(), emptySet(), emptySet())
            val all = (boxSetFilms + singles + crime + episode).sortedBy { item -> item.item.id.value }
            RuleSnapshot(it, setOf(LIBRARY_ID), all, emptyMap(), mapOf(boxSet to members), collectionsSynced = true)
        }

    private fun built(of: Lineup = lineup): List<ChannelDay> {
        val scheduled = days.scheduled(of, atMs)
        return days.days(of, scheduled, snapshotsOf(scheduled))
    }

    private fun rotationDay(all: List<ChannelDay> = built()) = all.single { it.channel.number == 600 }

    private fun persisted() = PersistedDays(store, "app", { events += it })

    private fun keyOf(
        persisted: PersistedDays,
        scheduled: List<Scheduled>,
    ) = keyOf(persisted, lineup, scheduled)

    private fun keyOf(
        persisted: PersistedDays,
        of: Lineup,
        scheduled: List<Scheduled>,
    ) = persisted.keyOf(ACCOUNT, LIBRARIES, 7L, of.utcOffsetMinutes, days.inputsOf(of), TestCatalog.version, scheduled)

    private fun save(): DayKey {
        val persisted = persisted()
        val scheduled = days.scheduled(lineup, atMs)
        val key = keyOf(persisted, scheduled)
        assertTrue(runBlocking { persisted.save(ACCOUNT, key, built()) })
        return key
    }

    private fun load(key: DayKey? = null): List<ChannelDay>? {
        val persisted = persisted()
        val scheduled = days.scheduled(lineup, atMs)
        return runBlocking { persisted.load(ACCOUNT, key ?: keyOf(persisted, scheduled), scheduled, days::restore) }
    }

    private fun lastMiss() = (events.last() as DaySnapshotEvent.Miss).reason

    @Test
    fun aRotationChannelsDayGroupsItsFilms() {
        val day = rotationDay()

        assertEquals(boxSetFilms.map { it.item.id to boxSet }, day.films.entries)
        val ungrouped = buildDay(day.settings, day.pool, day.n, FilmCollections.of(emptyMap()))
        assertNotEquals(ungrouped.blocks, day.day.blocks, "the grouping reaches the layout")
        assertEquals(buildDay(day.settings, day.pool, day.n, day.films).blocks, day.day.blocks)
    }

    @Test
    fun aStoredVersion2RotationChannelSchedulesWithItsCollectionGrouping() {
        val stored = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(LineupCodec.encode(lineup))).lineup

        val day = rotationDay(built(stored))

        assertIs<ChannelSource.Custom>(day.channel.source)
        assertEquals(rotationDay().films.entries, day.films.entries)
        assertEquals(boxSetFilms.map { it.item.id to boxSet }, day.films.entries)
        assertEquals(rotationDay().day.blocks, day.day.blocks)
    }

    @Test
    fun aV1ChannelsAndALeftoversDayHaveNoMap() {
        val all = built(lineupOf(*lineup.channels.toTypedArray(), leftovers("odds-and-ends", 199, null)))

        assertTrue(all.filter { it.channel.number != 600 }.all { it.films.isEmpty })
    }

    @Test
    fun theMapSurvivesThePersistedDay() {
        save()

        val restored = rotationDay(assertNotNull(load()))
        val fresh = rotationDay()

        assertEquals(fresh.films.entries, restored.films.entries)
        assertEquals(fresh.day.blocks, restored.day.blocks)
    }

    @Test
    fun aV4FileIsAMissAsFormat() {
        save()
        val (where, bytes) = store.only()
        val text = bytes.decodeToString()
        val asV4 = text.replaceFirst("\"format\":${DaySnapshotCodec.VERSION}", "\"format\":4")
        store.files[where] = asV4.encodeToByteArray()

        assertNull(load())
        assertEquals(DaySnapshotMiss.Format, lastMiss())
    }

    @Test
    fun aV1ChannelsDayWritesNoFilms() {
        // The lineup without the rotation channel: only v1 channels.
        val v1 = lineupOf(starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))))
        val persisted = persisted()
        val scheduled = days.scheduled(v1, atMs)
        val key = keyOf(persisted, v1, scheduled)
        assertTrue(runBlocking { persisted.save(ACCOUNT, key, days.days(v1, scheduled, snapshotsOf(scheduled))) })

        val body =
            Json
                .parseToJsonElement(
                    store
                        .only()
                        .second
                        .decodeToString()
                        .substringAfter('\n'),
                ).jsonObject

        // The body's shape is the version 4 one: these keys and no others.
        assertEquals(setOf("series", "items", "days"), body.keys)
        body.getValue("days").jsonArray.forEach { assertTrue(it.jsonObject.keys.all { k -> k in V4_DAY_KEYS }) }
    }

    @Test
    fun theDayKeyChangesWhenARuleChanges() {
        fun digest(rules: ChannelRules) = days.inputsOf(lineupOf(custom(600, rules)))
        val base = rotation(Source.Collection(boxSet), Source.Genre("Comedy"))
        val capped = base.copy(ordering = Ordering(OrderingMode.Rotation, OrderingCaps(series = 2, film = 2)))
        val filtered =
            base.copy(
                filters =
                    tv.stationbreak.core.rules
                        .Filters(null, 1990, 2010, null, null, null),
            )

        assertEquals(3, setOf(digest(base), digest(capped), digest(filtered)).size)
    }

    @Test
    fun aCorruptCollectionsTableIsAMissNotAWrongSchedule() {
        save()
        val (where, bytes) = store.only()
        val boxSetText = boxSet.value
        val text = bytes.decodeToString()
        assertTrue(boxSetText in text.substringAfter('\n'), "the table holds the collection")
        store.files[where] = text.replace(boxSetText, boxSetText.replace('0', '1')).encodeToByteArray()

        assertNull(load())
        assertEquals(DaySnapshotMiss.Unreadable, lastMiss())
    }

    // A file with the saved header and the [edit]ed body, its checksum made to match: the body is what is read.
    private fun forged(edit: (JsonObject) -> JsonObject): ByteArray {
        val text = store.only().second.decodeToString()
        val header = text.substringBefore('\n')
        val body = edit(Json.parseToJsonElement(text.substringAfter('\n')).jsonObject).toString()
        val key = header.substringAfter("\"key\":").dropLast(1)
        val crc =
            CRC32()
                .apply {
                    update(key.encodeToByteArray())
                    update(body.encodeToByteArray())
                }.value
        return "${header.replace(Regex("\"crc\":[0-9]+"), "\"crc\":$crc")}\n$body".encodeToByteArray()
    }

    private fun rowOf(
        body: JsonObject,
        item: CanonicalId,
    ) = body
        .getValue("items")
        .jsonArray
        .indexOfFirst { it.jsonObject.getValue("i") == JsonPrimitive(item.value) }
        .also { assertTrue(it >= 0, "the item is in the table") }

    // The rotation channel's day object, with its first film pair pointing at [row].
    private fun pointingAt(
        body: JsonObject,
        row: Int,
    ): JsonObject {
        val edited =
            body.getValue("days").jsonArray.map { day ->
                val films = day.jsonObject["films"]?.jsonArray ?: return@map day
                val pairs = films.toMutableList<JsonElement>()
                pairs[0] = JsonArray(listOf(JsonPrimitive(row), pairs[0].jsonArray[1]))
                JsonObject(day.jsonObject + ("films" to JsonArray(pairs)))
            }
        return JsonObject(body + ("days" to JsonArray(edited)))
    }

    @Test
    fun anEntryForAnItemOutsideTheChannelsPoolIsAMiss() {
        save()
        val (where, _) = store.only()
        // A crime film is in the table, because the other channel airs it, but not in the rotation channel's pool.
        assertTrue(rotationDay().pool.none { it.id == crime.item.id })

        val body =
            Json
                .parseToJsonElement(
                    store
                        .only()
                        .second
                        .decodeToString()
                        .substringAfter('\n'),
                ).jsonObject
        store.files[where] = forged { pointingAt(it, rowOf(body, crime.item.id)) }

        assertNull(load())
        assertIs<DaySnapshotEvent.Miss>(events.last())
    }

    @Test
    fun anEntryForAnEpisodeIsAMiss() {
        save()
        val (where, _) = store.only()
        assertTrue(rotationDay().pool.any { it.id == episode.item.id }, "the episode is in the channel's pool")

        val body =
            Json
                .parseToJsonElement(
                    store
                        .only()
                        .second
                        .decodeToString()
                        .substringAfter('\n'),
                ).jsonObject
        store.files[where] = forged { pointingAt(it, rowOf(body, episode.item.id)) }

        assertNull(load())
        assertIs<DaySnapshotEvent.Miss>(events.last())
    }

    private companion object {
        val V4_DAY_KEYS = setOf("c", "bits", "rows", "hidden")
    }
}
