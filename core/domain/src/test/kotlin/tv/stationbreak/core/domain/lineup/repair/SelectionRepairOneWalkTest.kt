// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.WalkClock
import tv.stationbreak.core.domain.lineup.CountingLibrary
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.RuleSnapshot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * #684 PR 4: a selection change reads the library in one walk per cut-off, and every decorator that counts or
 * intercepts the library's reads sees that read (a test double that overrode only `ruleSnapshot` would go blind to it).
 */
internal class SelectionRepairOneWalkTest {
    private val repaired = RepairMatrix.scenarios().first { "second untick on repaired days" in it.name }
    private val firstUntick = RepairMatrix.scenarios().first { "untick the 250-item library, days laid out" in it.name }

    private fun worldOf(
        scenario: Scenario,
        planner: Planner? = null,
    ) = RepairWorld(scenario.libs, scenario.shape, firstSeenFilter = true, planner = planner)

    @Test
    fun aRepairOfRepairedDays_readsTheNewAndTheCurrentSelectionInOneWalk() =
        runBlocking {
            val world = worldOf(repaired)
            world.(repaired.body)()
            val sizes = world.library.walkSizes

            assertTrue(world.library.walks > 0, "the counting library saw the repair's walks")
            assertEquals(world.library.walks, sizes.size)
            // The second untick's walks hold the new and the current selection together (and the removed libraries
            // when a programme is on air at the change).
            assertTrue(sizes.any { it >= 2 }, "a walk read the new and the current selection together: $sizes")
        }

    @Test
    fun aFirstUntick_readsTheNewSelectionInTheWalkAlone_orWithTheRemovedOnes() =
        runBlocking {
            val world = worldOf(firstUntick)
            world.(firstUntick.body)()

            assertTrue(world.library.walks > 0, "the counting library saw the repair's walks")
            assertTrue(world.library.walkSizes.all { it <= 2 }, "no current selection read: ${world.library.walkSizes}")
        }

    @Test
    fun theWalksTransactionTime_reachesTheRepairClock() =
        runBlocking {
            val clock = RepairClock()
            val reporting =
                { inputs: PlanInputs ->
                    PlanInputs(
                        inputs.days,
                        object : LibraryRepository by inputs.library {
                            override suspend fun ruleSnapshots(
                                account: LibraryAccount,
                                selections: List<Set<ItemId>>,
                                cutoff: Instant,
                            ): List<RuleSnapshot> {
                                currentCoroutineContext()[WalkClock]?.addTx(TX_NANOS)
                                return inputs.library.ruleSnapshots(account, selections, cutoff)
                            }
                        },
                        inputs.account,
                        inputs.held,
                        inputs.change,
                        inputs.shared,
                        inputs.today,
                    )
                }
            val planner: Planner = { inputs ->
                val with = reporting(inputs)
                SelectionRepair(with.days, with.library, with.account, with.held, with.change, with.shared, clock)
                    .plan(with.today)
            }
            val world = worldOf(firstUntick, planner)
            world.(firstUntick.body)()

            val phases = clock.phases()
            assertTrue(phases.walkTxMs >= 1, "the walk's transaction time was recorded: ${phases.line()}")
            assertTrue(phases.line().endsWith("walkTxMs=${phases.walkTxMs}"), "it is the last field: ${phases.line()}")
        }

    @Test
    fun aLibraryThatReportsNoTransaction_leavesTheFieldUnmeasured() =
        runBlocking {
            val clock = RepairClock()
            val planner: Planner = { inputs ->
                val repair =
                    SelectionRepair(
                        inputs.days,
                        inputs.library,
                        inputs.account,
                        inputs.held,
                        inputs.change,
                        inputs.shared,
                        clock,
                    )
                repair.plan(inputs.today)
            }
            val world = worldOf(firstUntick, planner)
            world.(firstUntick.body)()

            assertEquals(-1, clock.phases().walkTxMs)
        }

    @Test
    fun everyTestDecoratorThatInterceptsRuleSnapshot_interceptsTheWalkToo() {
        // `LibraryRepository by inner` hands `ruleSnapshots` straight on, so a decorator that counts, gates or fails
        // `ruleSnapshot` would not see a repair's read unless it overrides the walk as well. Every class of the test
        // sources that overrides one overrides the other.
        val root =
            File(
                CountingLibrary::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val loader = CountingLibrary::class.java.classLoader
        val decorators =
            root
                .walkTopDown()
                .filter { it.extension == "class" }
                .map {
                    it
                        .relativeTo(root)
                        .path
                        .removeSuffix(".class")
                        .replace(File.separatorChar, '.')
                }.map { Class.forName(it, false, loader) }
                .filter { LibraryRepository::class.java.isAssignableFrom(it) && !it.isInterface }
                .toList()
        assertTrue(decorators.size > 5, "found the test doubles: ${decorators.map { it.simpleName }}")

        val declared = declaredMembers(root, decorators, setOf("ruleSnapshot", "ruleSnapshots"))
        val blind =
            decorators.map { it.name }.filter {
                "ruleSnapshot" in declared[it].orEmpty() && "ruleSnapshots" !in declared[it].orEmpty()
            }

        assertEquals(emptyList(), blind, "these intercept ruleSnapshot but not ruleSnapshots")
    }

    /**
     * Which of [names] each of [types] declares itself, by class name. `by inner` makes the compiler write a forwarding
     * method for every member the class does not declare, so the methods of the class cannot tell a declared override
     * from a forward; the forward's body reads the `$$delegate_0` field, and `javap` (of the JDK that runs the test)
     * shows it. [root] is where the classes are.
     */
    private fun declaredMembers(
        root: File,
        types: List<Class<*>>,
        names: Set<String>,
    ): Map<String, Set<String>> {
        val javap = File(System.getProperty("java.home"), "bin/javap")
        assertTrue(javap.canExecute(), "javap is part of the JDK that runs this test: $javap")
        val files = types.map { File(root, it.name.replace('.', File.separatorChar) + ".class").path }
        val process = ProcessBuilder(listOf(javap.path, "-c", "-p") + files).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val result = HashMap<String, Set<String>>()
        // One section per class, each starting at its "Compiled from" line; one block per member (a blank line apart).
        for (section in text.split("Compiled from").drop(1)) {
            val className = CLASS_NAME.find(section)?.groupValues?.get(1) ?: continue
            val found = HashSet<String>()
            for (member in section.split("\n\n")) {
                val head = member.lineSequence().firstOrNull { "(" in it }.orEmpty()
                val name = METHOD_NAME.find(head)?.groupValues?.get(1)
                if (name in names && "\$\$delegate_0" !in member) found += checkNotNull(name)
            }
            result[className] = found
        }
        return result
    }

    private companion object {
        const val TX_NANOS = 5_000_000L
        val CLASS_NAME = Regex("""(?:class|interface) ([\w.$]+)""")
        val METHOD_NAME = Regex("""\s([A-Za-z0-9_]+)\(""")
    }
}
