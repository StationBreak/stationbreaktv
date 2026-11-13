// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.FilmCollections
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.TitleWeights
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.BitSet
import java.util.zip.CRC32
import kotlin.io.encoding.Base64

/**
 * Everything a set of built days depends on (#480), as the header of its file. A file is used only when its key is
 * equal to the key of the build it would replace; [PersistedDays.keyOf] makes one, and its comment says why each part
 * is there. Numbers, IDs and a hash only: no name or title.
 */
@Serializable
internal data class DayKey(
    val format: Int,
    val app: String,
    val holds: List<String>,
    val server: String,
    val user: String,
    val libraries: List<String>,
    val generation: Long,
    val catalog: Int,
    val utcOffsetMinutes: Int,
    val inputs: String,
    val curation: String,
    val cutoffs: List<Long>,
    val channels: List<String>,
    val days: List<Long>,
) {
    /** The first part of this key that [other] does not share, in [DaySnapshotMiss]'s order; null when equal. */
    fun firstDifference(other: DayKey): DaySnapshotMiss? =
        listOf(
            DaySnapshotMiss.Format to (format == other.format),
            DaySnapshotMiss.App to (app == other.app),
            DaySnapshotMiss.Holds to (holds == other.holds),
            DaySnapshotMiss.Account to (server == other.server && user == other.user),
            DaySnapshotMiss.Libraries to (libraries == other.libraries),
            DaySnapshotMiss.Generation to (generation == other.generation),
            DaySnapshotMiss.Catalog to (catalog == other.catalog),
            DaySnapshotMiss.Offset to (utcOffsetMinutes == other.utcOffsetMinutes),
            DaySnapshotMiss.Lineup to (inputs == other.inputs && curation == other.curation),
            DaySnapshotMiss.Cutoffs to (cutoffs == other.cutoffs),
            DaySnapshotMiss.Channels to (channels == other.channels && days == other.days),
        ).firstOrNull { (_, same) -> !same }?.first
}

