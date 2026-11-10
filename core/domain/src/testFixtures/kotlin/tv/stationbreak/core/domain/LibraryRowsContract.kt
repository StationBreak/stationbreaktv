// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ItemId
import kotlin.time.Instant

/**
 * The contract of the library-row lifecycle on [LibraryRepository] (spec 26, schema 11), run by the tests of every
 * implementation: the fake's and the Room one's. [LibraryRepository.storedLibraries] reads every field of the rows,
 * [LibraryRepository.landAdd] needs the walk's proof, [LibraryRepository.revertUnproven] reverts only an unproven add,
 * and a new stamp or a hold clears the proof. Synthetic IDs only. A failure throws [AssertionError].
 */
object LibraryRowsContract {
    /**
     * One implementation under test, fresh: [repository] with [account] signed in. [proveWalk] records that the add
     * walk of a library under a stamp ended (Room's `markAddWalked`), [saveRow] stores one item in a library being
     * added under a stamp, and [signOut] makes [account] no longer the signed-in one. [other] is an account whose rows
     * [account] must never see.
     */
    class Fixture(
        val repository: LibraryRepository,
        val account: LibraryAccount,
        val other: LibraryAccount,
        val proveWalk: suspend (library: ItemId, stamp: Long) -> Unit,
        val saveRow: suspend (library: ItemId, stamp: Long) -> Unit,
        val signOut: suspend () -> Unit,
    )

    val CHOSEN = ItemId("c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c1")
    val ADDING = ItemId("c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c2")
    val HELD = ItemId("c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c3")
    val LANDED = ItemId("c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c4")
    val SECOND = ItemId("c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c5")

    private const val NOW = 1_000L
    private const val HELD_UNTIL = 60_000L

    /** Runs every case, each on a fresh fixture from [fresh]. */
    suspend fun check(fresh: suspend () -> Fixture) {
        storedLibrariesReadEveryFieldOfTheAccountOnly(fresh())
        landAddNeedsTheProofAndLandsOnce(fresh())
        aNewStampOrAHoldClearsTheProof(fresh())
        revertUnprovenRevertsOnlyAnUnprovenAdd(fresh())
        revertUnprovenBumpsOnlyWhenTheLibraryHoldsRows(fresh())
        revertUnprovenOfAnAccountNotSignedInChangesNothing(fresh())
        ruleSnapshotsAreOneRuleSnapshotPerSelection(fresh())
    }

    private fun stored(
        id: ItemId,
        fetched: Boolean = false,
        addStamp: Long? = null,
        walkedStamp: Long? = null,
        heldUntilMs: Long? = null,
        rewalkPending: Boolean = false,
    ) = StoredLibrary(id, fetched, addStamp, walkedStamp, heldUntilMs, rewalkPending)

    private suspend fun Fixture.rows() = repository.storedLibraries(account).associateBy { it.id }

    // Lands [LANDED] (stamped [stamp] in the selection just applied), so the generation has left its first value: an
    // implementation may seed a new counter from its clock, so "moved by one" is measured only after a first move.
    private suspend fun Fixture.landFirst(stamp: Long) {
        proveWalk(LANDED, stamp)
        expect(repository.landAdd(account, LANDED, stamp), "a proven add lands")
    }

