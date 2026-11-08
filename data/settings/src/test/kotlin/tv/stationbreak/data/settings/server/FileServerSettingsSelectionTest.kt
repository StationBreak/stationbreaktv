// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.Missing
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import java.util.concurrent.Executor

/** The stored selection of spec 26: its seq, the libraries being added, gone, the misses and the counters. */
class FileServerSettingsSelectionTest {
    private val inPlace = Executor(Runnable::run).asCoroutineDispatcher()
    private val file = MemoryRecordFile()
    private val settings = FileServerSettings(file, inPlace)
    private val address = ServerAddress(Scheme.Https, "nas.example.test", 8920)
    private val other = ServerAddress(Scheme.Https, "other.example.test", 443)
    private val pin = SpkiPin(ByteArray(SpkiPin.SIZE) { 7 })

    private val alpha = ItemId("alpha")
    private val beta = ItemId("beta")
    private val gamma = ItemId("gamma")

    private val full =
        StoredSelection(
            chosen = setOf(alpha, beta),
            seq = 9,
            adding = mapOf(gamma to 9),
            gone = true,
            missing = mapOf(beta to Missing(sinceMs = 1_000, sincePass = 3), gamma to Missing(2_000, 4)),
            listings = 5,
            resetSeq = 2,
        )

    private fun rebuilt() = FileServerSettings(file, inPlace)

    private fun stored(): StoredSettings = ServerRecordCodec.decodeStored(file.text!!)!!

    @Test
    fun emptyChosen_refused() =
        runBlocking {
            // No record: nothing to attach a selection to.
            assertFalse(settings.saveSelection(StoredSelection(setOf(alpha), seq = 1)))
            assertNull(file.text)
            settings.save(ServerRecord(address))
            val writes = file.writes
            assertFalse(settings.saveSelection(StoredSelection(emptySet(), seq = 1)))
            assertEquals(writes, file.writes)
            assertNull(settings.loadSelection())
        }