/**
 * The file of one set of built days: a header line (compact JSON: the format, a CRC-32 and the [DayKey]), a newline,
 * and the body (compact JSON). The checksum covers the key (as this codec writes it) and the body's bytes, and is
 * checked before the key is compared or the body decoded, so a flipped digit anywhere (a generation, a runtime, a
 * date, a bit of a pool) is an unreadable file, never another key's match or a different schedule. A file for other
 * inputs is never decoded.
 *
 * What guards the code is the key's `app` part (the install, see [PersistedDays]): no build of the app reads another's
 * files. [VERSION] and the holds' versions in the key change only with the code, so they never decide alone; they are
 * there so that a miss says which of them moved.
 *
 * The body holds one table of the distinct [ScheduleItem]s of all the pools, in canonical-ID order, and per scheduled
 * channel, in the key's order (by channel ID), the channel's ID, its day pool (`EpochPool.build`'s items, in order)
 * and why the day is empty if it is. A day is matched to its channel by that ID, never by position alone: [encode]
 * refuses days in another order than the key's, and a reader checks each ID against the channel it expects. A
 * day pool is in canonical-ID order, so it is written as the set of table rows it holds (a bitset, Base64): a few
 * kilobytes per channel however large the pool. A pool in any other order is written as its row numbers instead, so
 * the order is kept whatever it is. An item's fields are all kept, since the layout reads them all; a series ID is an
 * index into a table of them. Nothing else of a day is kept: the channel, its name, settings, epoch and ceiling come
 * from the lineup, which the key pins. A day that has a film-to-collection map also lists it, as pairs of an item row
 * and a row of the body's one table of collection IDs; a reader checks that each film is a movie of that day's pool.
 * A curated day that has weights lists them, as pairs of a row of the body's one table of title IDs and the weight
 * (1 to 3); a reader checks each title is listed once. The keeps are never written: the day pool is already windowed,
 * and a restore applies no window ([LineupDays.restore]).
 *
 * **Any** change to what is written still bumps [VERSION]. A file of another format than [VERSION] or [OLDEST_READ]
 * (older or newer, so one a newer build wrote) is never decoded: its header says so, and it is a miss reported as
 * `Format`, which the caller answers with a build as usual. A file of [OLDEST_READ] is read as it is, under its own
 * key (its `format` apart), and the next save writes the current format.
 *
 * The stream reads and writes are kotlinx.serialization's experimental API. They keep a file of a few megabytes from
 * ever also being one string in memory (twice its size on Android), which matters on a 1.5 GB TV; the format is
 * versioned, so a change to the API can only fail the build, never misread a file.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object DaySnapshotCodec {
    /**
     * 8 (#757): a day may hold its home set (`homes`: rows of the body's table of title IDs, the titles whose home is
     * the channel; absent when it is not known, an empty list when the channel is home to none). A file of 7 is still
     * read: it has no home sets, so its days come back with an unknown one ([ChannelDay.homed]). 7 (spec 26): a day
     * may be empty for a new reason, `LibraryNotChosen`. 6 (spec 23): a curated day may hold its
     * weights (`weights`, with one table of title IDs in the body), the key holds the digest of the days' curations
     * (`curation`), and the curated pools, keeps and weights are live, so a file of 5 is never read. 5: a day may
     * hold its film-to-collection map (`films`, with one table of collection IDs in the body). 4: the key holds the
     * digest of the day inputs (`inputs`), its channels and the body's days are in ID order, and each day names its
     * channel.
     */
    const val VERSION = 8

    /**
     * The oldest format still read: its days are the same but for the home sets they do not hold. Its body schema must
     * be a strict subset of the current one (a reader of the current schema reads it unchanged), so a change that
     * breaks that must also raise this number past it, and it is always the one before [VERSION].
     *
     * Note that the key's `app` part changes with every install or upgrade (see [PersistedDays]), so no file of another
     * build is ever read, and this read matters only when the same install wrote a file of 7 (tests, or an upgrade
     * that keeps the install time). It must not be relied on in the field.
     */
    const val OLDEST_READ = 7

    init {
        check(OLDEST_READ == VERSION - 1) { "Only the previous format is read" }
    }

    /** The longest header line read: a key is a few kilobytes even for a few hundred channels. */
    private const val MAX_HEADER = 256 * 1024
    private const val NEWLINE = '\n'.code

    /** Where the encoding of a body starts: a 50-channel day is about 1.7 MB. */
    private const val INITIAL_BYTES = 256 * 1024

    /** Room for the header line besides the body. */
    private const val INITIAL_HEADER = 16 * 1024

    private val json = Json

    /** The file of [days], built under [key]: one day per channel of the key, in the key's order, or it throws. */
    fun encode(
        key: DayKey,
        days: List<ChannelDay>,
    ): ByteArray {
        require(days.map { it.channel.id.value } == key.channels) { "The days are in the key's order" }
        val body = ByteArrayOutputStream(INITIAL_BYTES)
        json.encodeToStream(BodyV2.serializer(), bodyOf(days), body)
        val out = ByteArrayOutputStream(body.size() + INITIAL_HEADER)
        val crc = crcOf(keyBytes(key), body.toByteArray(), 0)
        json.encodeToStream(HeaderV2.serializer(), HeaderV2(VERSION, crc, key), out)
        out.write(NEWLINE)
        body.writeTo(out)
        return out.toByteArray()
    }

    /** What the header of a file says, and where its body starts. */
    sealed interface Header {
        class Read(
            val key: DayKey,
            val bodyAt: Int,
        ) : Header

        /** Written by another version of this format. */
        data object OtherFormat : Header
    }

    /**
     * Reads the header line of [file] and checks the checksum over its key and the body. Throws for a header that
     * cannot be read (torn, corrupt or too long) and for a file that does not match its checksum.
     */
    fun header(file: ByteArray): Header {
        val end = headerEnd(file)
        val root = json.parseToJsonElement(file.decodeToString(0, end, throwOnInvalidSequence = true))
        val format = ((root as? JsonObject)?.get("format") as? JsonPrimitive)?.content
        if (format != VERSION.toString() && format != OLDEST_READ.toString()) return Header.OtherFormat
        val header = json.decodeFromJsonElement(HeaderV2.serializer(), root)
        val crc = crcOf(keyBytes(header.key), file, end + 1)
        return if (crc == header.crc) Header.Read(header.key, end + 1) else throw IOException("Fails its checksum")
    }

    // Where the header line ends (its newline). Throws for a file with no newline within MAX_HEADER bytes.
    private fun headerEnd(file: ByteArray): Int {
        var end = 0
        while (end < file.size && file[end] != NEWLINE.toByte() && end < MAX_HEADER) end++
        if (end >= file.size || file[end] != NEWLINE.toByte()) throw IOException("No end to the header")
        return end
    }

    /**
     * One day of a file: the ID of the channel it is for, its day pool, its film-to-collection map, why it is empty, if
     * it is, its curated weights and its home set (null when the file's format did not keep one).
     */
    class StoredDay(
        val channel: String,
        val pool: List<ScheduleItem>,
        val films: FilmCollections,
        val hidden: HiddenReason?,
        val weights: TitleWeights,
        val homed: Set<CanonicalId>?,
    )

    /**
     * Reads the body of [file] after [header] (which checked it): each channel's day, in the key's order. Throws for a
     * body that cannot be read, or that does not hold [count] days.
     */
    fun body(
        file: ByteArray,
        header: Header.Read,
        count: Int,
    ): List<StoredDay> {
        val at = header.bodyAt
        val body = json.decodeFromStream(BodyV2.serializer(), ByteArrayInputStream(file, at, file.size - at))
        if (body.days.size != count) throw IOException("The body holds another number of days")
        val series = body.series.map(::idOf)
        // An item is made once, however many pools hold it.
        val items = body.items.map { it.item(series) }
        val collections = body.collections.map(::idOf)
        val titles = body.titles.map(::idOf)
        return body.days.map { day ->
            val pool = poolOf(day, items)
            val films = filmsOf(day, items, pool, collections)
            StoredDay(day.channel, pool, films, day.hidden, weightsOf(day, titles, pool), homesOf(day, titles, pool))
        }
    }

    // The map of a day: every entry is a movie of the day's own pool, once, under a collection of the table. Anything
    // else is a fault, so a file whose checksum is right still never schedules a film the channel does not hold.
    private fun filmsOf(
        day: DayV2,
        items: List<ScheduleItem>,
        pool: List<ScheduleItem>,
        collections: List<CanonicalId>,
    ): FilmCollections {
        val pairs = day.films ?: return FilmCollections.of(emptyMap())
        val movies = pool.filter { it.kind == ScheduleItemKind.Movie }.mapTo(HashSet()) { it.id }
        val map = HashMap<CanonicalId, CanonicalId>(pairs.size * 2)
        for (pair in pairs) {
            val film = items[rowIn(pair.getOrElse(0) { -1 }, items.size)]
            val collection = collections[rowIn(pair.getOrElse(1) { -1 }, collections.size)]
            // A film once, a movie, and of this day's pool; an entry that is longer than a pair is not one.
            val sound = pair.size == 2 && film.kind == ScheduleItemKind.Movie && film.id in movies
            if (!sound || map.put(film.id, collection) != null) throw IOException("Not a film of the day, once")
        }
        return FilmCollections.of(map)
    }

    // The key as this codec writes it: compact JSON, its fields in declaration order.
    private fun keyBytes(key: DayKey): ByteArray = json.encodeToString(DayKey.serializer(), key).encodeToByteArray()

    // The CRC-32 of [key] and then [file] from [from] on.
    private fun crcOf(
        key: ByteArray,
        file: ByteArray,
        from: Int,
    ): Long =
        CRC32()
            .apply {
                update(key)
                update(file, from, file.size - from)
            }.value

    private fun poolOf(
        day: DayV2,
        items: List<ScheduleItem>,
    ): List<ScheduleItem> {
        val rows = day.rows
        val bits = day.bits
        return when {
            rows != null && bits == null -> rows.map { items[rowIn(it, items.size)] }
            bits != null && rows == null -> rowsOf(BitSet.valueOf(Base64.decode(bits)), items)
            else -> throw IOException("A day is written one way")
        }
    }

    private fun rowsOf(
        set: BitSet,
        items: List<ScheduleItem>,
    ): List<ScheduleItem> {
        if (set.length() > items.size) throw IOException("A day holds a row the table lacks")
        val pool = ArrayList<ScheduleItem>(set.cardinality())
        var row = set.nextSetBit(0)
        while (row >= 0) {
            pool += items[row]
            row = set.nextSetBit(row + 1)
        }
        return pool
    }

    private fun bodyOf(days: List<ChannelDay>): BodyV2 {
        val collections = LinkedHashMap<CanonicalId, Int>()
        val titles = LinkedHashMap<CanonicalId, Int>()
        // The distinct items in canonical-ID order (an ID held at two cut-offs in two versions keeps both).
        val distinct = LinkedHashSet<ScheduleItem>()
        days.forEach { distinct += it.pool }
        val table = distinct.sortedBy { it.id.value }
        val rowOf = HashMap<ScheduleItem, Int>(table.size * 2)
        table.forEachIndexed { row, item -> rowOf[item] = row }
        val pools =
            days.map { day ->
                val pool = day.pool
                val rows = IntArray(pool.size) { i -> rowOf.getValue(pool[i]) }
                val films = filmRows(day, rowOf, collections)
                val weights = weightRows(day, titles)
                val homes = homeRows(day, titles)
                val parts = DayParts(films, weights, homes)
                dayOfRows(day, rows, table.size, parts)
            }
        val series = LinkedHashMap<CanonicalId, Int>()
        val rows =
            table.map { item ->
                ItemV2(
                    id = item.id.value,
                    kind = kindCode(item.kind),
                    runtimeMs = item.runtimeMs,
                    createdAtMs = item.createdAtMs,
                    deletedAtMs = item.deletedAtMs,
                    premiereDate = item.premiereDate,
                    productionYear = item.productionYear,
                    series = item.seriesId?.let { series.getOrPut(it) { series.size } },
                    season = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                )
            }
        return BodyV2(
            series.keys.map { it.value },
            rows,
            pools,
            collections.keys.map { it.value },
            titles.keys.map { it.value },
        )
    }

    // The day's map as [item row, collection row] pairs, ascending by film ID; null when the day has none.
    private fun filmRows(
        day: ChannelDay,
        rowOf: Map<ScheduleItem, Int>,
        collections: MutableMap<CanonicalId, Int>,
    ): List<List<Int>>? {
        if (day.films.isEmpty) return null
        val pool = day.pool.associateBy { it.id }
        return day.films.entries.map { (film, collection) ->
            val item = requireNotNull(pool[film]) { "A film of the map is in the day's pool" }
            listOf(rowOf.getValue(item), collections.getOrPut(collection) { collections.size })
        }
    }
}

