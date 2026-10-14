// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.SignInRequest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R18: the adapter's own statements go through the injected seam and carry no header, body, URL or host. */
class AdapterLogTest {
    private val jellyfin = FakeJellyfin()
    private val lines = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() = jellyfin.close()

    @Test
    fun theAdapterLogsThroughTheSeamAndNeverTheHostUrlsOrHeaders() =
        runBlocking {
            jellyfin.on("POST", "/Users/AuthenticateByName") { jellyfin.status(401) }
            val request = SignInRequest(jellyfin.address, "alice", "pw".toCharArray(), null)
            testAdapter(log = { lines += it }).signIn(request)
            assertTrue(lines.isNotEmpty())
            for (line in lines) {
                assertFalse(jellyfin.address.host in line, "host in a log line")
                assertFalse("http://" in line || "https://" in line, "URL in a log line")
                assertFalse("Authorization" in line || "MediaBrowser" in line, "header in a log line")
                assertFalse("alice" in line, "username in a log line")
            }
        }

    @Test
    fun theDefaultSeamDoesNothing() {
        AdapterLog.NONE.log("anything")
        AdapterLog.NONE.log("anything", IllegalStateException("anything"))
    }

    @Test
    fun theRedactingDecoratorMasksTheLineBeforeTheSink() {
        val seen = mutableListOf<String>()
        val log = AdapterLog.redacting { seen += it }
        log.log("GET /Items?api_key=dummy-token-0001&limit=5 failed")
        assertEquals(listOf("GET /Items?api_key=<redacted>&limit=5 failed"), seen)
    }

    @Test
    fun theRedactingDecoratorMasksTheThrowableMessageBeforeTheSink() {
        val seen = mutableListOf<String>()
        val log = AdapterLog.redacting { seen += it }
        val error = IOException("Unable to resolve http://jf.example.test/Videos/a?ApiKey=dummy-token-0001")
        log.log("stream failed", error)
        assertEquals(1, seen.size)
        assertFalse("dummy-token-0001" in seen.single(), seen.single())
        assertTrue("IOException" in seen.single(), seen.single())
        assertTrue("stream failed" in seen.single(), seen.single())
    }

    @Test
    fun aPlainSinkGetsTheThrowableTypeAndMessageThroughTheDefault() {
        val seen = mutableListOf<String>()
        AdapterLog { seen += it }.log("boom", IllegalStateException("why"))
        assertEquals(listOf("boom: IllegalStateException: why"), seen)
    }

    @Test
    fun aThrowableWithNoMessageLogsItsTypeOnly() {
        val seen = mutableListOf<String>()
        AdapterLog { seen += it }.log("boom", IllegalStateException())
        assertEquals(listOf("boom: IllegalStateException"), seen)
    }
}
