// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import tv.stationbreak.core.domain.Missing
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin

/**
 * Everything the settings file holds: the [record] and, once chosen, the [libraries] picked on that server, with the
 * rest of the stored selection (spec 26 Part 4). [adding], [gone] and [missing] belong to a selection, so they exist
 * only with [libraries]. [seq], [listings] and [resetSeq] outlive it: a clear or another server keeps them, so a seq is
 * never reused.
 */
internal data class StoredSettings(
    val record: ServerRecord,
    val libraries: Set<ItemId>? = null,
    val seq: Long = 0,
    val adding: Map<ItemId, Long> = emptyMap(),
    val gone: Boolean = false,
    val missing: Map<ItemId, Missing> = emptyMap(),
    val listings: Long = 0,
    val resetSeq: Long = 0,
)

/**
 * The stored text of the one [ServerRecord]: `key=value` lines, the version first. The address and the pin are in the
 * same text so they are written in one atomic replace and can never disagree. Nothing here is a secret, and a reader
 * that finds anything it did not write gets null rather than a guess. A key it does not know is ignored, so a later
 * version can add one. The library selection is one more line (`libraries=` and comma-separated item IDs, which are
 * letters, numbers, `-` and `_` only), so it is replaced in the same write as the address; a file written before it
 * existed simply has none.
 *
 * Spec 26 Part 4 keeps `version=1` and only adds keys, each left out when it is empty or zero:
 * `seq=`, `adding=id:stamp,...`, `gone=1`, `missing=id:sinceMs:sincePass,...`, `listings=` and `selectionResetSeq=`.
 * A value this reader can't parse drops only its own key, never the record or `libraries`. One exception: a missing or
 * malformed `seq` is floored at the highest stamp in `adding` and `selectionResetSeq` (the reset seq alone when there
 * are no libraries), because a seq must never go back below one already used.
 *
 * Downgrade: an older app ignores these keys, and its next write of the file drops them. After a downgrade and an
 * upgrade the seq starts again at 0, nothing is being added, nothing is gone or missing, and no reset seq is known.
 * Every new seq is allocated above the lineup record's latest seq and above Room's highest add stamp, and the resume
 * raises a stored seq that is behind (spec 26 Part 4 contract, §2), so no seq still in use is taken again. The edge
 * that remains: a downgrade with no repair in the lineup record and no stamp in Room, then repairs written by another
 * path before a resume raises the seq; until that resume, those repairs are judged against a seq of 0.
 */
internal object ServerRecordCodec {
    private const val VERSION = "1"
    private const val GONE = "1"

    /**
     * [value] if it can sit in a `key=value` line without forging another: no line break. Defence in depth, since the
     * address and the item IDs refuse these already. An `=` in a value is harmless: a line is split on its first one.
     */
    fun checked(value: String): String {
        require(value.none { it == '\n' || it == '\r' }) { "Not storable" }
        return value
    }

    fun encode(record: ServerRecord): String = encode(StoredSettings(record))

    fun encode(stored: StoredSettings): String =
        buildList {
            val record = stored.record
            add("version=$VERSION")
            add("scheme=${checked(record.address.scheme.text)}")
            add("host=${checked(record.address.host)}")
            add("port=${record.address.port}")
            add("subpath=${checked(record.address.subpath)}")
            add("viaFallback=${record.address.viaFallback}")
            record.pin?.let { add("pin=${it.hex}") }
            stored.libraries?.let { ids -> add("libraries=${ids.joinToString(",") { checked(it.value) }}") }
            addAll(selectionLines(stored))
        }.joinToString("\n")