// The weights of a day: each a title of the table and of the day's own pool, once, at a weight of 1 to 3. Anything
// else is a fault, so a file whose checksum is right still never weights a title the channel does not air.
private fun weightsOf(
    day: DayV2,
    titles: List<CanonicalId>,
    pool: List<ScheduleItem>,
): TitleWeights {
    val pairs = day.weights ?: return TitleWeights.NONE
    val aired = HashSet<CanonicalId>(pool.size * 2)
    for (item in pool) aired.add(item.groupId)
    val map = HashMap<CanonicalId, Int>(pairs.size * 2)
    for (pair in pairs) {
        if (pair.size != 2) throw IOException("A weight is a pair")
        val title = titles[rowIn(pair[0], titles.size)]
        val weight = pair[1]
        val sound = weight in TitleWeights.MIN_WEIGHT..TitleWeights.MAX_WEIGHT && title in aired
        if (!sound || map.put(title, weight) != null) throw IOException("A title's weight, once")
    }
    return TitleWeights.of(map)
}

// The day's weights as [title row, weight] pairs, ascending by title ID; null when the day has none.
private fun weightRows(
    day: ChannelDay,
    titles: MutableMap<CanonicalId, Int>,
): List<List<Int>>? {
    if (day.weights.isEmpty) return null
    return day.weights.entries.map { (title, weight) -> listOf(titles.getOrPut(title) { titles.size }, weight) }
}

