// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.core.domain.FakeMediaServer
import tv.stationbreak.core.domain.InMemorySecretStore
import tv.stationbreak.core.domain.InMemoryServerSettings
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.data.library.SyncWorld.TOKEN
import tv.stationbreak.data.library.SyncWorld.movies
import tv.stationbreak.data.library.SyncWorld.session
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Spec 06 task 3.7 (Security: Tokens): after a sync, the worker's input data, the database file and everything printed
 * or logged during the sync hold no token. The session is only ever handed to the server port, from memory.
 */
class SyncSecretScanTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()

    // Built on first use: the temporary folder exists only once the rule has run.
    private val repository by lazy { TestLibraries.onDisk(folder.root, "scan.db", clock) }

    @After
    fun close() = repository.close()

    private val fake = FakeMediaServer()
    private val server = PagedMediaServer(listOf(movies), mapOf(MOVIES to SyncWorld.source("m", 260)), fake)
    private val store = InMemorySecretStore()
    private val sessions by lazy { SessionRepository(store, server, repository) }
    private val settings = InMemoryServerSettings(ServerRecord(session.address), setOf(MOVIES))

    private fun containsToken(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1).contains(TOKEN)

    private fun databaseFiles(): List<File> =
        folder.root
            .listFiles { file -> file.name.startsWith("scan.db") }
            .orEmpty()
            .toList()

    /** Everything printed to the console or logged through java.util.logging while [block] runs. */
    private fun captured(block: () -> Unit): String {
        val out = ByteArrayOutputStream()
        val printed = PrintStream(out, true)
        val oldOut = System.out
        val oldErr = System.err
        val logged = StringBuilder()
        val root = Logger.getLogger("")
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    logged.append(record.message).append(record.parameters?.joinToString().orEmpty())
                    record.thrown?.let { logged.append(it.toString()) }
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        System.setOut(printed)
        System.setErr(printed)
        root.addHandler(handler)
        try {
            block()
        } finally {
            root.removeHandler(handler)
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        return out.toString(Charsets.ISO_8859_1) + logged
    }

    @Test
    fun theWorkRequestCarriesNoInputAtAll() {
        val input = FirstSyncWork.request().workSpec.input

        assertTrue(input.keyValueMap.isEmpty())
        assertFalse(containsToken(input.toByteArray()))
    }

    @Test
    fun afterASyncTheDatabaseFilesHoldNoToken() =
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Success(session)
            val runner = FirstSyncRunner(settings, sessions, server, repository, clock)

            runner.run(attempt = 0)

            assertEquals(260, repository.stored(ALICE).size)
            val files = databaseFiles()
            assertTrue("no database file was written", files.isNotEmpty())
            files.forEach { assertFalse("${it.name} holds the token", containsToken(it.readBytes())) }
        }

    @Test
    fun nothingPrintedOrLoggedDuringASyncHoldsTheToken() =
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Success(session)
            val runner = FirstSyncRunner(settings, sessions, server, repository, clock)

            val output = captured { runBlocking { runner.run(attempt = 0) } }

            assertFalse("the token reached the log output", output.contains(TOKEN))
        }

    @Test
    fun aFailedSyncPrintsAndLogsNoTokenEither() =
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Success(session)
            server.respond = { if (it.startIndex == 100 && !it.idsOnly) MediaServerError.Timeout() else null }
            val runner = FirstSyncRunner(settings, sessions, server, repository, clock)

            val output = captured { runBlocking { runner.run(attempt = 0) } }

            assertFalse(output.contains(TOKEN))
            databaseFiles().forEach { assertFalse(containsToken(it.readBytes())) }
        }

    @Test
    fun theOutcomesAndTheStatusPrintNoToken() =
        runBlocking {
            sessions.save(session)
            fake.resumeResult = ServerResult.Success(session)
            val engine = SyncWorld.engine(repository, server, clock)

            val outcome = engine.run(session, ALICE, setOf(MOVIES))

            assertFalse(outcome.toString().contains(TOKEN))
            assertFalse(repository.stored(ALICE).toString().contains(TOKEN))
            assertFalse(repository.rowCounts().toString().contains(TOKEN))
        }
}