    private suspend fun storedLibrariesReadEveryFieldOfTheAccountOnly(f: Fixture) {
        val repo = f.repository
        repo.applySelection(
            f.account,
            setOf(CHOSEN, HELD, LANDED, ADDING),
            mapOf(LANDED to 3L, ADDING to 5L),
            nowMs = NOW,
        )
        f.proveWalk(LANDED, 3L)
        expect(repo.landAdd(f.account, LANDED, 3L), "a proven add lands")
        repo.applySelection(f.account, setOf(CHOSEN, LANDED, ADDING), holds = mapOf(HELD to HELD_UNTIL), nowMs = NOW)
        f.proveWalk(ADDING, 5L)
        repo.applySelection(f.other, setOf(ItemId("d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d1")), nowMs = NOW)

        val got = repo.storedLibraries(f.account)
        val want =
            listOf(
                stored(CHOSEN),
                stored(ADDING, addStamp = 5L, walkedStamp = 5L),
                stored(HELD, heldUntilMs = HELD_UNTIL),
                stored(LANDED, fetched = true),
            )
        expect(got == want, "every row and field, in ID order, the account's own only: $got")

        expect(repo.revertUnproven(f.account, ADDING, 4L).not(), "another stamp is not reverted")
        f.repository.applySelection(f.account, setOf(CHOSEN, LANDED, ADDING), mapOf(ADDING to 6L), nowMs = NOW)
        expect(repo.revertUnproven(f.account, ADDING, 6L), "an unproven add is reverted")
        val reverted = f.rows()[ADDING]
        expect(reverted == stored(ADDING, rewalkPending = true), "pending, unstamped and marked: $reverted")
    }

    private suspend fun landAddNeedsTheProofAndLandsOnce(f: Fixture) {
        val repo = f.repository
        repo.applySelection(f.account, setOf(CHOSEN, ADDING, LANDED), mapOf(ADDING to 7L, LANDED to 1L), nowMs = NOW)
        f.landFirst(1L)
        val before = repo.contentGeneration(f.account)

        expect(!repo.landAdd(f.account, ADDING, 7L), "an add whose walk was not proven does not land")
        expect(repo.contentGeneration(f.account) == before, "and moves nothing")
        f.proveWalk(ADDING, 7L)
        expect(!repo.landAdd(f.account, ADDING, 6L), "another stamp does not land")
        expect(repo.landAdd(f.account, ADDING, 7L), "the proven stamp lands")
        expect(repo.contentGeneration(f.account) == before + 1, "once")
        expect(f.rows()[ADDING] == stored(ADDING, fetched = true), "stamp and proof cleared, fetched: ${f.rows()}")
        expect(!repo.landAdd(f.account, ADDING, 7L), "a second landing finds no stamp")
        expect(repo.contentGeneration(f.account) == before + 1, "and moves nothing")
    }

    private suspend fun aNewStampOrAHoldClearsTheProof(f: Fixture) {
        val repo = f.repository
        repo.applySelection(f.account, setOf(CHOSEN, ADDING), mapOf(ADDING to 7L), nowMs = NOW)
        f.proveWalk(ADDING, 7L)
        repo.applySelection(f.account, setOf(CHOSEN, ADDING), nowMs = NOW)
        expect(f.rows()[ADDING]?.walkedStamp == 7L, "the same stamp, or none, keeps the proof")

        repo.applySelection(f.account, setOf(CHOSEN, ADDING), mapOf(ADDING to 8L), nowMs = NOW)
        expect(f.rows()[ADDING] == stored(ADDING, addStamp = 8L), "a new stamp clears the proof: ${f.rows()}")
        expect(!repo.landAdd(f.account, ADDING, 8L), "so the new stamp cannot land yet")

        f.proveWalk(ADDING, 8L)
        repo.applySelection(f.account, setOf(CHOSEN), holds = mapOf(ADDING to HELD_UNTIL), nowMs = NOW)
        val held = f.rows()[ADDING]
        expect(held == stored(ADDING, heldUntilMs = HELD_UNTIL), "a hold clears the stamp and the proof: $held")
    }