// The day's home set: each a title of the table and of the day's own pool, once. Anything else is a fault. Null when
// the file did not keep one (a format 7 file): unknown, which is not the same as empty.
private fun homesOf(
    day: DayV2,
    titles: List<CanonicalId>,
    pool: List<ScheduleItem>,
): Set<CanonicalId>? {
    val rows = day.homes
    val homed = HashSet<CanonicalId>((rows?.size ?: 0) * 2)
    if (!rows.isNullOrEmpty()) {
        val aired = HashSet<CanonicalId>(pool.size * 2)
        for (item in pool) aired.add(item.groupId)
        for (row in rows) {
            val title = titles[rowIn(row, titles.size)]
            if (title !in aired || !homed.add(title)) throw IOException("A home, once, of a title the day airs")
        }
    }
    return if (rows == null) null else homed
}

// The day's home set as title rows, ascending by title ID; null when it is not known, [] when there are none.
private fun homeRows(
    day: ChannelDay,
    titles: MutableMap<CanonicalId, Int>,
): List<Int>? = day.homed?.sortedBy { it.value }?.map { titles.getOrPut(it) { titles.size } }

// What a day keeps besides its pool and its reason: the film map, the weights and the home set, as written.
private class DayParts(
    val films: List<List<Int>>?,
    val weights: List<List<Int>>?,
    val homes: List<Int>?,
) {
    fun day(
        day: ChannelDay,
        bits: String? = null,
        rows: IntArray? = null,
    ) = DayV2(day.channel.id.value, bits, rows, day.hidden, films, weights, homes)
}

