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
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
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
    val lineup: String,
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
            DaySnapshotMiss.Lineup to (lineup == other.lineup),
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
 * channel in the key's order its day pool (`EpochPool.build`'s items, in order) and why the day is empty if it is. A
 * day pool is in canonical-ID order, so it is written as the set of table rows it holds (a bitset, Base64): a few
 * kilobytes per channel however large the pool. A pool in any other order is written as its row numbers instead, so
 * the order is kept whatever it is. An item's fields are all kept, since the layout reads them all; a series ID is an
 * index into a table of them. Nothing else of a day is kept: the channel, its name, settings, epoch and ceiling come
 * from the lineup, which the key pins.
 *
 * **Any** change to what is written still bumps [VERSION], so the miss is reported as `Format`.
 *
 * The stream reads and writes are kotlinx.serialization's experimental API. They keep a file of a few megabytes from
 * ever also being one string in memory (twice its size on Android), which matters on a 1.5 GB TV; the format is
 * versioned, so a change to the API can only fail the build, never misread a file.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object DaySnapshotCodec {
    const val VERSION = 3

    /** The longest header line read: a key is a few kilobytes even for a few hundred channels. */
    private const val MAX_HEADER = 256 * 1024
    private const val NEWLINE = '\n'.code

    /** Where the encoding of a body starts: a 50-channel day is about 1.7 MB. */
    private const val INITIAL_BYTES = 256 * 1024

    /** Room for the header line besides the body. */
    private const val INITIAL_HEADER = 16 * 1024

    private val json = Json

    /** The file of [days], built under [key]. */
    fun encode(
        key: DayKey,
        days: List<ChannelDay>,
    ): ByteArray {
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
        if (format != VERSION.toString()) return Header.OtherFormat
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
     * Reads the body of [file] after [header] (which checked it): each channel's day pool and empty reason, in the
     * key's order. Throws for a body that cannot be read, or that does not hold [count] days.
     */
    fun body(
        file: ByteArray,
        header: Header.Read,
        count: Int,
    ): List<Pair<List<ScheduleItem>, HiddenReason?>> {
        val at = header.bodyAt
        val body = json.decodeFromStream(BodyV2.serializer(), ByteArrayInputStream(file, at, file.size - at))
        if (body.days.size != count) throw IOException("The body holds another number of days")
        val series = body.series.map(::idOf)
        // An item is made once, however many pools hold it.
        val items = body.items.map { it.item(series) }
        return body.days.map { day -> poolOf(day, items) to day.hidden }
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
        // The distinct items in canonical-ID order (an ID held at two cut-offs in two versions keeps both).
        val distinct = LinkedHashSet<ScheduleItem>()
        days.forEach { distinct += it.pool }
        val table = distinct.sortedBy { it.id.value }
        val rowOf = HashMap<ScheduleItem, Int>(table.size * 2)
        table.forEachIndexed { row, item -> rowOf[item] = row }
        val pools =
            days.map { day ->
                val rows = IntArray(day.pool.size) { i -> rowOf.getValue(day.pool[i]) }
                if (isAscending(rows)) {
                    val set = BitSet(table.size)
                    rows.forEach(set::set)
                    DayV2(bits = Base64.encode(set.toByteArray()), hidden = day.hidden)
                } else {
                    DayV2(rows = rows, hidden = day.hidden)
                }
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
        return BodyV2(series.keys.map { it.value }, rows, pools)
    }
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

/** One channel's day: its pool as a bitset of table rows (Base64) or as row numbers, and its empty reason. */
@Serializable
private class DayV2(
    val bits: String? = null,
    val rows: IntArray? = null,
    val hidden: HiddenReason? = null,
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
