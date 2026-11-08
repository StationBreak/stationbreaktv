// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.ListLibrarySource
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.LibraryKind
import tv.stationbreak.data.library.SyncWorld.session
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Spec 26, schema 11: the shared rows' invariant under random histories. Each run is a fixed seed: 30 operations on
 * 4 libraries and 8 items against a real Room database (the JVM driver), mirrored by an independent model of the
 * library classes, the owners, the tombstones and each library's latest observation of each item. The operations are
 * writes by a chosen, adding or held writer (a held one is refused), a chosen take from an adding owner, a tombstone
 * revival by a non-owner, an ID reconcile through the engine (both reads), an untick (with or without a hold), the
 * re-tick of a held library, a new tick, a hold expiry, a history purge with its cascades, `revertUnproven` and
 * `landAdd`. After each one it checks INV-1 and INV-2 in SQL, the owners and tombstones against the model (INV-4: the
 * owner moved only along a listed path), every owner change versioned, INV-3 against the model's observations, a live
 * item a chosen library lists still held, a generation that never goes down, and a bump for every owner change of a
 * chosen item. A failure names the seed and the step. Invented libraries and names only.
 */
class SharedRowsPropertyTest {
    @Test
    fun sharedRows_keepTheInvariant_underRandomHistories() {
        val reached = HashMap<String, Int>()
        for (run in 0 until RUNS) Run(SEED_BASE + run, reached).go()
        // Every path the invariant is about was taken in some run: the histories are not all trivial.
        for (path in PATHS) assertTrue("never reached: $path ($reached)", (reached[path] ?: 0) > 0)
    }

    private enum class Cls { Chosen, Adding, Held }

    private class Lib(
        var stamp: Long? = null,
        var heldUntil: Long? = null,
        var marked: Boolean = false,
        var walked: Long? = null,
    ) {
        val cls: Cls
            get() =
                when {
                    heldUntil != null -> Cls.Held
                    stamp != null -> Cls.Adding
                    else -> Cls.Chosen
                }
    }

    private class Item(
        var owner: ItemId,
        var deleted: Boolean,
    )

