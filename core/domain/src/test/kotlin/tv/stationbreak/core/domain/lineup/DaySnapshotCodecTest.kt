// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.model.schedule.TitleWeights
import tv.stationbreak.core.rules.Source
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
    private val lineup = CuratedLibrary.lineup()
    private val scheduled = days.scheduled(lineup, noon, null)
    private val key =
        persisted.keyOf(ACCOUNT, LIBRARIES, 1L, lineup.utcOffsetMinutes, days.inputsOf(lineup, null), 1, scheduled)

    private fun inKeyOrder(built: List<ChannelDay>) = built.sortedBy { it.channel.id.value }

    @Test
    fun weightsRoundTrip() {
        // Today, and the day after the boundary (another window of the capped series).
        for (atMs in listOf(noon, noon + CuratedLibrary.DAY_MS)) {
            val at = days.scheduled(lineup, atMs, null)
            val keyAt = persisted.keyOf(ACCOUNT, LIBRARIES, 1L, 0, days.inputsOf(lineup, null), 1, at)
            val built = inKeyOrder(days.curatedDays(lineup, atMs))
            assertTrue(built.any { !it.weights.isEmpty } && built.any { it.weights.isEmpty })

            val file = DaySnapshotCodec.encode(keyAt, built)
            val header = DaySnapshotCodec.header(file) as DaySnapshotCodec.Header.Read
            val body = DaySnapshotCodec.body(file, header, built.size)

            assertEquals(built.map { it.weights.entries }, body.map { it.weights.entries })
            assertEquals(built.map { it.pool }, body.map { it.pool })
        }
    }

    // A file the version-5 code wrote (checked in): its header has no curation digest and its body no titles or
    // weights. It misses as Format, before its key or body is read.
    @Test
    fun aRealVersionFiveFileMissesWithFormat() {
        val file = checkNotNull(javaClass.getResourceAsStream("/days/days-v5.bin")).readBytes()
        val text = file.decodeToString()
        assertTrue(text.startsWith("{\"format\":5,"))
        assertTrue("\"curation\"" !in text && "\"titles\"" !in text && "\"weights\"" !in text)
        val old =
            lineupOf(
                starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
                leftovers("odds-and-ends", 199, null),
            )
        val at = days.scheduled(old, noon, null)
        val keyAt = persisted.keyOf(ACCOUNT, LIBRARIES, 1L, 0, days.inputsOf(old, null), 1, at)
        store.files[ACCOUNT to PersistedDays.nameOf(keyAt)] = file

        assertNull(runBlocking { persisted.load(ACCOUNT, keyAt, at, days::restore) })
        assertEquals(DaySnapshotEvent.Miss(DaySnapshotMiss.Format), events.last())
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

    // A weight for a title the day does not air (the comedy channel's first title, written on the western channel's
    // day) is refused, as a film map entry outside the day's pool is.
    @Test
    fun aWeightForATitleTheDayDoesNotAirIsAFault() {
        val built = inKeyOrder(days.curatedDays(lineup, noon))
        val text = DaySnapshotCodec.encode(key, built).decodeToString()
        val first = text.indexOf("\"weights\":[[")
        val second = text.indexOf("\"weights\":[[", first + 1)
        assertTrue(first > 0 && second > first, "two days have weights")
        val laughsRow = text.substring(first + "\"weights\":[[".length).substringBefore(',')
        val westernsPair = text.substring(second + "\"weights\":[".length).substringBefore(']') + "]"
        val broken = text.substring(0, second) + text.substring(second).replaceFirst(westernsPair, "[$laughsRow,2]")
        val file = withChecksum(broken)
        val header = DaySnapshotCodec.header(file) as DaySnapshotCodec.Header.Read

        assertFailsWith<Exception> { DaySnapshotCodec.body(file, header, built.size) }
        // The control: the file as written reads.
        val good = withChecksum(text)
        DaySnapshotCodec.body(good, DaySnapshotCodec.header(good) as DaySnapshotCodec.Header.Read, built.size)
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
