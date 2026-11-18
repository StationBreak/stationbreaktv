// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import kotlin.time.Clock

/**
 * The [LineupReadiness] of the built-in lineup: the [service]'s lineup for the account, whether the refresher is
 * [working] ([LineupRefresher.working]), and whether the [schedule] has a channel on the air now, asked as live TV's
 * first tune asks it (a visible channel with a block now). It looks again each time the lineup or [working] changes.
 *
 * - **Ready**: a channel is on the air, whatever else is so (a thin library that never finalises can still air the
 *   viewer's own channels).
 * - **Building**: no lineup yet, the refresher working, or a first lineup that has taken in no collection pass and has
 *   not given up waiting for one ([GIVE_UP_AFTER] failed passes): its first choice is still to come.
 * - **Empty**: otherwise. A thin library planned with its collections, or a finalised lineup with nothing on the air.
 *
 * An account whose IDs are not IDs never has a lineup: Empty. A read that fails counts as nothing on the air, and as
 * not given up.
 */
class BuiltInLineupReadiness(
    private val service: LineupService,
    private val library: LibraryRepository,
    private val schedule: ScheduleSource,
    private val clock: Clock,
    private val working: Flow<Boolean>,
) : LineupReadiness {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun readiness(account: LibraryAccount): Flow<LineupState> {
        if (canonicalAccount(account) == null) return flowOf(LineupState.Empty)
        val held =
            flow {
                service.loaded(account)
                emitAll(service.lineup(account))
            }
        return combine(held, working) { lineup, busy -> lineup to busy }
            .mapLatest { (lineup, busy) -> stateOf(account, lineup, busy) }
            .distinctUntilChanged()
    }

    private suspend fun stateOf(
        account: LibraryAccount,
        held: Revisioned?,
        working: Boolean,
    ): LineupState =
        when {
            onTheAir() -> LineupState.Ready
            held == null || working -> LineupState.Building
            held.waitsForFirstChoice() && !givenUp(account) -> LineupState.Building
            else -> LineupState.Empty
        }

    @Suppress("TooGenericExceptionCaught") // A schedule that cannot be read has nothing on the air.
    private suspend fun onTheAir(): Boolean =
        try {
            val now = clock.now()
            schedule
                .airings(now)
                .filter { it.hidden == null }
                .any { schedule.onAir(it.id, now, 0) != null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    @Suppress("TooGenericExceptionCaught") // A count that cannot be read has not given up: the wait goes on.
    private suspend fun givenUp(account: LibraryAccount): Boolean =
        try {
            library.failedCollectionPasses(account) >= GIVE_UP_AFTER
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
}

// A first lineup that has taken in no collection pass: the planner has not made its first choice yet.
private fun Revisioned.waitsForFirstChoice(): Boolean = !lineup.finalised && meta.collectionPassActedOn == null