    private fun selectionLines(stored: StoredSettings): List<String> =
        buildList {
            if (stored.seq != 0L) add("seq=${stored.seq}")
            if (stored.adding.isNotEmpty()) {
                val text = stored.adding.entries.joinToString(",") { (id, stamp) -> "${checked(id.value)}:$stamp" }
                add("adding=$text")
            }
            if (stored.gone) add("gone=$GONE")
            if (stored.missing.isNotEmpty()) {
                val text =
                    stored.missing.entries.joinToString(",") { (id, miss) ->
                        "${checked(id.value)}:${miss.sinceMs}:${miss.sincePass}"
                    }
                add("missing=$text")
            }
            if (stored.listings != 0L) add("listings=${stored.listings}")
            if (stored.resetSeq != 0L) add("selectionResetSeq=${stored.resetSeq}")
        }

    fun decode(text: String): ServerRecord? = decodeStored(text)?.record

    fun decodeStored(text: String): StoredSettings? {
        val values =
            text
                .lines()
                .filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        val record = (if (values["version"] == VERSION) recordOf(values) else null) ?: return null
        // A selection that can't be read is as good as none: the picker asks again. The record is still good.
        val libraries = values["libraries"]?.let(::librariesOf)
        // A malformed reset seq reads as 0: a later build then hides no pre-setup repairs by it. Accepted, because
        // setup empties a readable lineup record anyway (spec 26 Part 4, D17), so it only matters for a record this
        // build could not read, after a corrupted settings file.
        val resetSeq = values["selectionResetSeq"]?.let(::countOf) ?: 0
        val counters =
            StoredSettings(
                record = record,
                listings = values["listings"]?.let(::countOf) ?: 0,
                resetSeq = resetSeq,
            )
        // Adding, gone and the misses belong to a selection: without its libraries they are not one.
        val read =
            if (libraries == null) {
                counters
            } else {
                counters.copy(
                    libraries = libraries,
                    adding = values["adding"]?.let { entriesOf(it, parts = 2) { (stamp) -> stamp } }.orEmpty(),
                    gone = values["gone"] == GONE,
                    missing =
                        values["missing"]
                            ?.let { entriesOf(it, parts = 3) { (ms, pass) -> Missing(ms, pass) } }
                            .orEmpty(),
                )
            }
        // A seq that is missing or can't be read is floored at the highest seq the file still proves was used (the
        // stamps and the reset seq), never 0, so a seq is not taken twice.
        val seq = values["seq"]?.let(::countOf) ?: maxOf(resetSeq, read.adding.values.maxOrNull() ?: 0)
        return read.copy(seq = seq)
    }

    private fun librariesOf(text: String): Set<ItemId>? =
        try {
            text
                .split(',')
                .map(::ItemId)
                .toSet()
                .takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun countOf(text: String): Long? = text.toLongOrNull()?.takeIf { it >= 0 }

    // Every entry or none: one entry that can't be read drops the whole key, never a guess at the others.
    private fun <V> entriesOf(
        text: String,
        parts: Int,
        value: (List<Long>) -> V,
    ): Map<ItemId, V>? =
        try {
            text
                .split(',')
                .associate { entry ->
                    val fields = entry.split(':')
                    require(fields.size == parts)
                    val numbers = fields.drop(1).map { requireNotNull(countOf(it)) }
                    ItemId(fields[0]) to value(numbers)
                }.takeIf { it.isNotEmpty() }
        } catch (_: IllegalArgumentException) {
            null
        }

    // ServerAddress and ServerRecord refuse what they should not hold (a bad host or port, a pin over http).
    private fun recordOf(values: Map<String, String>): ServerRecord? =
        try {
            val scheme = Scheme.entries.first { it.text == values["scheme"] }
            val address =
                ServerAddress(
                    scheme = scheme,
                    host = values.getValue("host"),
                    port = values.getValue("port").toInt(),
                    subpath = values["subpath"].orEmpty(),
                    viaFallback = values["viaFallback"] == "true",
                )
            val pin = values["pin"]?.let { SpkiPin.fromHex(it) ?: return null }
            ServerRecord(address, pin)
        } catch (_: NoSuchElementException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