// A day pool in canonical-ID order: the set of table rows it holds, as a bitset. Any other order: its row numbers.
private fun dayOfRows(
    day: ChannelDay,
    rows: IntArray,
    tableSize: Int,
    parts: DayParts,
): DayV2 {
    if (!isAscending(rows)) return parts.day(day, rows = rows)
    val set = BitSet(tableSize)
    rows.forEach(set::set)
    return parts.day(day, bits = Base64.encode(set.toByteArray()))
}

/** The header line: the format first (read before anything else), the body's CRC-32, and the key. */
@Serializable
private class HeaderV2(
    val format: Int,
    val crc: Long,
    val key: DayKey,
)

@Serializable
private class BodyV2(
    val series: List<String>,
    val items: List<ItemV2>,
    val days: List<DayV2>,
    val collections: List<String> = emptyList(),
    val titles: List<String> = emptyList(),
)

/**
 * One distinct [ScheduleItem]. Short names and no nulls written: there are thousands of these. Its kind is a code
 * ([kindCode], [kindOf]), mapped one for one both ways, so a new kind fails the build here rather than being misread.
 */
@Suppress("LongParameterList")
@Serializable
private class ItemV2(
    @SerialName("i") val id: String,
    @SerialName("k") val kind: Int = MOVIE,
    @SerialName("r") val runtimeMs: Long,
    @SerialName("c") val createdAtMs: Long,
    @SerialName("d") val deletedAtMs: Long? = null,
    @SerialName("p") val premiereDate: String? = null,
    @SerialName("y") val productionYear: Int? = null,
    @SerialName("s") val series: Int? = null,
    @SerialName("sn") val season: Int? = null,
    @SerialName("en") val episodeNumber: Int? = null,
) {
    fun item(seriesIds: List<CanonicalId>) =
        ScheduleItem(
            id = idOf(id),
            kind = kindOf(kind),
            runtimeMs = runtimeMs,
            createdAtMs = createdAtMs,
            deletedAtMs = deletedAtMs,
            premiereDate = premiereDate,
            productionYear = productionYear,
            seriesId = series?.let { seriesIds[rowIn(it, seriesIds.size)] },
            seasonNumber = season,
            episodeNumber = episodeNumber,
        )
}

/**
 * One channel's day: the channel's ID, its pool as a bitset of table rows (Base64) or as row numbers, its empty
 * reason, the film-to-collection map as `[item row, collection row]` pairs and the weights as `[title row, weight]`
 * pairs (each absent when the day has none) and, from format 8, the home set as title rows (absent when it is not
 * known; an empty list is a day with no homes).
 */
@Suppress("LongParameterList") // One field for each thing a day keeps.
@Serializable
private class DayV2(
    @SerialName("c") val channel: String,
    val bits: String? = null,
    val rows: IntArray? = null,
    val hidden: HiddenReason? = null,
    val films: List<List<Int>>? = null,
    val weights: List<List<Int>>? = null,
    val homes: List<Int>? = null,
)

private const val MOVIE = 0
private const val EPISODE = 1

private fun kindCode(kind: ScheduleItemKind): Int =
    when (kind) {
        ScheduleItemKind.Movie -> MOVIE
        ScheduleItemKind.Episode -> EPISODE
    }

private fun kindOf(code: Int): ScheduleItemKind =
    when (code) {
        MOVIE -> ScheduleItemKind.Movie
        EPISODE -> ScheduleItemKind.Episode
        else -> throw IOException("Not an item kind")
    }

// A row of a table of [size] rows, or a fault: never a negative or past-the-end index.
private fun rowIn(
    row: Int,
    size: Int,
): Int = if (row in 0 until size) row else throw IOException("A row the table lacks")

private fun isAscending(rows: IntArray): Boolean {
    for (i in 1 until rows.size) {
        if (rows[i - 1] >= rows[i]) return false
    }
    return true
}

// Only an ID this app wrote: already canonical, so nothing is repaired.
private fun idOf(text: String): CanonicalId {
    val id = CanonicalId.parseCanonicalOrNull(text)
    return id ?: throw IOException("Not a canonical ID")
}
