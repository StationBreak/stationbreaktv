// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import tv.stationbreak.core.domain.lineup.HiddenReason
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.ItemId

/**
 * The `repairs` object of a version-4 lineup file (spec 26), compact because it shares the file's size cap. Per
 * channel repair and per fix an `ids` table lists each item once, and a block is the integer array
 * `[startDelta, length, idIndex, mediaStart]`: `startDelta` from the end of the block before it (for the first block
 * from `from`, a fix's from its `blockStart`), `idIndex` into `ids` or -1 for a slate.
 *
 * `lastSeq` is the highest selection seq ever recorded (`TodayRepairs.lastSeq`), written when not 0. The lost-block
 * check (`TodayRepairs.lostCheck`) is never written.
 *
 * It is strict like the rest of the file: an unknown key, a wrong type, an id that is not an id, an index outside the
 * table, a block that runs backwards, or two repairs of one channel day make [decode] throw
 * [IllegalArgumentException]. The lineup codec then loads the lineup with no record
 * (`Decoded.Loaded.repairsUnreadable`), and never sets the file aside for it.
 */
@Suppress("TooManyFunctions") // One small reader or writer per field shape, kept together.
internal object RepairsCodec {
    private const val SELECTION = "selection"
    private const val FIXES = "fixes"
    private const val LAST_SEQ = "lastSeq"

    private const val CHANNEL = "ch"
    private const val DAY = "n"
    private const val DAY_END = "end"
    private const val AT = "at"
    private const val SEQ = "seq"
    private const val LIBRARIES = "libs"
    private const val FROM = "from"
    private const val PIN_END = "pinEnd"
    private const val PIN_LIBRARY = "pinLib"
    private const val IDS = "ids"
    private const val BLOCKS = "b"
    private const val HIDDEN = "hidden"
    private const val BLOCK_START = "blockStart"
    private const val BLOCK_END = "blockEnd"
    private const val GONE = "gone"
    private const val PIECES = "p"

    private const val BLOCK_FIELDS = 4
    private const val SLATE = -1L

    private val topKeys = setOf(SELECTION, FIXES, LAST_SEQ)
    private val selectionKeys =
        setOf(CHANNEL, DAY, DAY_END, AT, SEQ, LIBRARIES, FROM, PIN_END, PIN_LIBRARY, IDS, BLOCKS, HIDDEN)
    private val fixKeys = setOf(CHANNEL, DAY, DAY_END, BLOCK_START, BLOCK_END, GONE, IDS, PIECES)

