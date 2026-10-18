// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Spec 10, Unit 2 (ADR-0009 amendment): a collection's name is text a person typed, so it never reaches a log line at
 * info level or above, on a good call or a failed one. The adapter's log seam carries fixed phrases and error types
 * only; this pins that for the collection calls, including an error body and an undecodable answer that hold a name.
 * It arrived green: the adapter never logged a body, and this keeps it so.
 */
class CollectionLogRedactionTest {
    private val jellyfin = FakeJellyfin()
    private val lines = CopyOnWriteArrayList<String>()
    private val name = "Private Name 7f3a"
    private val box = ItemId("box01")

    @AfterTest
    fun tearDown() = jellyfin.close()

    private val named = """{"Items":[{"Id":"b1","Name":"$name","Type":"BoxSet"}],"TotalRecordCount":1}"""

    private fun everyCall(): List<ServerResult<*>> =
        runBlocking {
            val adapter = testAdapter(log = { lines += it })
            val session = testSession(jellyfin.address)
            listOf(
                adapter.collections(session, 0, 10),
                adapter.collectionIds(session, 0, 10),
                adapter.collectionMembers(session, box, 0, 10),
            )
        }

    @Test
    fun aGoodAnswerThatNamesACollectionLogsNoName() {
        jellyfin.on("GET", "/Items") { jellyfin.json(named) }

        assertTrue(everyCall().all { it is ServerResult.Success })

        assertTrue(lines.none { name in it }, lines.toString())
    }

    @Test
    fun aFailedCallWhoseBodyNamesACollectionLogsNoName() {
        jellyfin.on("GET", "/Items") { jellyfin.json("""{"Message":"no such collection $name"}""", code = 500) }

        everyCall().forEach { assertIs<ServerResult.Failure>(it) }

        assertTrue(lines.none { name in it }, lines.toString())
    }

    @Test
    fun anAnswerThatCannotBeDecodedLogsNoName() {
        jellyfin.on("GET", "/Items") { jellyfin.json("""{"Items":[{"Id":"b1","Name":"$name","Type":"BoxSet"},""") }

        everyCall().forEach { assertIs<ServerResult.Failure>(it) }

        assertTrue(lines.none { name in it }, lines.toString())
    }

    @Test
    fun theRedactingSeamStillHandsTheSinkOnlyRedactedLines() {
        val seen = mutableListOf<String>()
        val log = AdapterLog.redacting { seen += it }
        jellyfin.on("GET", "/Items") { jellyfin.json("""{"Message":"$name"}""", code = 500) }

        runBlocking { testAdapter(log = log).collections(testSession(jellyfin.address), 0, 10) }

        assertEquals(emptyList(), seen.filter { name in it })
    }
}
