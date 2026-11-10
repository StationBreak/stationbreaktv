// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.CancellationException
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.selection.HoldExpiry
import tv.stationbreak.core.domain.selection.ServerListings
import tv.stationbreak.core.model.Library
import tv.stationbreak.core.model.Session

// What a run does for the server's listing after its passes: list it and hand it to the selection to follow
// ([Follow]), or use the one its caller already listed and handed over ([Listed], null when there is none).
internal sealed interface AfterPasses {
    data object Follow : AfterPasses

    data class Listed(
        val listed: ServerResult<List<Library>>?,
        val listedFor: LibraryAccount?,
    ) : AfterPasses
}

// What the after-passes hook gave: the listing the rewalk walks on (null when there is none), or that a sign-out landed
// inside it.
internal class Hooked(
    val listed: ServerResult<List<Library>>?,
    val signedOut: Boolean = false,
)

/**
 * The steps of a stay-current run that reach the library selection (spec 26, Part 4 §4, D11 and D12), through its
 * narrow ports only: the expiry of the holds that ended ([HoldExpiry]) and the following of the run's one listing of
 * the server's libraries ([ServerListings]), and then the rewalk on that same listing. Each step that throws is said
 * by its name only (never a message or an ID) and never changes how the run ended; cancellation and a sign-out pass
 * through, the same as for the passes.
 */
internal class SelectionSteps(
    private val settings: ServerSettings,
    private val listings: ServerListings,
    private val holds: HoldExpiry,
    private val rewalkStep: Rewalk,
    private val report: (String) -> Unit,
) {
    /**
     * The after-passes hook for [after] (the runner decides when): the holds that ended expire, then the run's
     * [listing] is followed, or the one its caller already listed is used when it is for this [account] (a listing made
     * for another account is not walked on, and the run is not failed for it). A sign-out that lands inside the hook
     * skips what is left of it and says so in [Hooked.signedOut].
     */
    suspend fun afterPasses(
        after: AfterPasses,
        account: LibraryAccount,
        listing: RunListing,
    ): Hooked =
        try {
            when (after) {
                AfterPasses.Follow -> {
                    expire(account)
                    Hooked(follow(account, listing))
                }

                is AfterPasses.Listed -> {
                    expire(account)
                    if (after.listedFor == null || after.listedFor == account) {
                        Hooked(after.listed)
                    } else {
                        say("ListingAccountMismatch")
                        Hooked(null)
                    }
                }
            }
        } catch (_: AccountNotActiveException) {
            Hooked(null, signedOut = true)
        }

    /** After the passes, for a run its caller already listed for (Sync now): only the holds that ended expire. */
    private suspend fun expire(account: LibraryAccount) {
        guarded("HoldExpiry") { holds.expireHolds(account) }
    }

    /**
     * The rewalk on the run's listing [listed], once the whole run completed. The selection may have ended gone on
     * that listing, so what plays is read again first, and the rewalk is skipped while it is gone. Gives what the
     * rewalk did, or null when it did not run.
     */
    suspend fun rewalk(
        session: Session,
        account: LibraryAccount,
        listed: ServerResult<List<Library>>?,
    ): RewalkSummary? {
        val playing = settings.loadPlayableLibraries()
        return when {
            playing == null -> null.also { say("RewalkSkipped reason=Gone") }
            playing.isEmpty() -> null.also { say("RewalkSkipped reason=NoLibraries") }
            listed == null -> null.also { say("RewalkSkipped reason=NoListing") }
            else -> rewalkStep.step(session, account, playing, listed)
        }
    }

    /**
     * Hands the run's [listing] to the selection to follow (a failed listing too: the selection drops it). The listing
     * is the run's one: read now only if no pass has read it. Gives the listing, or null only when reading it threw.
     */
    suspend fun follow(
        account: LibraryAccount,
        listing: RunListing,
    ): ServerResult<List<Library>>? {
        var listed: ServerResult<List<Library>>? = null
        guarded("Listing") {
            val read = listing.get()
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
