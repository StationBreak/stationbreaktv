// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

/**
 * Asks for a stay-current run of the library sync soon (spec 26): the selection asks after it reverts an unproven add,
 * so the marked library is read again without waiting for the next open. Only a request: it enqueues and never waits,
 * and asking again before the run starts is one run. No WorkManager type appears here (ADR-0004); the app binds it.
 */
fun interface StayCurrentRequests {
    /** Asks for a run. Safe to call from any thread, any number of times. */
    fun request()
}
