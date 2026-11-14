// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupIds
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.Source
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The one-time rewrite (docs/adr/0026-studio-fold-2.md, Versioning): once the format 4 gate has passed, a stored lineup
 * whose rules a load upgraded is saved once, in the background, through the normal save; a second start finds nothing
 * to upgrade and saves nothing, and a lineup with no studio feature is never saved for it. The rewrite re-reads the
 * file under the lock, so a save that got there first, a file that changed, and a session that may not write all leave
 * it alone. The rules formats are told once per account, from the first read on any path (counts only).
 */
class RulesRewriteTest {
    private val library = CountingLibrary().apply { seed(fillingLibrary()) }
    private val clock =
        object : Clock {
            override fun now(): Instant = NOON
        }

    private val studio3 = at3("Studio", Source.Studio("Studio A"))
    private val plain = rules("Plain", Source.Genre("Drama"))

    private fun at3(
        name: String,
        source: Source,
        ordering: Ordering? = null,
    ) = ChannelRules(3, name, Seed(name.hashCode().toULong()), listOf(source), null, null, ordering, timing(), null)

    private fun custom(
        id: String,
        number: Int,
        rules: ChannelRules,
    ) = channel(id, number, ChannelState.On, ChannelSource.Custom(rules))

    private val studioLineup = lineupOf(custom("custom:studio", 600, studio3), custom("custom:plain", 601, plain))

    /** A store, the service's hooks and what they were told, with a rewrite dispatcher that can hold work back. */
    private inner class World(
        val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) {
        val store = RecordingStore()
        val formats = mutableListOf<RulesFormats>()
        val events = mutableListOf<LineupEvent>()

        fun service(): LineupService {
            val seams = LineupSeams(TestCatalog, rollDispatcher = dispatcher, rulesFormats = { formats += it })
            return LineupService(store, library, clock, { 0 }, { events += it }, seams)
        }

        suspend fun stored(
            lineup: Lineup,
            account: LibraryAccount = ACCOUNT,
        ) {
            store.inner.save(account, LineupCodec.encode(lineup))
        }

        fun saves() = store.calls.count { it == "save" }

        /** The format versions of each channel's rules (and a starter's previous rules) as the file says them. */
        fun rulesOnDisk(account: LibraryAccount = ACCOUNT): List<Int> {
            val bytes = checkNotNull(store.inner.peek(account))
            val loaded = assertIs<LineupCodec.Decoded.Loaded>(LineupCodec.decode(bytes, gatePassed = false))
            return loaded.lineup.channels.flatMap {
                when (val source = it.source) {
                    is ChannelSource.Custom -> {
                        listOf(source.rules.formatVersion)
                    }

                    is ChannelSource.Starter -> {
                        listOfNotNull(source.rules, source.previousRules).map { r ->
                            r.formatVersion
                        }
                    }

                    else -> {
                        emptyList()
                    }
                }
            }
        }

        suspend fun start(): LineupService =
            service().apply {
                checkNotNull(loaded(ACCOUNT))
                awaitRewrites()
            }
    }

    @Test
    fun aStoredFormat3StudioChannelIsSavedOnceAt4AndASecondStartSavesNothing() =
        runBlocking<Unit> {
            val world = World()
            world.stored(studioLineup)

            val first = world.start()

            assertEquals(1, world.saves(), "saved once")
            assertEquals(listOf(4, 1), world.rulesOnDisk())
            val held = checkNotNull(first.loaded(ACCOUNT)).lineup.channels[0].source
            assertEquals(4, assertIs<ChannelSource.Custom>(held).rules.formatVersion, "memory holds what was saved")
            assertEquals(listOf(RulesFormats(v1 = 1, v4 = 1, upgraded = 1)), world.formats)

            world.start()

            assertEquals(1, world.saves(), "a second start finds nothing to upgrade")
            assertEquals(RulesFormats(v1 = 1, v4 = 1, upgraded = 0), world.formats.last())
        }

