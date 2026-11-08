// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.StoredLibrary
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.Session
import kotlin.time.Clock

// How one library's rewalk ended: walked to its end, stopped by a first sync that began, unmarked meanwhile (by
// another walk's end, or the library left the choice), or failed (for this library alone, or for every library after
// it too: the session or the network).
private enum class Rewalked {
    Walked,
    FirstSyncBegan,
    Unmarked,
    Failed,
    FailedAll,
}

/**
 * What a rewalk step did (spec 26, Part 4 §4), counts only: the chosen libraries [marked] for a rewalk, those [walked]
 * to their end, those the listing left out ([unlisted], kept marked), those [unmarked] while they were walked (by
 * another walk's end, or because they left the choice: nothing of them was this step's to finish), those that
 * [failed] (kept marked, for the next run), and whether a first sync that began [stopped] it.
 */
internal data class RewalkSummary(
    val marked: Int = 0,
    val walked: Int = 0,
    val unlisted: Int = 0,
    val unmarked: Int = 0,
    val failed: Int = 0,
    val stopped: Boolean = false,
)

/**
 * The rewalk step of a stay-current run (spec 26, Part 4 §4, Amendments 3 and 4). The candidates are the chosen
 * libraries marked for a rewalk (an add whose walk was never proven, set back) with no stamp and no hold; a library a
 * setup left pending and unmarked is the first sync's. Each one the server lists is walked from its checkpoint with no
 * stamp ([LibraryPass] with `rewalk`), and `finishRewalk` then clears its mark and writes its coverage. Every rewalk
 * write checks, inside its transaction, that no first sync is running: when one began, the step stops and that sync
 * takes the library. Results are per library: one that fails, or that the listing leaves out, keeps its mark for the
 * next run.
 */
internal class Rewalk(
    private val repository: RoomLibraryRepository,
    private val server: MediaServer,
    private val clock: Clock,
    private val report: (String) -> Unit,
) {
    /**
     * Runs the step for [account]'s [chosen] libraries and says what it did, in a numbers-only line when there was
     * anything to rewalk. Whatever else fails is one failure, and never fails the run; cancellation and a sign-out pass
     * through, as for the passes.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun step(
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ): RewalkSummary {
        val summary =
            try {
                run(session, account, chosen)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (signedOut: AccountNotActiveException) {
                throw signedOut
            } catch (_: Exception) {
                RewalkSummary(failed = 1)
            }
        if (summary.marked > 0 || summary.failed > 0) {
            val line =
                "Rewalk marked=${summary.marked} walked=${summary.walked} unlisted=${summary.unlisted} " +
                    "unmarked=${summary.unmarked} failed=${summary.failed} stopped=${summary.stopped}"
            // Logging is best effort: a report that fails never changes the step.
            runCatching { report(line) }
        }
        return summary
    }

    @Suppress("ReturnCount") // Each return is where the step stops.
    private suspend fun run(
        session: Session,
        account: LibraryAccount,
        chosen: Set<ItemId>,
    ): RewalkSummary {
        val marked = repository.storedLibraries(account).filter { it.isCandidate(chosen) }
        if (marked.isEmpty()) return RewalkSummary()
        val listed =
            when (val result = server.libraries(session)) {
                is ServerResult.Success -> result.value.associateBy { it.id }
                is ServerResult.Failure -> return RewalkSummary(marked.size, failed = marked.size)
            }
        var summary = RewalkSummary(marked.size)
        for (row in marked) {
            // Not listed: kept marked; it leaves the choice, and its mark, only by the selection's confirmed miss.
            val library = listed[row.id]
            if (library == null) {
                summary = summary.copy(unlisted = summary.unlisted + 1)
                continue
            }
            when (rewalkOne(session, account, library)) {
                Rewalked.Walked -> {
                    summary = summary.copy(walked = summary.walked + 1)
                }

                Rewalked.FirstSyncBegan -> {
                    return summary.copy(stopped = true)
                }

                Rewalked.Unmarked -> {
                    summary = summary.copy(unmarked = summary.unmarked + 1)
                }

                Rewalked.Failed -> {
                    summary = summary.copy(failed = summary.failed + 1)
                }

                // The session or the network failed: the libraries after this one would fail too; the next run tries.
                Rewalked.FailedAll -> {
                    return summary.copy(failed = summary.failed + 1)
                }
            }
        }
        return summary
    }

    // A chosen library marked for a rewalk, with no stamp and no hold.
    private fun StoredLibrary.isCandidate(chosen: Set<ItemId>): Boolean {
        val free = addStamp == null && heldUntilMs == null
        return id in chosen && rewalkPending && free
    }

    // One marked library's rewalk, from its checkpoint with no stamp, and its end.
    private suspend fun rewalkOne(
        session: Session,
        account: LibraryAccount,
        library: Library,
    ): Rewalked {
        val pass = LibraryPass.rewalk(repository, server, session, account, report)
        return when (val end = pass.walk(library, null, clock.now())) {
            is PassEnd.Walked -> {
                when (repository.finishRewalk(account, library.id, end.run)) {
                    RewalkFinish.Finished -> Rewalked.Walked

                    RewalkFinish.FirstSyncRunning -> Rewalked.FirstSyncBegan

                    // Unmarked meanwhile: nothing of it was this step's to finish.
                    RewalkFinish.NotMarked -> Rewalked.Unmarked
                }
            }

            is PassEnd.Refused -> {
                refused(account, library, end.result)
            }

            is PassEnd.Failed -> {
                val failure = end.error.toSyncFailure()
                val all = failure == SyncFailure.Network || failure == SyncFailure.SessionExpired
                if (all) Rewalked.FailedAll else Rewalked.Failed
            }
        }
    }

    // A rewalk write the cache refused: a first sync began, or the row lost its mark (it is unmarked, or gone with
    // the choice), or something else about the row changed (held, stamped again), which the next run reads again.
    private suspend fun refused(
        account: LibraryAccount,
        library: Library,
        result: PageResult,
    ): Rewalked {
        if (result is PageResult.FirstSyncRunning) return Rewalked.FirstSyncBegan
        val row = repository.storedLibraries(account).firstOrNull { it.id == library.id }
        return if (row?.rewalkPending == true) Rewalked.Failed else Rewalked.Unmarked
    }
}
