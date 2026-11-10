// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.lineup.ACCOUNT
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.domain.lineup.repair.RepairMatrix.referenceOf
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The lanes of the plan (#684 PR 3): the walk, the two builds and the early current-row read run side by side, and
 * the repair records the same bytes whatever the order the lanes run in or the threads they run on (the equality
 * matrix of [RepairMatrix], against the frozen reference), every lane's work goes through the planning dispatcher,
 * the early read answers the tail pools' own, and a sync that lands since the early read is not missed.
 */
internal class SelectionRepairLanesTest {
    // A planning dispatcher with one worker that runs a ready task chosen at random, not the oldest: the lanes (and
    // everything else the plan launches) run in an order of the seed's making. Counts what it was given.
    // A pool of [threads] named `lanes-test-pool`, counting what it is given.
    private class Counting(
        threads: Int,
    ) : CoroutineDispatcher(),
        AutoCloseable {
        private val pool =
            Executors.newFixedThreadPool(threads) { Thread(it, "lanes-test-pool").apply { isDaemon = true } }
        val dispatched = AtomicInteger()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatched.incrementAndGet()
            pool.execute(block)
        }

        override fun close() {
            pool.shutdown()
        }
    }

    private class Shuffling(
        seed: Long,
    ) : CoroutineDispatcher(),
        AutoCloseable {
        private val random = Random(seed)
        private val lock = ReentrantLock()
        private val ready = lock.newCondition()
        private val queue = ArrayList<Runnable>()
        private var open = true
        val dispatched = AtomicInteger()

        init {
            Thread({ work() }, "shuffled-plan").apply { isDaemon = true }.start()
        }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatched.incrementAndGet()
            lock.withLock {
                queue += block
                ready.signal()
            }
        }

        private fun work() {
            while (true) {
                val next =
                    lock.withLock {
                        while (queue.isEmpty() && open) ready.await()
                        if (!open) return
                        queue.removeAt(random.nextInt(queue.size))
                    }
                next.run()
            }
        }

        override fun close() {
            lock.withLock {
                open = false
                ready.signalAll()
            }
        }
    }

    private fun repairOf(inputs: PlanInputs) =
        SelectionRepair(inputs.days, inputs.library, inputs.account, inputs.held, inputs.change, inputs.shared)

    private fun runWith(
        scenario: Scenario,
        planning: CoroutineDispatcher,
        library: CountingLibrary? = null,
    ): List<Step> =
        runBlocking {
            val world =
                RepairWorld(
                    scenario.libs,
                    scenario.shape,
                    library ?: seededLibrary(scenario.libs),
                    firstSeenFilter = true,
                    planning = planning,
                )
            world.(scenario.body)()
            world.steps
        }

    private fun mismatches(planning: () -> CoroutineDispatcher): List<String> =
        RepairMatrix.scenarios().mapNotNull { scenario ->
            val dispatcher = planning()
            try {
                val actual = runCatching { runWith(scenario, dispatcher) }
                when {
                    actual.isFailure -> "${scenario.name}: threw ${actual.exceptionOrNull()}"
                    actual.getOrThrow() != referenceOf(scenario.name) -> "${scenario.name}: differs from the reference"
                    else -> null
                }
            } finally {
                (dispatcher as? AutoCloseable)?.close()
            }
        }

    @Test
    fun onTwoPlanningThreads_theRepairerRecordsWhatTheReferenceRecords() {
        val failures = mismatches { planningDispatcher(RepairConfig(planThreads = 2)) }

        assertTrue(failures.isEmpty(), "two threads differ from the reference:\n${failures.joinToString("\n")}")
    }

    @Test
    fun underADispatcherThatRunsReadyTasksInARandomOrder_theRepairerRecordsWhatTheReferenceRecords() {
        for (seed in listOf(1L, 2L, 3L)) {
            val failures = mismatches { Shuffling(seed) }

            assertTrue(failures.isEmpty(), "seed $seed differs from the reference:\n${failures.joinToString("\n")}")
        }
    }

    @Test
    fun everyLanesWork_goesThroughThePlanningDispatcher() {
        val scenario = RepairMatrix.scenarios().first { it.name == "catalog: second untick on repaired days" }
        val dispatcher = Counting(2)
        val library = seededLibrary(scenario.libs)
        val inPlan = AtomicBoolean()
        val threads = Collections.synchronizedSet(HashSet<String>())
        val reads = AtomicInteger()

        fun read() {
            if (inPlan.get()) {
                reads.incrementAndGet()
                threads += Thread.currentThread().name.substringBefore(" @")
            }
        }
        library.afterSnapshot = { read() }
        library.afterCurrentIds = { read() }
        // The turn's own `withContext(planning)` around the plan, as the repairer has it, with the plan marked.
        val planner: Planner = { inputs ->
            withContext(dispatcher) {
                inPlan.set(true)
                try {
                    repairOf(inputs).plan(inputs.today)
                } finally {
                    inPlan.set(false)
                }
            }
        }

        try {
            runBlocking {
                val world = RepairWorld(scenario.libs, scenario.shape, library, planner, firstSeenFilter = true)
                world.(scenario.body)()
            }
        } finally {
            dispatcher.close()
        }

        // Every library read of the plan (the walk's, the early read's, the kept windows' and the pools') ran on the
        // pool's threads; the walk and the early read are lanes, so none was started on a context of its own. Two
        // changes: each dispatches the turn's `withContext`, three lanes (four with a repaired day) and their resumes.
        assertTrue(reads.get() > 0, "the plan read the library")
        assertEquals(setOf("lanes-test-pool"), threads, "reads ran on")
        assertTrue(dispatcher.dispatched.get() >= MIN_DISPATCHES, "dispatched ${dispatcher.dispatched.get()}")
    }

    @Test
    fun theTailPoolsCurrentRowRead_isAnsweredByTheEarlyRead_inEveryScenario() {
        val misses = AtomicInteger()
        val planner: Planner = { inputs ->
            val repair = repairOf(inputs)
            repair.plan(inputs.today).also { misses.addAndGet(repair.primeMisses) }
        }

        val failures = RepairMatrix.mismatches(planner)

        assertTrue(failures.isEmpty(), "the plan differs from the reference:\n${failures.joinToString("\n")}")
        assertEquals(0, misses.get(), "a tail pool item was outside the early read's ids: the guard of #684 M1")
    }

    // The tail's items of the repairs a step recorded: those airing from the pin's end.
    private fun tailItems(step: Step): Set<CanonicalId> =
        step.record.selection
            .flatMap { repair -> repair.blocks.filter { it.startMs >= repair.pinEndMs } }
            .mapNotNullTo(HashSet()) { it.itemId }

    private val untickScenario =
        RepairMatrix.scenarios().first { it.name == "catalog: untick the 250-item library, days laid out" }

    // The sizes of the control run's current-row reads, and one item its tail airs.
    private val control: Pair<Int, CanonicalId> by lazy {
        val library = seededLibrary(untickScenario.libs)
        val steps = runWith(untickScenario, planningDispatcher(RepairConfig(planThreads = 1)), library)
        library.currentIdsSizes.max() to tailItems(steps.last()).minBy { it.value }
    }

    @Test
    fun aSyncThatLandsAfterTheEarlyRead_dropsThePrimedAnswers_andTheTailLeavesTheDeletedItemOut() {
        val (earlySize, victim) = control
        val library = seededLibrary(untickScenario.libs)
        val fired = AtomicInteger()
        library.afterCurrentIds = { size ->
            // The early read is the biggest; the sync deletes an item of the tail and moves the content generation.
            if (size == earlySize && fired.getAndIncrement() == 0) {
                library.delegate.tombstone(ACCOUNT, listOf(ItemId(victim.value)))
            }
        }

        val steps = runWith(untickScenario, planningDispatcher(RepairConfig(planThreads = 2)), library)

        assertEquals(1, fired.get(), "the early read was found by its size")
        assertTrue(victim !in tailItems(steps.last()), "the tail airs an item the sync deleted")
        assertNotEquals(tailItems(referenceOf(untickScenario.name).last()), tailItems(steps.last()))
    }

    @Test
    fun aSyncThatMovesOnlyTheGeneration_leavesTheRecordAsTheReferenceHasIt() {
        val (earlySize, _) = control
        val library = seededLibrary(untickScenario.libs)
        val fired = AtomicInteger()
        library.afterCurrentIds = { size ->
            if (size == earlySize && fired.getAndIncrement() == 0) library.delegate.noteContentChange(ACCOUNT)
        }
        val reads = library.currentIdsCalls

        val steps = runWith(untickScenario, planningDispatcher(RepairConfig(planThreads = 2)), library)

        assertEquals(1, fired.get())
        assertEquals(referenceOf(untickScenario.name).map { it.record }, steps.map { it.record })
        assertEquals(referenceOf(untickScenario.name).map { it.pins }, steps.map { it.pins })
        assertTrue(library.currentIdsCalls > reads, "the guard read again")
    }

    private companion object {
        const val MIN_DISPATCHES = 9
    }
}