    @Test
    fun roundTrip_allFields() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            assertTrue(settings.saveSelection(full))
            assertEquals(full, settings.loadSelection())
            assertEquals(full, rebuilt().loadSelection())
            assertEquals(ServerRecord(address, pin), rebuilt().load())
            assertEquals(setOf(alpha, beta), rebuilt().loadLibraries())
        }

    @Test
    fun emptyFields_omitted_versionStill1() =
        runBlocking {
            settings.save(ServerRecord(address))
            assertTrue(settings.saveSelection(StoredSelection(setOf(alpha), seq = 1)))
            val lines = file.text!!.lines()
            assertEquals("version=1", lines.first())
            assertTrue(lines.toString(), "seq=1" in lines)
            for (key in listOf("adding", "gone", "missing", "listings", "selectionResetSeq")) {
                assertTrue("$key in $lines", lines.none { it.startsWith("$key=") })
            }
            // A seq of 0 is omitted too.
            val bare = ServerRecordCodec.encode(StoredSettings(ServerRecord(address), setOf(alpha)))
            assertTrue(bare, bare.lines().none { it.startsWith("seq=") })
        }

    @Test
    fun allFields_writeTheirKeys() =
        runBlocking {
            settings.save(ServerRecord(address))
            settings.saveSelection(full)
            val lines = file.text!!.lines()
            assertTrue(lines.toString(), "seq=9" in lines)
            assertTrue(lines.toString(), "adding=gamma:9" in lines)
            assertTrue(lines.toString(), "gone=1" in lines)
            assertTrue(lines.toString(), "missing=beta:1000:3,gamma:2000:4" in lines)
            assertTrue(lines.toString(), "listings=5" in lines)
            assertTrue(lines.toString(), "selectionResetSeq=2" in lines)
        }

    @Test
    fun oldFile_readsDefaults() =
        runBlocking {
            // What a build before spec 26 Part 4 wrote: the record and the chosen libraries, nothing more.
            file.text = "version=1\nscheme=https\nhost=nas.example.test\nport=8920\nsubpath=\nviaFallback=false\n" +
                "libraries=alpha,beta"
            assertEquals(StoredSelection(setOf(alpha, beta)), settings.loadSelection())
            assertEquals(0L, settings.loadSelectionSeq())
            assertEquals(setOf(alpha, beta), settings.loadPlayableLibraries())
        }

    @Test
    fun setupSave_bumpsSeq_clearsAdding() =
        runBlocking {
            settings.save(ServerRecord(address))
            settings.saveSelection(full)
            assertTrue(settings.saveLibraries(setOf(beta)))
            assertEquals(
                StoredSelection(chosen = setOf(beta), seq = 10, listings = 5, resetSeq = 10),
                settings.loadSelection(),
            )
        }

    @Test
    fun setupSave_withNoSeqYet_startsAtOne() =
        runBlocking {
            settings.save(ServerRecord(address))
            assertTrue(settings.saveLibraries(setOf(alpha)))
            assertEquals(StoredSelection(setOf(alpha), seq = 1, resetSeq = 1), settings.loadSelection())
        }

    @Test
    fun clearAndNewAddress_keepSeq() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            settings.saveSelection(full)

            assertTrue(settings.clearLibraries())
            assertNull(settings.loadSelection())
            assertNull(settings.loadLibraries())
            assertNull(settings.loadPlayableLibraries())
            assertEquals(9L, settings.loadSelectionSeq())
            assertEquals(StoredSettings(ServerRecord(address, pin), seq = 9, listings = 5, resetSeq = 2), stored())
            // A seq is never reused: a new selection takes one above the kept seq.
            assertFalse(settings.saveSelection(StoredSelection(setOf(alpha), seq = 9, resetSeq = 9)))
            assertTrue(settings.saveSelection(StoredSelection(setOf(alpha), seq = 10, listings = 5, resetSeq = 10)))

            assertTrue(settings.save(ServerRecord(other)))
            assertNull(settings.loadSelection())
            assertEquals(10L, settings.loadSelectionSeq())
            assertEquals(StoredSettings(ServerRecord(other), seq = 10, listings = 5, resetSeq = 10), stored())
            // A setup on the new server goes on from the kept seq.
            assertTrue(settings.saveLibraries(setOf(beta)))
            assertEquals(11L, settings.loadSelection()?.seq)
        }

    @Test
    fun clear_withNothingChosen_keepsTheCountersAndWritesNothing() =
        runBlocking {
            settings.save(ServerRecord(address))
            settings.saveSelection(full)
            settings.clearLibraries()
            val writes = file.writes
            assertTrue(settings.clearLibraries())
            assertEquals(writes, file.writes)
            assertEquals(9L, settings.loadSelectionSeq())
        }

    @Test
    fun sameAddressSave_keepsTheWholeSelection() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            settings.saveSelection(full)
            assertTrue(settings.clearPin())
            assertTrue(settings.save(ServerRecord(address)))
            assertEquals(full, settings.loadSelection())
        }

    @Test
    fun selectionSeq_nullWithoutARecord() =
        runBlocking {
            assertNull(settings.loadSelectionSeq())
            file.text = "not a record"
            assertNull(rebuilt().loadSelectionSeq())
            settings.save(ServerRecord(address))
            assertEquals(0L, settings.loadSelectionSeq())
        }

    @Test
    fun saveSelection_lowerSeq_refused() =
        runBlocking {
            settings.save(ServerRecord(address))
            settings.saveSelection(full)
            val writes = file.writes
            assertFalse(settings.saveSelection(full.copy(seq = 8, adding = mapOf(gamma to 8))))
            // An equal seq is bookkeeping only: a change to chosen, adding, gone or the reset seq takes a new seq.
            assertFalse(settings.saveSelection(full.copy(chosen = setOf(alpha))))
            assertFalse(settings.saveSelection(full.copy(adding = emptyMap(), missing = mapOf(beta to Missing(1, 1)))))
            assertFalse(settings.saveSelection(full.copy(gone = false)))
            assertFalse(settings.saveSelection(full.copy(resetSeq = 3)))
            assertEquals(writes, file.writes)
            assertEquals(full, settings.loadSelection())
        }

    @Test
    fun saveSelection_equalSeq_bookkeepingStored() =
        runBlocking {
            settings.save(ServerRecord(address))
            settings.saveSelection(full)
            val kept = full.copy(missing = emptyMap(), listings = 6)
            assertTrue(settings.saveSelection(kept))
            assertEquals(kept, settings.loadSelection())
        }

    @Test
    fun saveSelection_brokenRules_refused() =
        runBlocking {
            settings.save(ServerRecord(address))
            val base = StoredSelection(setOf(alpha), seq = 4)
            val broken =
                listOf(
                    // A library is chosen or being added, never both.
                    base.copy(adding = mapOf(alpha to 4)),
                    // A stamp is the seq of the change that ticked it, so never above the seq.
                    base.copy(adding = mapOf(beta to 5)),
                    base.copy(adding = mapOf(beta to -1)),
                    // Only chosen or adding libraries can be missing.
                    base.copy(missing = mapOf(gamma to Missing(1, 1))),
                    base.copy(missing = mapOf(alpha to Missing(-1, 1))),
                    // The reset seq is a seq that was saved.
                    base.copy(resetSeq = 5),
                    base.copy(listings = -1),
                )
            for (selection in broken) assertFalse(selection.toString(), settings.saveSelection(selection))
            assertNull(settings.loadSelection())
            val good = base.copy(adding = mapOf(beta to 4), missing = mapOf(beta to Missing(1, 1)))
            assertTrue(settings.saveSelection(good))
        }

    @Test
    fun saveSelection_resetSeqBelowStored_refused() =
        runBlocking {
            settings.save(ServerRecord(address))
            settings.saveSelection(full)
            // A writer that forgot to carry the reset seq is refused rather than allowed to lose it.
            val forgot = full.copy(seq = 10, adding = emptyMap(), missing = emptyMap(), resetSeq = 0)
            assertFalse(settings.saveSelection(forgot))
            assertEquals(2L, settings.loadSelection()?.resetSeq)
        }

    @Test
    fun playable_nullWhenGone() =
        runBlocking {
            settings.save(ServerRecord(address))
            settings.saveSelection(full)
            assertNull(settings.loadPlayableLibraries())
            // The choice is kept while gone: routing still sees a selection.
            assertEquals(setOf(alpha, beta), settings.loadLibraries())
            settings.saveSelection(full.copy(seq = 10, gone = false))
            // The libraries being added are never playable.
            assertEquals(setOf(alpha, beta), settings.loadPlayableLibraries())
        }

    @Test
    fun malformedNewKey_dropsOnlyThatKey() =
        runBlocking {
            settings.save(ServerRecord(address, pin))
            settings.saveSelection(full)
            val good = file.text!!
            val cases =
                mapOf(
                    "seq=9" to "seq=x",
                    "adding=gamma:9" to "adding=gamma",
                    "gone=1" to "gone=yes",
                    "missing=beta:1000:3,gamma:2000:4" to "missing=beta:1000",
                    "listings=5" to "listings=-2",
                    "selectionResetSeq=2" to "selectionResetSeq=",
                )
            for ((line, broken) in cases) {
                val read = ServerRecordCodec.decodeStored(good.replace(line, broken))
                val key = line.substringBefore('=')
                assertEquals(key, ServerRecord(address, pin), read?.record)
                assertEquals(key, setOf(alpha, beta), read?.libraries)
                val expected =
                    when (key) {
                        "seq" -> stored().copy(seq = 0)
                        "adding" -> stored().copy(adding = emptyMap())
                        "gone" -> stored().copy(gone = false)
                        "missing" -> stored().copy(missing = emptyMap())
                        "listings" -> stored().copy(listings = 0)
                        else -> stored().copy(resetSeq = 0)
                    }
                assertEquals(key, expected, read)
            }
        }

    @Test
    fun aBadIdInAMap_dropsThatKeyOnly() {
        val text =
            ServerRecordCodec.encode(StoredSettings(ServerRecord(address), setOf(alpha), seq = 3)) +
                "\nadding=a b:3\nmissing=alpha:1:1"
        val read = ServerRecordCodec.decodeStored(text)
        assertEquals(emptyMap<ItemId, Long>(), read?.adding)
        assertEquals(mapOf(alpha to Missing(1, 1)), read?.missing)
        assertEquals(setOf(alpha), read?.libraries)
    }

    @Test
    fun selectionKeys_withoutLibraries_areNotASelection() =
        runBlocking {
            // A selection exists only with its libraries: adding, gone and missing alone are dropped; counters stay.
            file.text = ServerRecordCodec.encode(ServerRecord(address)) +
                "\nseq=4\nadding=gamma:4\ngone=1\nmissing=gamma:1:1\nlistings=2\nselectionResetSeq=1"
            assertNull(settings.loadSelection())
            assertEquals(4L, settings.loadSelectionSeq())
            assertEquals(StoredSettings(ServerRecord(address), seq = 4, listings = 2, resetSeq = 1), stored())
        }

    @Test
    fun aFailedSelectionWrite_keepsTheOldSelection() =
        runBlocking {
            settings.save(ServerRecord(address))
            settings.saveSelection(full)
            file.failWrites = true
            assertFalse(settings.saveSelection(full.copy(seq = 10)))
            assertFalse(settings.saveLibraries(setOf(alpha)))
            assertEquals(full, settings.loadSelection())
            assertEquals(full, rebuilt().loadSelection())
        }
}
