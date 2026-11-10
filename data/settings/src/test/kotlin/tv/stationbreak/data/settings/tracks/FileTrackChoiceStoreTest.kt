// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.tracks

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.tracks.SubtitlePreference
import tv.stationbreak.core.domain.tracks.TrackChoiceStore
import tv.stationbreak.core.domain.tracks.TrackPreferences
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaSourceId
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.data.settings.server.RecordFile
import java.io.IOException
import java.util.concurrent.Executor

/** The track choices per account behind the [TrackChoiceStore] port, files faked. */
class FileTrackChoiceStoreTest {
    private class MemoryFile : RecordFile {
        var text: String? = null
        var failWrites = false
        var failReads = false

        override fun read(): String? {
            if (failReads) throw IOException("unreadable")
            return text
        }

        override fun write(text: String) {
            if (failWrites) throw IOException("disk full")
            this.text = text
        }
    }

    private val inPlace = Executor(Runnable::run).asCoroutineDispatcher()
    private val files = mutableMapOf<String, MemoryFile>()
    private val store = FileTrackChoiceStore({ name -> files.getOrPut(name) { MemoryFile() } }, inPlace)
    private val account = LibraryAccount(ServerId("server-a"), UserId("user-a"))

    @Test
    fun nothingSavedReadsAsEmpty() =
        runBlocking {
            assertEquals(TrackPreferences(), store.preferences(account))
            assertNull(store.version(account, ItemId("i1")))
        }

    @Test
    fun preferencesAreKeptAcrossAReopen() =
        runBlocking {
            assertTrue(store.saveAudioLanguage(account, "es"))
            assertTrue(store.saveSubtitles(account, SubtitlePreference.On("en")))
            val reopened = FileTrackChoiceStore({ name -> files.getValue(name) }, inPlace)
            assertEquals(TrackPreferences("es", SubtitlePreference.On("en")), reopened.preferences(account))
            assertTrue(store.saveSubtitles(account, SubtitlePreference.Off))
            assertEquals(SubtitlePreference.Off, reopened.preferences(account).subtitles)
            assertEquals("the audio choice stayed", "es", reopened.preferences(account).audioLanguage)
        }

    @Test
    fun savingNoLanguageClearsTheAudioChoice() =
        runBlocking {
            store.saveAudioLanguage(account, "es")
            store.saveAudioLanguage(account, null)
            assertNull(store.preferences(account).audioLanguage)
        }

    @Test
    fun aVersionIsRememberedPerItem() =
        runBlocking {
            assertTrue(store.saveVersion(account, ItemId("i1"), MediaSourceId("a")))
            assertTrue(store.saveVersion(account, ItemId("i2"), MediaSourceId("b")))
            assertTrue(store.saveVersion(account, ItemId("i1"), MediaSourceId("c")))
            assertEquals(MediaSourceId("c"), store.version(account, ItemId("i1")))
            assertEquals(MediaSourceId("b"), store.version(account, ItemId("i2")))
        }

    @Test
    fun onlyTheMostRecentVersionsAreKept() =
        runBlocking {
            repeat(TrackChoiceStore.MAX_VERSIONS + 3) {
                store.saveVersion(account, ItemId("i$it"), MediaSourceId("s$it"))
            }
            assertNull(store.version(account, ItemId("i0")))
            assertEquals(MediaSourceId("s202"), store.version(account, ItemId("i202")))
        }

    @Test
    fun anotherAccountHasItsOwnRecord() =
        runBlocking {
            store.saveAudioLanguage(account, "es")
            store.saveVersion(account, ItemId("i1"), MediaSourceId("a"))
            val other = LibraryAccount(ServerId("server-a"), UserId("user-b"))
            assertEquals(TrackPreferences(), store.preferences(other))
            assertNull(store.version(other, ItemId("i1")))
        }

    @Test
    fun noServerOrUserIdIsInAFileName() =
        runBlocking {
            store.saveAudioLanguage(account, "es")
            assertTrue(files.keys.none { "server-a" in it || "user-a" in it })
        }

    @Test
    fun aValueThatCouldBreakALineIsRefused() =
        runBlocking {
            assertFalse(store.saveAudioLanguage(account, "e\nv=x"))
            assertFalse(store.saveSubtitles(account, SubtitlePreference.On("en\n")))
            assertFalse(store.saveVersion(account, ItemId("i1"), MediaSourceId("a\tb")))
            assertFalse(store.saveVersion(account, ItemId("i1"), MediaSourceId("a\nb")))
            assertEquals(TrackPreferences(), store.preferences(account))
        }

    @Test
    fun aRecordWithBadLinesReadsWhatItCan() =
        runBlocking {
            store.saveAudioLanguage(account, "es")
            val file = files.values.single()
            file.text = "junk\na=\ns=on:\nv=\nv=ok-item\tsrc\nx=1\n" + file.text
            assertEquals("es", store.preferences(account).audioLanguage)
            assertEquals(MediaSourceId("src"), store.version(account, ItemId("ok-item")))
        }

    @Test
    fun aFaultIsAFalseOrAnEmptyReadNeverAnException() =
        runBlocking {
            store.saveAudioLanguage(account, "es")
            val file = files.values.single()
            file.failWrites = true
            assertFalse(store.saveSubtitles(account, SubtitlePreference.Off))
            assertFalse(store.saveVersion(account, ItemId("i1"), MediaSourceId("a")))
            file.failWrites = false
            file.failReads = true
            assertEquals(TrackPreferences(), store.preferences(account))
            assertNull(store.version(account, ItemId("i1")))
            assertFalse(
                "a save that cannot read the record first must not overwrite it",
                store.saveVersion(account, ItemId("i1"), MediaSourceId("a")),
            )
            file.failReads = false
            assertEquals("the record survived", "es", store.preferences(account).audioLanguage)
        }

    @Test
    fun theStoreNeverPrintsItsContents() {
        assertEquals("FileTrackChoiceStore", store.toString())
    }

    @Test
    fun theHearingImpairedBitIsKeptAcrossAReopen() =
        runBlocking {
            store.saveSubtitles(account, SubtitlePreference.On("en", hearingImpaired = true))
            val reopened = FileTrackChoiceStore({ name -> files.getValue(name) }, inPlace)
            assertEquals(SubtitlePreference.On("en", hearingImpaired = true), reopened.preferences(account).subtitles)
            store.saveSubtitles(account, SubtitlePreference.On("en"))
            assertEquals(SubtitlePreference.On("en"), reopened.preferences(account).subtitles)
        }

    @Test
    fun aRecordFromBeforeTheBitReadsAsAPlainPreference() =
        runBlocking {
            store.saveAudioLanguage(account, "es")
            files.values.single().text = "s=on:en\ns=on:fr:bogus"
            assertEquals(SubtitlePreference.On("en"), store.preferences(account).subtitles)
        }
}