    @Test
    fun aLineupWithNoStudioFeatureIsNeverSavedForIt() =
        runBlocking<Unit> {
            val world = World()
            val noStudios = at3("Curated", Source.Genre("Drama"), Ordering(OrderingMode.Curated))
            world.stored(lineupOf(custom("custom:curated", 600, noStudios), custom("custom:plain", 601, plain)))

            world.start()
            world.start()

            assertEquals(0, world.saves())
            assertEquals(listOf(3, 1), world.rulesOnDisk())
            assertEquals(RulesFormats(v1 = 1, v3 = 1), world.formats.last())
        }

    // (a) A refresh that saves 4 before the rewrite runs: the rewrite finds 4 on disk and saves nothing more.
    @Test
    fun aRefreshThatSaves4FirstLeavesTheRewriteNothingToDo() =
        runBlocking<Unit> {
            val held = HeldDispatcher()
            val world = World(held)
            world.stored(studioLineup)
            val service = world.service()
            checkNotNull(service.loaded(ACCOUNT))

            service.refresh(ACCOUNT, LIBRARIES)
            assertEquals(1, world.saves(), "the refresh saved")
            assertEquals(4, world.rulesOnDisk().first())
            held.release()
            service.awaitRewrites()

            assertEquals(1, world.saves(), "the rewrite made no second save")
            assertEquals(listOf(RulesFormats(v1 = 1, v4 = 1, upgraded = 1)), world.formats, "told once")
        }

    // (b) The rewrite's save fails: the file stays 3 and it is reported; the next start saves it once.
    @Test
    fun aFailedRewriteLeavesTheFileAt3AndTheNextStartSavesItOnce() =
        runBlocking<Unit> {
            val world = World()
            world.stored(studioLineup)
            world.store.inner.failWrites = true

            world.start()

            assertEquals(listOf(3, 1), world.rulesOnDisk(), "the file is as it was")
            assertTrue(LineupEvent.SaveFailed in world.events)
            world.store.inner.failWrites = false
            val before = world.saves()

            world.start()

            assertEquals(before + 1, world.saves(), "the next start saves once")
            assertEquals(listOf(4, 1), world.rulesOnDisk())
            assertEquals(1, world.formats.last().upgraded, "the next start tells the upgrade again")
        }

    // (c) The file changes between the load and the rewrite: a newer app's, unreadable, unreadable now, or gone.
    @Test
    fun aFileThatChangedBeforeTheRewriteIsNotWritten() =
        runBlocking<Unit> {
            val newer = """{"version":99}""".encodeToByteArray()
            val garbage = "not a lineup".encodeToByteArray()
            val changes: List<Pair<String, suspend (PeekableStore) -> Unit>> =
                listOf(
                    "newer" to { it.save(ACCOUNT, newer) },
                    "unreadable" to { it.save(ACCOUNT, garbage) },
                    "read fails" to { it.failReads = true },
                    "forgotten" to { it.forget(ACCOUNT) },
                )
            for ((name, change) in changes) {
                val held = HeldDispatcher()
                val world = World(held)
                world.stored(studioLineup)
                val service = world.service()
                checkNotNull(service.loaded(ACCOUNT))
                change(world.store.inner)
                val bytes = world.store.inner.peek(ACCOUNT)

                held.release()
                service.awaitRewrites()

                assertEquals(0, world.saves(), name)
                assertEquals(listOf("load", "load"), world.store.calls, "$name: read, and nothing written")
                if (bytes == null) {
                    assertEquals(null, world.store.inner.peek(ACCOUNT), name)
                } else {
                    assertContentEquals(bytes, world.store.inner.peek(ACCOUNT), name)
                }
            }
        }

    // (d) A session that may no longer write (its last read of the file failed) writes nothing.
    @Test
    fun aSessionThatMayNotWriteDoesNotRewrite() =
        runBlocking<Unit> {
            val held = HeldDispatcher()
            val world = World(held)
            world.stored(studioLineup)
            val service = world.service()
            checkNotNull(service.loaded(ACCOUNT))
            world.store.inner.failReads = true
            assertEquals(RecordReset.Failed, service.floorRecord(ACCOUNT))
            world.store.inner.failReads = false

            held.release()
            service.awaitRewrites()

            assertEquals(0, world.saves())
            assertEquals(listOf(3, 1), world.rulesOnDisk())
        }

