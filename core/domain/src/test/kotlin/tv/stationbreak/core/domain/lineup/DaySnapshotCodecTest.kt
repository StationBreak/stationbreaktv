// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.schedule.TitleWeights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The days file of format 6 (spec 23): a curated day's weights go to disk and come back; the keeps never do. */
class DaySnapshotCodecTest {
    private val days = LineupDays(TestCatalog)
    private val noon = NOON.toEpochMilliseconds()
    private val store = MemoryDayStore()
    private val events = mutableListOf<DaySnapshotEvent>()
    private val persisted = PersistedDays(store, "app-1", { events += it }, PersistedDays.HOLDS)
    private val lineup = Curated.lineup()
    private val scheduled = days.scheduled(lineup, noon, null)
    private val key =
        persisted.keyOf(ACCOUNT, LIBRARIES, 1L, lineup.utcOffsetMinutes, days.inputsOf(lineup, null), 1, scheduled)

    private fun inKeyOrder(built: List<ChannelDay>) = built.sortedBy { it.channel.id.value }

    @Test
    fun weightsRoundTrip() {
        val built = inKeyOrder(days.curatedDays(lineup, noon))
        assertTrue(built.any { !it.weights.isEmpty } && built.any { it.weights.isEmpty })

        val file = DaySnapshotCodec.encode(key, built)
        val header = DaySnapshotCodec.header(file) as DaySnapshotCodec.Header.Read
        val body = DaySnapshotCodec.body(file, header, built.size)

        assertEquals(built.map { it.weights.entries }, body.map { it.weights.entries })
        assertEquals(built.map { it.pool }, body.map { it.pool })
    }

    @Test
    fun aWeightOutsideItsRangeOrForATitleTwiceIsAFault() {
        val built = inKeyOrder(days.curatedDays(lineup, noon))
        val text = DaySnapshotCodec.encode(key, built).decodeToString()
        val at = text.indexOf("\"weights\":[[")
        assertTrue(at > 0, "the weights are written as [title row, weight] pairs")
        val pair = text.substring(at + "\"weights\":[".length, text.indexOf(']', at) + 1)
        val (row, _) = pair.removeSurrounding("[", "]").split(',')

        for (bad in listOf("[$row,4]", "[$row,0]", "[-1,2]", "[$row,2],[$row,3]")) {
            val broken = text.replaceFirst("\"weights\":[$pair", "\"weights\":[$bad")
            // The checksum is put right, so only the body's own checks can refuse it.
            val file = withChecksum(broken)
            val header = DaySnapshotCodec.header(file) as DaySnapshotCodec.Header.Read
            assertFailsWith<Exception>(bad) { DaySnapshotCodec.body(file, header, built.size) }
        }
    }

    @Test
    fun aVersionFiveFileMissesWithFormat() {
        assertEquals(6, DaySnapshotCodec.VERSION)
        val built = days.curatedDays(lineup, noon)
        assertTrue(runBlocking { persisted.save(ACCOUNT, key, built) })
        val (where, bytes) = store.only()
        val text = bytes.decodeToString()
        store.files[where] = text.replaceFirst("\"format\":6", "\"format\":5").encodeToByteArray()

        assertNull(runBlocking { persisted.load(ACCOUNT, key, scheduled, days::restore) })
        assertEquals(DaySnapshotEvent.Miss(DaySnapshotMiss.Format), events.last())
        store.files[where] = bytes
        assertNotNull(runBlocking { persisted.load(ACCOUNT, key, scheduled, days::restore) })
    }

    @Test
    fun theCurationVersionIsAHold() {
        assertTrue(tv.stationbreak.core.engine.lineup.curation.Curation.VERSION_NAME in PersistedDays.HOLDS)
        assertEquals(TitleWeights.NONE, days.restore(scheduled.first(), emptyList(), NO_FILMS, null).weights)
    }

    // [text] with its header's CRC-32 worked out again over its key and body, as the codec does.
    private fun withChecksum(text: String): ByteArray {
        val newline = text.indexOf('\n')
        val header =
            kotlinx.serialization.json.Json
                .parseToJsonElement(text.substring(0, newline)) as kotlinx.serialization.json.JsonObject
        val keyText = header.getValue("key").toString()
        val crc =
            java.util.zip
                .CRC32()
                .apply {
                    update(keyText.encodeToByteArray())
                    update(text.substring(newline + 1).encodeToByteArray())
                }.value
        val fixed = text.substring(0, newline).replace(Regex("\"crc\":\\d+"), "\"crc\":$crc")
        return (fixed + text.substring(newline)).encodeToByteArray()
    }
}
