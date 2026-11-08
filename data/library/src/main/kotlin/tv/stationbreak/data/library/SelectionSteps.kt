// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.selection.HoldExpiry
import tv.stationbreak.core.domain.selection.ServerListings
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.Session

/**
 * The steps of a stay-current run that reach the library selection (spec 26, Part 4 §4, D11 and D12), through its
 * narrow ports only: the expiry of the holds that ended ([HoldExpiry]) and the following of the run's one listing of
 * the server's libraries ([ServerListings]), and then the rewalk on that same listing. Each step that throws is said
 * by its name only (never a message or an ID) and never changes how the run ended; cancellation and a sign-out pass
 * through, the same as for the passes.
 */
internal class SelectionSteps(
    private val settings: ServerSettings,
    private val server: MediaServer,
    private val listings: ServerListings,
    private val holds: HoldExpiry,
    private val rewalk: Rewalk,
    private val report: (String) -> Unit,
) {
    /**
     * After passes that completed: the holds that ended expire, then the run's listing is followed, then the rewalk
     * walks on that listing. The selection may end gone on it, so what plays is read again first, and the rewalk is
     * skipped while the selection is gone. Gives what the rewalk did, or null when it did not run.
     */
    suspend fun afterPasses(
        session: Session,
        account: LibraryAccount,
    ): RewalkSummary? {
        guarded("HoldExpiry") { holds.expireHolds(account) }
        val listed = follow(session, account)
        val playing = settings.loadPlayableLibraries()?.takeIf { it.isNotEmpty() }
        return when {
            playing == null -> null.also { say("RewalkSkipped reason=Gone") }
            listed == null -> null.also { say("RewalkSkipped reason=NoListing") }
            else -> rewalk.step(session, account, playing, listed)
        }
    }

    /**
     * Lists the server's libraries once and hands the listing to the selection to follow (a failed listing too: the
     * selection drops it). Gives the listing, or null only when reading it threw.
     */
    suspend fun follow(
        session: Session,
        account: LibraryAccount,
    ): ServerResult<List<Library>>? {
        var listed: ServerResult<List<Library>>? = null
        guarded("Listing") {
            val read = server.libraries(session)
            listed = read
            listings.followServer(account, read)
        }
        return listed
    }

    @Suppress("TooGenericExceptionCaught") // Whatever a step throws, the run ends as its passes did.
    private suspend fun guarded(
        step: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (signedOut: AccountNotActiveException) {
            throw signedOut
        } catch (_: Exception) {
            say("${step}Failed")
        }
    }

    // Logging is best effort: a report that fails never changes a step.
    private fun say(line: String) {
        runCatching { report(line) }
    }
}
