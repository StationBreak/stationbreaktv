// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.InMemoryLineupStore
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.domain.lineup.repair.repairOf
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupEdit
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.rules.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A starter the viewer switches on is saved as lineup file version 5, and the file it replaces is kept first under the
 * tag of its version and the new one (`v3-to5`), at the one place every save goes through.
 */
class LineupServiceSwitchOnTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val store = RecordingStore()
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }
    private val service = LineupService(store, library, clock, { 0 }, {}, TestCatalog)

    private val frights = LineupIds.starter("frights")

    // Frights is on the shelf and the planner's, untouched: switching it on adds a channel.
    private fun withShelved(): Lineup =
        lineupOf(
            starter("laughs", 110, ChannelState.On, rules("Laughs", Source.Genre("Comedy"))),
            starter("whodunit", 210, ChannelState.On, rules("Whodunit", Source.Genre("Crime"))),
            starter("frights", 310, ChannelState.Shelf, rules("Frights", Source.Genre("Horror"))),
        )

    private suspend fun seed(bytes: ByteArray): ByteArray {
        store.inner.save(ACCOUNT, bytes)
        store.calls.clear()
        return bytes
    }

    private fun peek(): LineupCodec.Decoded.Loaded =
        assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(checkNotNull(store.inner.peek(ACCOUNT))))

    private suspend fun switchOnFrights() = service.edit(ACCOUNT, LineupEdit.SwitchOn(frights))

    @Test
    fun aFirstSwitchOnSavesVersion5AndKeepsTheVersion3FileUnderV3To5() =
        runBlocking<Unit> {
            val v3 = seed(LineupCodec.encode(withShelved()))

            assertIs<Saved.Ok<*>>(switchOnFrights())

            assertEquals(5, peek().fileVersion)
            assertEquals(
                ViewerChoice.Added,
                peek()
                    .lineup.channels
                    .first { it.id == frights }
                    .viewerChoice,
            )
            assertContentEquals(v3, store.inner.keptCopy(ACCOUNT, "v3-to5"))
            assertNull(store.inner.keptCopy(ACCOUNT, "v3"), "no copy is made under the tag of the upgrade to 4")
        }

    @Test
    fun aVersion4FileKeepsV4To5() =
        runBlocking<Unit> {
            val record = TodayRepairs(selection = listOf(repairOf(atMs = NOON.toEpochMilliseconds() + 60_000L)))
            val v4 = seed(LineupCodec.encode(withShelved(), repairs = record))
            assertEquals(4, peek().fileVersion)

            assertIs<Saved.Ok<*>>(switchOnFrights())

            assertEquals(5, peek().fileVersion)
            assertContentEquals(v4, store.inner.keptCopy(ACCOUNT, "v4-to5"))
        }

    @Test
    fun aFreshV3To5CopyIsWrittenEvenWhenAnOldV3BackupExists() =
        runBlocking<Unit> {
            store.inner.save(ACCOUNT, "an older file".encodeToByteArray())
            assertTrue(store.inner.keepCopy(ACCOUNT, "v3"))
            val v3 = seed(LineupCodec.encode(withShelved()))

            assertIs<Saved.Ok<*>>(switchOnFrights())

            assertContentEquals("an older file".encodeToByteArray(), store.inner.keptCopy(ACCOUNT, "v3"))
            assertContentEquals(v3, store.inner.keptCopy(ACCOUNT, "v3-to5"))
        }

    // The copy is made before the write; a failed save leaves it, and the next one writes version 5 under the same tag
    // with the file as it was, not with the new bytes.
    @Test
    fun aFailedFirstSaveThenASuccessfulOneKeepsTheOriginalUnderV3To5() =
        runBlocking<Unit> {
            val v3 = seed(LineupCodec.encode(withShelved()))
            store.inner.failWrites = true
            assertEquals(Saved.Failed, switchOnFrights())
            store.inner.failWrites = false

            assertIs<Saved.Ok<*>>(switchOnFrights())

            assertEquals(5, peek().fileVersion)
            assertContentEquals(v3, store.inner.keptCopy(ACCOUNT, "v3-to5"))
        }

    // A version-4 file whose record does not read is saved as version 3 by the drop (a downgrade: no tag); the 3 to 5
    // save after it is tagged by the version just written, `v3-to5`, and holds those bytes, not the version 4 ones.
    @Test
    fun aVersion4FileWithAnUnreadableRecordSavedAsV3ThenAV3To5SaveIsTaggedV3To5() =
        runBlocking<Unit> {
            val record = TodayRepairs(selection = listOf(repairOf(atMs = NOON.toEpochMilliseconds() + 60_000L)))
            val good = LineupCodec.encode(withShelved(), repairs = record).decodeToString()
            val broken = good.replace(Regex("\"repairs\":\\{.*?\\}(?=\\}\\s*$)"), "\"repairs\":{\"selection\":42}")
            assertTrue(broken != good, "the record is made unreadable")
            seed(broken.encodeToByteArray())

            assertIs<Saved.Ok<*>>(switchOnFrights())

            val keptAsV3 = checkNotNull(store.inner.keptCopy(ACCOUNT, "v3-to5"))
            assertEquals(3, (LineupCodec.decode(keptAsV3) as LineupCodec.Decoded.Loaded).fileVersion)
            assertNull(store.inner.keptCopy(ACCOUNT, "v4-to5"), "the file on disk was version 3 by then")
            assertContentEquals(broken.encodeToByteArray(), store.inner.keptCopy(ACCOUNT, "repairs-unreadable"))
        }

    // A reset rebuilds the starters fresh and drops the marks: a version 5 file is saved as 3, with no copy of the
    // higher version.
    @Test
    fun aResetOnAVersion5FileSavesVersion3() =
        runBlocking<Unit> {
            seed(LineupCodec.encode(withShelved()))
            assertIs<Saved.Ok<*>>(switchOnFrights())
            assertEquals(5, peek().fileVersion)

            assertIs<Saved.Ok<*>>(service.reset(ACCOUNT, LIBRARIES))

            assertEquals(3, peek().fileVersion)
            assertTrue(peek().lineup.channels.none { it.viewerChoice == ViewerChoice.Added })
        }

    // Every pair of versions a first save can skip over has a tag the stores take: v1, v2 and v3 as files already on
    // devices carry them, `v{from}-to{to}` for every other pair.
    @Test
    fun everyPairOfVersionsHasATagTheStoreTakes() =
        runBlocking<Unit> {
            val files = InMemoryLineupStore()
            files.save(ACCOUNT, "bytes".encodeToByteArray())
            var pairs = 0
            for (from in 1..LineupCodec.VERSION) {
                for (to in from + 1..LineupCodec.VERSION) {
                    val tag = upgradeCopyTag(from, to)
                    assertTrue(files.keepCopy(ACCOUNT, tag), "$from to $to: $tag")
                    pairs++
                }
            }
            assertEquals(21, pairs)
            assertEquals("v1", upgradeCopyTag(1, 3))
            assertEquals("v2", upgradeCopyTag(2, 3))
            assertEquals("v3", upgradeCopyTag(3, 4))
            assertEquals("v3-to5", upgradeCopyTag(3, 5))
            assertEquals("v4-to5", upgradeCopyTag(4, 5))
            assertEquals("v1-to4", upgradeCopyTag(1, 4))
            assertEquals("v2-to5", upgradeCopyTag(2, 5))
            assertEquals("v5-to6", upgradeCopyTag(5, 6))
            assertEquals("v3-to6", upgradeCopyTag(3, 6))
            assertEquals("v3-to7", upgradeCopyTag(3, 7))
        }

    // `forget` removes the `-to` copies with the rest (the store's tags are one family).
    @Test
    fun forgetRemovesTheToCopiesToo() =
        runBlocking<Unit> {
            val files = InMemoryLineupStore()
            files.save(ACCOUNT, "bytes".encodeToByteArray())
            assertTrue(files.keepCopy(ACCOUNT, "v3-to5"))
            assertTrue(files.keepCopy(ACCOUNT, "v1"))

            assertTrue(files.forget(ACCOUNT))

            assertNull(files.keptCopy(ACCOUNT, "v3-to5"))
            assertNull(files.keptCopy(ACCOUNT, "v1"))
            for (bad in listOf("v3-to", "v-to5", "v3-5", "v3-to5x", "to5", "v3-to5-to6")) {
                files.save(ACCOUNT, "bytes".encodeToByteArray())
                assertTrue(!files.keepCopy(ACCOUNT, bad), bad)
            }
        }
}
