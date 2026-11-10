// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.repair

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The dispatcher a repairer plans a selection change on (#684): a fixed pool of [RepairConfig.planThreads] daemon
 * threads named `repair-plan-N`, at [RepairConfig.planPriority]. A pool's thread starts when a change first needs it
 * and then waits idle, so nothing runs on it between changes. Made once per repairer.
 */
internal fun planningDispatcher(config: RepairConfig): CoroutineDispatcher {
    val numbered = AtomicInteger()
    return Executors
        .newFixedThreadPool(config.planThreads) { task ->
            Thread(task, "repair-plan-${numbered.incrementAndGet()}").apply {
                isDaemon = true
                priority = config.planPriority
            }
        }.asCoroutineDispatcher()
}
