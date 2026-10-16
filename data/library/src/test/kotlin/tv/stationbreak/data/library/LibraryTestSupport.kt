// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import android.content.Context
import android.content.ContextWrapper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The accounts and libraries every test uses. Placeholder IDs only. */
internal val ALICE = LibraryAccount(ServerId("server-1"), UserId("alice"))
internal val BOB = LibraryAccount(ServerId("server-1"), UserId("bob"))
internal val ALICE_ON_ANOTHER_SERVER = LibraryAccount(ServerId("server-2"), UserId("alice"))
internal val MOVIES = ItemId("lib-movies")
internal val SHOWS = ItemId("lib-shows")

/** A moment well after the test items were created. */
internal val EPOCH: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)

/** A clock a test moves by hand (the tombstone purge and the last-success time read it). */
internal class FakeClock(
    var instant: Instant = EPOCH,
) : Clock {
    override fun now(): Instant = instant
}

/** A usable item: created a minute before [EPOCH], 90 minutes long. */
internal fun item(
    id: String,
    created: Instant? = EPOCH - 1.minutes,
    runtime: kotlin.time.Duration? = 90.minutes,
) = MediaItem(ItemId(id), "Name of $id", ItemKind.Movie, runtime, MOVIES, dateCreated = created)

/**
 * A Context for Room on the JVM: the stubbed android.jar answers with defaults, and database files go under [dir]. The
 * tests use no real Android framework (Robolectric is not in the build); the app's real SQLite is exercised by the
 * instrumented test on the emulator.
 */
internal class TestContext(
    private val dir: File,
) : ContextWrapper(null) {
    override fun getDatabasePath(name: String): File = File(dir, name)

    override fun getApplicationContext(): Context = this
}

/** The schema Room exported at build time (`schemas/`, committed so a change shows in review). */
internal object SchemaFile {
    /** Unit tests run in the module's directory. */
    val file = File("schemas/tv.stationbreak.data.library.LibraryDatabase/${LibraryDatabase.VERSION}.json")

    fun text(): String = file.readText()

    fun columnNames(): List<String> = valuesOf("columnName")

    fun tableNames(): List<String> = valuesOf("tableName")

    private fun valuesOf(key: String): List<String> {
        val pattern = Regex("\"$key\":\\s*\"([^\"]+)\"")
        return pattern.findAll(text()).map { it.groupValues[1] }.toList()
    }
}

/** Room on the JVM, over the bundled SQLite driver (test only). */
internal object TestLibraries {
    /** A database that lives in memory and is gone when closed. */
    fun inMemory(clock: Clock = FakeClock()): RoomLibraryRepository {
        val configured = builder(null, null)
        return RoomLibraryRepository.testing(configured, clock)
    }

    /** A database file named [name] under [dir]; opening the same name again reads what the last open stored. */
    fun onDisk(
        dir: File,
        name: String = "library-test.db",
        clock: Clock = FakeClock(),
    ): RoomLibraryRepository = RoomLibraryRepository.testing(builder(dir, name), clock)

    private fun builder(
        dir: File?,
        name: String?,
    ) = LibraryDatabases
        .builder(TestContext(dir ?: File(System.getProperty("java.io.tmpdir"))), name)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
}
