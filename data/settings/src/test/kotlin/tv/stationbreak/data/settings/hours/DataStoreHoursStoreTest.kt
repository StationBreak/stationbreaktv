// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.hours

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.stationbreak.core.domain.hours.AudienceLevel
import tv.stationbreak.core.domain.hours.HoursPeriod
import tv.stationbreak.core.domain.hours.HoursSchedule
import java.io.File

/** The hours file on a real DataStore in a temporary folder, reopened to read back what reached the disk. */
@Suppress("InjectDispatcher") // Real files on the real IO dispatcher, as the app's store uses.
class DataStoreHoursStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    private fun file() = File(folder.root, DataStoreHoursStore.FILE_NAME)

    private fun open(): DataStoreHoursStore {
        close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return DataStoreHoursStore(DataStoreHoursStore.dataStore(scope) { file() })
    }

    @After
    fun close() =
        runBlocking {
            scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
            scopes.clear()
        }

    private val schedule = HoursSchedule(listOf(HoursPeriod(5, 20 * 60, 6 * 60, AudienceLevel.Kids)))

    @Test
    fun nothingStoredIsNoSchedule() = runBlocking { assertEquals(HoursSchedule.EMPTY, open().schedule.first()) }

    @Test
    fun aSavedScheduleSurvivesARelaunch() =
        runBlocking {
            open().save(schedule)
            assertEquals(schedule, open().schedule.first())
        }

    @Test
    fun aDamagedFileReadsAsNoSchedule() =
        runBlocking {
            file().writeBytes(byteArrayOf(1, 2, 3, 42, -1))
            assertEquals(HoursSchedule.EMPTY, open().schedule.first())
        }

    @Test
    fun aSaveReplacesADamagedFile() =
        runBlocking {
            file().writeBytes(byteArrayOf(1, 2, 3, 42, -1))
            open().save(schedule)
            assertEquals(schedule, open().schedule.first())
        }

    @Test
    fun readingAValidFileDoesNotWipeIt() =
        runBlocking {
            open().save(schedule)
            val reopened = open()
            assertEquals(schedule, reopened.schedule.first())
            assertEquals(schedule, reopened.schedule.first())
            assertEquals(schedule, open().schedule.first())
        }

    @Test
    fun aNewerVersionReadsAsNoSchedule_andASaveReplacesIt() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
            DataStoreHoursStore.dataStore(scope) { file() }.edit {
                it[stringPreferencesKey("schedule")] = """{"v":2,"periods":[]}"""
            }
            val reopened = open()
            assertEquals(HoursSchedule.EMPTY, reopened.schedule.first())
            reopened.save(schedule)
            assertEquals(schedule, open().schedule.first())
        }
}