    private class Run(
        val seed: Long,
        val reached: MutableMap<String, Int>,
    ) {
        private fun reach(path: String) {
            reached[path] = (reached[path] ?: 0) + 1
        }

        private val random = Random(seed)
        private val clock = FakeClock()
        private val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
        private val ids = listOf(MOVIES, SHOWS, ADDS, MORE)
        private val sources = ids.associateWith { ListLibrarySource() }
        private val server =
            PagedMediaServer(ids.map { Library(it, "Invented ${it.value.takeLast(2)}", LibraryKind.Movies) }, sources)
        private val libs = LinkedHashMap<ItemId, Lib>()
        private val items = HashMap<Int, Item>()
        private val listed = HashMap<ItemId, MutableSet<Int>>()
        private val serverLists = ids.associateWith { HashSet<Int>() }
        private var nextStamp = 100L
        private var step = 0
        private var what = ""

        fun go() =
            runBlocking {
                try {
                    repository.useAccount(ALICE)
                    // Start with two chosen libraries, so there is something to write to.
                    select(setOf(MOVIES, SHOWS))
                    check()
                    while (step < OPERATIONS) {
                        step++
                        clock.stepMs(STEP_MS)
                        operate()
                        check()
                    }
                } catch (e: AssertionError) {
                    throw AssertionError("seed $seed, step $step ($what): ${e.message}", e)
                } finally {
                    repository.close()
                }
            }

        // One random operation, applied to the database and to the model.
        @Suppress("CyclomaticComplexMethod") // One branch per kind of operation.
        private suspend fun operate() {
            when (random.nextInt(14)) {
                0, 1, 2 -> write(pickLib() ?: return, pickItems())
                3 -> take()
                4 -> reviveByNonOwner()
                5, 6 -> reconcile()
                7 -> untick()
                8 -> retickHeld()
                9 -> tickNew()
                10 -> expire()
                11 -> purge()
                12 -> revert()
                else -> land()
            }
        }

        private fun pickLib(): ItemId? = libs.keys.toList().randomOrNull(random)

        private fun pickItems(): List<Int> = (1..ITEMS).shuffled(random).take(1 + random.nextInt(3))

        // A write of [ns] by [library]: refused when held, otherwise each item follows the write table.
        private suspend fun write(
            library: ItemId,
            ns: List<Int>,
        ) {
            val lib = libs.getValue(library)
            what = "write by $library (${lib.cls}) of $ns"
            val page = ns.map { sitem(it, runtime = if (random.nextBoolean()) 60.minutes else 90.minutes) }
            val result = repository.upsertItems(ALICE, library, lib.stamp, rewalk = false, page)
            if (lib.cls == Cls.Held) {
                assertEquals("a held writer is refused", PageResult.StampMismatch, result)
                reach("held write refused")
                return
            }
            result.written()
            for (n in ns) {
                val item = items[n]
                when {
                    item == null -> {
                        items[n] = Item(library, deleted = false)
                    }

                    item.owner == library -> {
                        item.deleted = false
                    }

                    item.deleted -> {
                        // A tombstone takeover: the old owner's row is never inserted (exception (a)).
                        reach("tombstone takeover on write")
                        listed[item.owner]?.remove(n)
                        item.owner = library
                        item.deleted = false
                    }

                    libs.getValue(item.owner).cls == Cls.Adding && lib.stamp == null -> {
                        reach("chosen takes from adding")
                        item.owner = library
                    }

                    else -> {
                        reach("non-owner write")
                    }
                }
                listed.getOrPut(library) { HashSet() } += n
            }
        }

        // A chosen writer writes an item an adding library owns.
        private suspend fun take() {
            val owned = items.filter { (_, it) -> !it.deleted && libs.getValue(it.owner).cls == Cls.Adding }.keys
            val writer = libs.filter { it.value.cls == Cls.Chosen }.keys.randomOrNull(random)
            if (owned.isEmpty() || writer == null) return write(pickLib() ?: return, pickItems())
            write(writer, listOf(owned.random(random)))
        }

        // A library that does not own a tombstone writes it.
        private suspend fun reviveByNonOwner() {
            val gone = items.filter { it.value.deleted }.keys.randomOrNull(random)
            val writer = gone?.let { n -> libs.keys.filter { it != items.getValue(n).owner }.randomOrNull(random) }
            if (gone == null || writer == null) return write(pickLib() ?: return, pickItems())
            write(writer, listOf(gone))
        }

        // The server's listing of a chosen library changes a little, then the engine reconciles it.
        private suspend fun reconcile() {
            val library = libs.filter { it.value.cls == Cls.Chosen }.keys.randomOrNull(random) ?: return
            val listing = serverLists.getValue(library)
            repeat(random.nextInt(3)) {
                val n = 1 + random.nextInt(ITEMS)
                if (!listing.remove(n)) listing += n
            }
            val source = sources.getValue(library)
            source.items.clear()
            listing.sorted().forEach { source.items += sitem(it) }
            val lib = libs.getValue(library)
            what = "reconcile of $library (marked ${lib.marked}) listing ${listing.sorted()}"
            server.calls.clear()
            val outcome = StayCurrentWorld.engine(repository, server, clock).reconcile(session, ALICE, setOf(library))
            val live = items.filter { it.value.owner == library && !it.value.deleted }.keys
            val also = listed[library].orEmpty().filter { n -> items[n]?.owner != library }.toSet()
            when {
                lib.marked -> {
                    reach("marked not reconciled")
                    assertEquals("a marked library is not reconciled", SyncOutcome.Completed, outcome.outcome)
                }

                listing.isEmpty() && (live + also).isNotEmpty() -> {
                    assertTrue("an empty listing is suspect", outcome.outcome is SyncOutcome.Failed)
                }

                else -> {
                    assertEquals(SyncOutcome.Completed, outcome.outcome)
                    reconciled(library, listing, live, also)
                }
            }
        }

        // The model of a completed reconcile of [library], which held [live] and [also] before it read [listing].
        private fun reconciled(
            library: ItemId,
            listing: Set<Int>,
            live: Set<Int>,
            also: Set<Int>,
        ) {
            val held = live + also
            val known = items.keys.toSet()
            val gone = held - listing
            for (n in gone intersect live) {
                val target = best(n, exclude = library)
                reach(if (target == null) "reconcile tombstone" else "reconcile move")
                if (target == null) items.getValue(n).deleted = true else items.getValue(n).owner = target
            }
            if ((gone intersect also).isNotEmpty()) reach("reconcile removal half")
            for (n in listing intersect known) {
                val item = items.getValue(n)
                if (item.owner == library) {
                    item.deleted = false
                } else if (item.deleted) {
                    reach("reconcile takeover")
                    listed[item.owner]?.remove(n)
                    item.owner = library
                    item.deleted = false
                }
            }
            for (n in listing - known) items[n] = Item(library, deleted = false)
            listed[library] = listing.filterTo(HashSet()) { it in items }
        }

        // The best staying library that lists [n] (other than [exclude]): chosen, adding, held, then the smallest id.
        private fun best(
            n: Int,
            exclude: ItemId? = null,
            staying: Set<ItemId> = libs.keys,
        ): ItemId? =
            staying
                .filter { it != exclude && n in listed[it].orEmpty() }
                .minWithOrNull(compareBy({ libs.getValue(it).cls.ordinal }, { it.value }))

        private suspend fun untick() {
            val library = libs.filter { it.value.cls != Cls.Held }.keys.randomOrNull(random) ?: return
            val keep = libs.filter { it.value.cls != Cls.Held }.keys - library
            val until = clock.nowMs + (1 + random.nextInt(MAX_HOLD_STEPS)) * STEP_MS
            val hold = if (random.nextBoolean()) mapOf(library to until) else emptyMap()
            what = "untick $library hold $hold"
            select(keep, holds = hold)
        }

        private suspend fun retickHeld() {
            val library = libs.filter { it.value.cls == Cls.Held }.keys.randomOrNull(random) ?: return
            val stamps = if (random.nextBoolean()) mapOf(library to nextStamp++) else emptyMap()
            what = "re-tick held $library stamps $stamps"
            select(libs.filter { it.value.cls != Cls.Held }.keys + library, stamps)
        }

        private suspend fun tickNew() {
            val library = ids.filter { it !in libs }.randomOrNull(random) ?: return
            val stamps = if (random.nextBoolean()) mapOf(library to nextStamp++) else emptyMap()
            what = "tick $library stamps $stamps"
            select(libs.filter { it.value.cls != Cls.Held }.keys + library, stamps)
        }

        private suspend fun expire() {
            clock.stepMs(random.nextLong(3 * STEP_MS))
            what = "expire holds"
            select(libs.filter { it.value.cls != Cls.Held }.keys)
        }

        private suspend fun purge() {
            clock.stepMs(PURGE_JUMP_MS)
            what = "purge"
            val outcome = repository.purgeHistory(ALICE)
            assertTrue("$outcome", outcome is PurgeOutcome.Purged)
            val gone = items.filter { it.value.deleted }.keys
            if (gone.isNotEmpty()) reach("purge")
            for (n in gone) {
                items.remove(n)
                listed.values.forEach { it.remove(n) }
            }
        }

        private suspend fun revert() {
            val (library, lib) = libs.entries.filter { it.value.cls == Cls.Adding }.randomOrNull(random) ?: return
            what = "revertUnproven $library"
            val reverted = repository.revertUnproven(ALICE, library, lib.stamp!!)
            val expected = lib.walked != lib.stamp
            assertEquals(expected, reverted)
            if (reverted) {
                reach("revert")
                lib.stamp = null
                lib.walked = null
                lib.marked = true
            }
        }

        private suspend fun land() {
            val (library, lib) = libs.entries.filter { it.value.cls == Cls.Adding }.randomOrNull(random) ?: return
            what = "land $library"
            val stamp = lib.stamp!!
            assertTrue(repository.markAddWalked(ALICE, library, stamp, clock.nowMs))
            assertTrue(repository.landAdd(ALICE, library, stamp))
            reach("land")
            lib.stamp = null
            lib.walked = null
            lib.marked = false
        }

        // The selection, applied to the database and to the model (the rules of LibraryDao.applySelectionRows and the
        // delete-time moves of CacheTransactionDao.applySelection).
        private suspend fun select(
            keep: Set<ItemId>,
            stamps: Map<ItemId, Long> = emptyMap(),
            holds: Map<ItemId, Long> = emptyMap(),
        ) {
            repository.applySelection(ALICE, keep, stamps, holds, clock.nowMs)
            val deleted = selectRows(keep, stamps, holds)
            val staying = libs.keys - deleted.toSet()
            for (x in deleted) {
                for ((n, item) in items.filter { it.value.owner == x }) {
                    val target = best(n, staying = staying)
                    reach(if (target == null) "delete cascade" else "delete move to ${libs.getValue(target).cls}")
                    if (target == null) items.remove(n) else item.owner = target
                }
            }
            for (x in deleted) {
                libs.remove(x)
                listed.remove(x)
            }
        }

        // The library rows of a selection (LibraryDao.applySelectionRows): the libraries to delete.
        private fun selectRows(
            keep: Set<ItemId>,
            stamps: Map<ItemId, Long>,
            holds: Map<ItemId, Long>,
        ): List<ItemId> {
            val now = clock.nowMs
            val deleted = ArrayList<ItemId>()
            for ((id, lib) in libs) {
                if (id in keep) continue
                val until = listOfNotNull(holds[id], lib.heldUntil).maxOrNull()
                if (until != null && until > now) {
                    lib.heldUntil = until
                    lib.stamp = null
                    lib.walked = null
                    lib.marked = false
                } else {
                    deleted += id
                }
            }
            for (id in keep) {
                val lib = libs[id]
                if (lib == null) {
                    libs[id] = Lib(stamp = stamps[id])
                    continue
                }
                val stamp = stamps[id]
                val newStamp = stamp != null && stamp != lib.stamp
                lib.heldUntil = null
                lib.stamp = stamp ?: lib.stamp
                if (newStamp) {
                    lib.marked = false
                    lib.walked = null
                }
            }
            return deleted
        }

        // What the last check saw, to judge the owner changes and the generation.
        private var lastOwners = emptyMap<String, String>()
        private var lastClasses = emptyMap<String, Cls>()
        private var lastGeneration = Long.MIN_VALUE

        private suspend fun check() {
            assertEquals("INV-1: a row names its owner", 0, repository.dao.countOwnerListed())
            assertEquals("INV-2: a row without its item or library", 0, repository.dao.countOrphanListed())
            val rows = repository.currentRows(ALICE).associateBy { it.itemId }
            val expected = items.entries.associate { (n, it) -> hexId(n) to (it.owner.value to it.deleted) }
            val actual = rows.mapValues { (_, row) -> row.libraryId to (row.deletedAtMs != null) }
            assertEquals("INV-4: owners and tombstones against the model", expected, actual)
            assertEquals("library classes", modelClasses(), storedClasses())
            assertEquals("INV-3: rows against the latest observations", modelRows(), databaseRows())
            for ((library, ns) in listed) {
                if (libs[library]?.cls != Cls.Chosen) continue
                val live = ns.filter { items[it]?.deleted == false }
                for (n in live) {
                    val row = rows[hexId(n)]
                    assertNotNull("a live item a chosen library lists lost its row", row)
                    assertNull("a live item a chosen library lists was tombstoned", row!!.deletedAtMs)
                }
            }
            checkOwnerChanges(actual.mapValues { it.value.first })
        }

        private fun modelClasses() = libs.mapKeys { it.key.value }.mapValues { it.value.cls to it.value.marked }

        private suspend fun storedClasses() =
            repository.storedLibraries(ALICE).associate {
                val cls =
                    when {
                        it.heldUntilMs != null -> Cls.Held
                        it.addStamp != null -> Cls.Adding
                        else -> Cls.Chosen
                    }
                it.id.value to (cls to it.rewalkPending)
            }

        // The rows the model's observations call for: (item, library) for each library that last saw the item
        // listed, other than its owner.
        private fun modelRows(): Set<Pair<String, String>> {
            val rows = HashSet<Pair<String, String>>()
            for ((library, ns) in listed) {
                if (library !in libs) continue
                for (n in ns) {
                    val owner = items[n]?.owner
                    if (owner != null && owner != library) rows += hexId(n) to library.value
                }
            }
            return rows
        }

        private suspend fun databaseRows() =
            repository
                .alsoListed(ALICE)
                .map { it.first.value to it.second.value }
                .toSet()

        // Each owner change since the last check was versioned, and one of a chosen item moved the generation, which
        // never goes down.
        private suspend fun checkOwnerChanges(owners: Map<String, String>) {
            val generation = repository.contentGeneration(ALICE)
            assertTrue("the generation went down", generation >= lastGeneration)
            val classes = libs.entries.associate { it.key.value to it.value.cls }
            for ((id, owner) in owners) {
                val before = lastOwners[id]
                if (before == null || before == owner) continue
                val versioned = repository.movedVersioned(ALICE, id.toInt(HEX), ItemId(before))
                assertTrue("owner change of $id not versioned", versioned)
                val chosen = lastClasses[before] == Cls.Chosen || classes[owner] == Cls.Chosen
                if (chosen) assertTrue("a chosen item moved with no bump", generation > lastGeneration)
            }
            lastOwners = owners
            lastClasses = classes
            lastGeneration = generation
        }
    }

    private companion object {
        const val RUNS = 500
        const val OPERATIONS = 30
        const val ITEMS = 8
        const val SEED_BASE = 26_000L
        const val STEP_MS = 1_000L
        const val MAX_HOLD_STEPS = 5
        const val HEX = 16
        val PURGE_JUMP_MS = 31.days.inWholeMilliseconds

        val PATHS =
            listOf(
                "held write refused",
                "tombstone takeover on write",
                "chosen takes from adding",
                "non-owner write",
                "reconcile tombstone",
                "reconcile move",
                "reconcile removal half",
                "reconcile takeover",
                "delete cascade",
                "delete move to Chosen",
                "delete move to Adding",
                "delete move to Held",
                "purge",
                "revert",
                "land",
                "marked not reconciled",
            )
    }
}