    private suspend fun revertUnprovenRevertsOnlyAnUnprovenAdd(f: Fixture) {
        val repo = f.repository
        repo.applySelection(f.account, setOf(CHOSEN, ADDING, LANDED), mapOf(ADDING to 7L, LANDED to 9L), nowMs = NOW)
        f.proveWalk(LANDED, 9L)

        expect(!repo.revertUnproven(f.account, LANDED, 9L), "a proven add is left to land")
        expect(f.rows()[LANDED] == stored(LANDED, addStamp = 9L, walkedStamp = 9L), "untouched: ${f.rows()}")
        expect(!repo.revertUnproven(f.account, ADDING, 6L), "another stamp is left alone")
        expect(!repo.revertUnproven(f.account, CHOSEN, 7L), "a library with no stamp is left alone")
        expect(!repo.revertUnproven(f.account, ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e9"), 7L), "no such library")
        expect(repo.revertUnproven(f.account, ADDING, 7L), "the unproven add is reverted")
        expect(f.rows()[ADDING] == stored(ADDING, rewalkPending = true), "pending and marked: ${f.rows()}")
        expect(ADDING in repo.pendingLibraries(f.account), "it is pending again")
        expect(!repo.revertUnproven(f.account, ADDING, 7L), "a second revert finds no stamp")

        repo.applySelection(f.account, setOf(CHOSEN, LANDED, HELD), mapOf(HELD to 4L), nowMs = NOW)
        repo.applySelection(f.account, setOf(CHOSEN, LANDED), holds = mapOf(HELD to HELD_UNTIL), nowMs = NOW)
        expect(!repo.revertUnproven(f.account, HELD, 4L), "a held library has no stamp to revert")
        expect(f.rows()[ADDING] == null, "the reverted add left keep and was deleted, mark and all")
    }

    private suspend fun revertUnprovenBumpsOnlyWhenTheLibraryHoldsRows(f: Fixture) {
        val repo = f.repository
        val stamps = mapOf(ADDING to 7L, SECOND to 8L, LANDED to 1L)
        repo.applySelection(f.account, setOf(CHOSEN, ADDING, LANDED, SECOND), stamps, nowMs = NOW)
        f.landFirst(1L)
        f.saveRow(SECOND, 8L)
        val before = repo.contentGeneration(f.account)

        expect(repo.revertUnproven(f.account, ADDING, 7L), "an empty add is reverted")
        expect(repo.contentGeneration(f.account) == before, "with no rows nothing moves")
        expect(repo.revertUnproven(f.account, SECOND, 8L), "an add that wrote a row is reverted")
        expect(repo.contentGeneration(f.account) == before + 1, "its rows are a chosen library's now: one bump")
    }

    private suspend fun revertUnprovenOfAnAccountNotSignedInChangesNothing(f: Fixture) {
        val repo = f.repository
        repo.applySelection(f.account, setOf(CHOSEN, ADDING), mapOf(ADDING to 7L), nowMs = NOW)
        f.signOut()
        expect(!repo.revertUnproven(f.account, ADDING, 7L), "an account that is not signed in reverts nothing")
    }

    // #684: the walk's list is one snapshot per selection, in order, each what `ruleSnapshot` gives for it (an empty
    // selection and a repeated one included). The row data of the equality is the implementations' own property tests'.
    private suspend fun ruleSnapshotsAreOneRuleSnapshotPerSelection(f: Fixture) {
        val repo = f.repository
        repo.applySelection(f.account, setOf(CHOSEN, HELD), nowMs = NOW)
        val cutoff = Instant.fromEpochMilliseconds(NOW)
        val selections = listOf(setOf(CHOSEN, HELD), setOf(CHOSEN), emptySet(), setOf(CHOSEN, HELD), setOf(HELD))

        val walked = repo.ruleSnapshots(f.account, selections, cutoff)

        expect(walked.size == selections.size, "one snapshot per selection, in order: ${walked.size}")
        for ((i, selection) in selections.withIndex()) {
            expect(walked[i] == repo.ruleSnapshot(f.account, selection, cutoff), "element $i is its own ruleSnapshot")
        }
        expect(walked[2].items.isEmpty() && walked[2].libraries.isEmpty(), "an empty selection holds nothing")
        expect(repo.ruleSnapshots(f.account, emptyList(), cutoff).isEmpty(), "no selections, no snapshots")
    }

    private fun expect(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw AssertionError(message)
    }
}