    // (e) A starter's previous rules that say 3 and hold a studio feature are rewritten too.
    @Test
    fun aStartersPreviousStudioRulesAreRewritten() =
        runBlocking<Unit> {
            val world = World()
            val source = ChannelSource.Starter("alpha", false, plain, previousRules = studio3, rulesFromMs = 5L)
            world.stored(lineupOf(channel(LineupIds.starter("alpha").value, 110, ChannelState.On, source)))

            world.start()

            assertEquals(1, world.saves())
            assertEquals(listOf(1, 4), world.rulesOnDisk(), "rules, then previous rules")
            assertEquals(listOf(RulesFormats(v1 = 1, v4 = 1, upgraded = 1)), world.formats)
            world.start()
            assertEquals(1, world.saves())
        }

    // (f) A lineup file of version 1 or 2 holding studio rules that say 3: its copy is kept before the save.
    @Test
    fun anOlderLineupFileIsCopiedBeforeTheRewrite() =
        runBlocking<Unit> {
            for (version in listOf(1, 2)) {
                val world = World()
                val text = LineupCodec.encode(studioLineup).decodeToString()
                val older = text.replace("\"version\":3", "\"version\":$version").encodeToByteArray()
                assertTrue(!older.contentEquals(text.encodeToByteArray()))
                world.store.inner.save(ACCOUNT, older)

                world.start()

                assertContentEquals(older, world.store.inner.keptCopy(ACCOUNT, "v$version"), "v$version kept")
                val calls = world.store.calls
                assertTrue(calls.indexOf("keepCopy") in 0 until calls.indexOf("save"), "copy first: $calls")
                assertEquals(1, world.saves())
                assertEquals(listOf(4, 1), world.rulesOnDisk())
            }
        }

    // (g) Two accounts: only the account that was loaded has its file rewritten.
    @Test
    fun onlyTheLoadedAccountsFileIsRewritten() =
        runBlocking<Unit> {
            val world = World()
            val otherUser = CanonicalId.parse("d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0")
            val other = LibraryAccount(ServerId(SERVER.value), UserId(otherUser.value))
            val otherLineup = Lineup(SERVER, otherUser, 1, 0, true, studioLineup.channels)
            world.stored(studioLineup)
            world.stored(otherLineup, other)
            val otherBytes = assertNotNull(world.store.inner.peek(other))

            world.start()

            assertEquals(listOf(4, 1), world.rulesOnDisk())
            assertContentEquals(otherBytes, world.store.inner.peek(other), "the other account's file is untouched")
            assertEquals(listOf(ACCOUNT), world.store.accounts.filterIndexed { i, _ -> world.store.calls[i] == "save" })
        }

    // The formats are told once per account, from whichever path reads the file first.
    @Test
    fun theFormatsAreToldOnceFromTheFirstRead() =
        runBlocking<Unit> {
            val world = World()
            world.stored(studioLineup)
            val service = world.service()

            service.floorRecord(ACCOUNT)
            service.loaded(ACCOUNT)
            service.refresh(ACCOUNT, LIBRARIES)
            service.awaitRewrites()

            assertEquals(listOf(RulesFormats(v1 = 1, v4 = 1, upgraded = 1)), world.formats)
        }
}

/** Holds every dispatch back until [release], then runs them, and anything later, on [target]. */
private class HeldDispatcher(
    private val target: CoroutineDispatcher = Dispatchers.Default,
) : CoroutineDispatcher() {
    private val held = ConcurrentLinkedQueue<Pair<CoroutineContext, Runnable>>()

    @Volatile private var released = false

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        if (released) target.dispatch(context, block) else held += context to block
    }

    fun release() {
        released = true
        while (true) {
            val (context, block) = held.poll() ?: break
            target.dispatch(context, block)
        }
    }
}