    fun encode(repairs: TodayRepairs): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        if (repairs.selection.isNotEmpty()) fields[SELECTION] = JsonArray(repairs.selection.map(::encode))
        if (repairs.fixes.isNotEmpty()) fields[FIXES] = JsonArray(repairs.fixes.map(::encode))
        if (repairs.lastSeq != 0L) fields[LAST_SEQ] = JsonPrimitive(repairs.lastSeq)
        return JsonObject(fields)
    }

    /** Throws [IllegalArgumentException] (or an [ArithmeticException] for a time that overflows) for a bad record. */
    fun decode(element: JsonElement): TodayRepairs {
        val root = element as? JsonObject ?: throw IllegalArgumentException("repairs is not an object")
        require(topKeys.containsAll(root.keys)) { "An unknown key in repairs" }
        val selection = root[SELECTION]?.let { list(it).map { entry -> repair(entry) } }.orEmpty()
        val fixes = root[FIXES]?.let { list(it).map { entry -> fix(entry) } }.orEmpty()
        val lastSeq = root[LAST_SEQ]?.let(::long) ?: 0L
        val days = selection.map { it.channel to it.n }
        require(days.size == days.toSet().size) { "Two repairs of one channel day" }
        return TodayRepairs(selection, fixes, lastSeq = lastSeq)
    }

    private fun encode(repair: ChannelRepair): JsonObject {
        val table = IdTable()
        val blocks = encodeBlocks(repair.blocks, repair.keptFromMs, table)
        val fields = LinkedHashMap<String, JsonElement>()
        fields[CHANNEL] = JsonPrimitive(repair.channel.value)
        fields[DAY] = JsonPrimitive(repair.n)
        fields[DAY_END] = JsonPrimitive(repair.dayEndMs)
        fields[AT] = JsonPrimitive(repair.atMs)
        fields[SEQ] = JsonPrimitive(repair.seq)
        fields[LIBRARIES] =
            JsonArray(
                repair.libraries
                    .map { it.value }
                    .sorted()
                    .map(::JsonPrimitive),
            )
        fields[FROM] = JsonPrimitive(repair.keptFromMs)
        fields[PIN_END] = JsonPrimitive(repair.pinEndMs)
        repair.pinLibrary?.let { fields[PIN_LIBRARY] = JsonPrimitive(it.value) }
        fields[IDS] = table.json()
        fields[BLOCKS] = blocks
        repair.tailHidden?.let { fields[HIDDEN] = JsonPrimitive(it.name) }
        return JsonObject(fields)
    }

    private fun encode(fix: LostFix): JsonObject {
        val table = IdTable()
        val pieces = encodeBlocks(fix.pieces, fix.blockStartMs, table)
        val fields = LinkedHashMap<String, JsonElement>()
        fields[CHANNEL] = JsonPrimitive(fix.channel.value)
        fields[DAY] = JsonPrimitive(fix.n)
        fields[DAY_END] = JsonPrimitive(fix.dayEndMs)
        fields[BLOCK_START] = JsonPrimitive(fix.blockStartMs)
        fields[BLOCK_END] = JsonPrimitive(fix.blockEndMs)
        fields[GONE] = JsonPrimitive(fix.gone.value)
        fields[IDS] = table.json()
        fields[PIECES] = pieces
        return JsonObject(fields)
    }

    private fun repair(element: JsonElement): ChannelRepair {
        val fields = obj(element, selectionKeys)
        val ids = ids(fields.getValue(IDS))
        val from = long(fields.getValue(FROM))
        return ChannelRepair(
            channel = channel(fields.getValue(CHANNEL)),
            n = long(fields.getValue(DAY)),
            dayEndMs = long(fields.getValue(DAY_END)),
            atMs = long(fields.getValue(AT)),
            seq = long(fields.getValue(SEQ)),
            libraries = list(fields.getValue(LIBRARIES)).map { ItemId(string(it)) }.toSet(),
            keptFromMs = from,
            pinEndMs = long(fields.getValue(PIN_END)),
            pinLibrary = fields[PIN_LIBRARY]?.let { ItemId(string(it)) },
            blocks = decodeBlocks(fields.getValue(BLOCKS), from, ids),
            tailHidden = fields[HIDDEN]?.let { name -> hiddenOf(string(name)) },
        )
    }

    // A reason this app does not know (a newer app's) reads as none: the record stays readable (spec 26, ruling 6).
    private fun hiddenOf(name: String): HiddenReason? = HiddenReason.entries.firstOrNull { it.name == name }

    private fun fix(element: JsonElement): LostFix {
        val fields = obj(element, fixKeys)
        val ids = ids(fields.getValue(IDS))
        val start = long(fields.getValue(BLOCK_START))
        return LostFix(
            channel = channel(fields.getValue(CHANNEL)),
            n = long(fields.getValue(DAY)),
            dayEndMs = long(fields.getValue(DAY_END)),
            blockStartMs = start,
            blockEndMs = long(fields.getValue(BLOCK_END)),
            gone = canonical(fields.getValue(GONE)),
            pieces = decodeBlocks(fields.getValue(PIECES), start, ids),
        )
    }

    // The items of one entry in order of first use, each once.
    private class IdTable {
        private val index = LinkedHashMap<CanonicalId, Int>()

        fun of(id: CanonicalId): Int = index.getOrPut(id) { index.size }

        fun json() = JsonArray(index.keys.map { JsonPrimitive(it.value) })
    }

    private fun encodeBlocks(
        blocks: List<KeptBlock>,
        anchor: Long,
        table: IdTable,
    ): JsonArray {
        var cursor = anchor
        val rows =
            blocks.map { block ->
                val row =
                    listOf(
                        block.startMs - cursor,
                        block.endMs - block.startMs,
                        block.itemId?.let { table.of(it).toLong() } ?: SLATE,
                        block.mediaStartMs,
                    )
                cursor = block.endMs
                JsonArray(row.map(::JsonPrimitive))
            }
        return JsonArray(rows)
    }

    private fun decodeBlocks(
        element: JsonElement,
        anchor: Long,
        ids: List<CanonicalId>,
    ): List<KeptBlock> {
        var cursor = anchor
        return list(element).map { row ->
            val numbers = list(row).map(::long)
            require(numbers.size == BLOCK_FIELDS) { "A block is four numbers" }
            val delta = numbers[0]
            val length = numbers[1]
            val index = numbers[2]
            val mediaStart = numbers[3]
            require(delta >= 0 && length > 0 && mediaStart >= 0) { "A block runs backwards" }
            require(index == SLATE || index in ids.indices.map(Int::toLong)) { "A block names an id outside the table" }
            val start = Math.addExact(cursor, delta)
            val end = Math.addExact(start, length)
            cursor = end
            KeptBlock(start, end, if (index == SLATE) null else ids[index.toInt()], mediaStart)
        }
    }

    private fun obj(
        element: JsonElement,
        allowed: Set<String>,
    ): JsonObject {
        val fields = element as? JsonObject ?: throw IllegalArgumentException("Not an object")
        require(allowed.containsAll(fields.keys)) { "An unknown key in a repair" }
        return fields
    }

    private fun list(element: JsonElement): List<JsonElement> =
        (element as? JsonArray)?.toList() ?: throw IllegalArgumentException("Not an array")

    private fun ids(element: JsonElement): List<CanonicalId> {
        val table = list(element).map(::canonical)
        require(table.size == table.toSet().size) { "An id twice in a table" }
        return table
    }

    private fun long(element: JsonElement): Long {
        val primitive = element as? JsonPrimitive
        require(primitive != null && element !is JsonNull && !primitive.isString) { "Not a number" }
        return requireNotNull(primitive.longOrNull) { "Not a whole number" }
    }

    private fun string(element: JsonElement): String {
        val primitive = element as? JsonPrimitive
        require(primitive != null && element !is JsonNull && primitive.isString) { "Not a string" }
        return primitive.content
    }

    private fun canonical(element: JsonElement): CanonicalId =
        requireNotNull(CanonicalId.parseCanonicalOrNull(string(element))) { "Not an item id" }

    private fun channel(element: JsonElement) = ChannelId(string(element))
}
