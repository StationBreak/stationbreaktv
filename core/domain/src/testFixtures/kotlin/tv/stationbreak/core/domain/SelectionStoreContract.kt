// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress

/**
 * The contract of the stored selection on [SelectionStore] (spec 26), run by the tests of every implementation: the
 * file store's and the in-memory one's. It covers the setup-only rule (with nothing chosen only a setup's save is
 * taken), the kept seq, and a choice belonging to its address. Synthetic IDs only. A failure throws [AssertionError].
 */
object SelectionStoreContract {
    private val ADDRESS = ServerAddress(Scheme.Https, "nas.example.test", 8920)
    private val OTHER = ServerAddress(Scheme.Https, "other.example.test", 443)
    private val ALPHA = ItemId("alpha")
    private val BETA = ItemId("beta")

    /** Runs every case, each on a fresh, empty store from [fresh]. */
    suspend fun check(fresh: suspend () -> SelectionStore) {
        aSaveNeedsARecord(fresh())
        withNothingChosenOnlyASetupSaves(fresh())
        aSetupSavesAndAChangeFollows(fresh())
        afterAClearOnlyASetupSavesAndTheSeqIsKept(fresh())
        anotherAddressDropsTheChoiceAndOnlyASetupSavesAgain(fresh())
    }

    private suspend fun SelectionStore.withRecord(): SelectionStore {
        expect(save(ServerRecord(ADDRESS)), "a record saves")
        return this
    }

    private suspend fun aSaveNeedsARecord(store: SelectionStore) {
        val setup = StoredSelection(setOf(ALPHA), seq = 1, resetSeq = 1)
        expect(!store.saveSelection(setup, setup = true), "a setup's save without a record is refused")
        expect(!store.saveSelection(setup), "and so is a change")
        expect(store.loadSelection() == null, "nothing is stored")
    }

    private suspend fun withNothingChosenOnlyASetupSaves(store: SelectionStore) {
        store.withRecord()
        // A well formed change over nothing chosen: its shape is fine, its marker is not a setup's.
        val change = StoredSelection(setOf(ALPHA), seq = 2, resetSeq = 1)
        expect(!store.saveSelection(change), "a change over nothing chosen is refused, whatever its shape")
        expect(store.loadSelection() == null, "nothing is stored by the refused change")
        expect(store.loadSelectionSeq() == 0L, "and the seq has not moved")
    }

    private suspend fun aSetupSavesAndAChangeFollows(store: SelectionStore) {
        store.withRecord()
        val setup = StoredSelection(setOf(ALPHA), seq = 1, resetSeq = 1)
        expect(store.saveSelection(setup, setup = true), "a setup's save is taken")
        expect(store.loadSelection() == setup, "and read back whole: ${store.loadSelection()}")
        expect(store.loadLibraries() == setOf(ALPHA), "the libraries are its chosen")
        val change = StoredSelection(setOf(ALPHA, BETA), seq = 2, resetSeq = 1)
        expect(store.saveSelection(change), "a change over a chosen selection is taken")
        expect(store.loadSelection() == change, "and read back: ${store.loadSelection()}")
        expect(store.loadSelectionSeq() == 2L, "the seq moved")
        expect(!store.saveSelection(StoredSelection(setOf(BETA), seq = 1, resetSeq = 1)), "a seq below is refused")
        expect(store.loadSelection() == change, "and changes nothing")
    }

    private suspend fun afterAClearOnlyASetupSavesAndTheSeqIsKept(store: SelectionStore) {
        store.withRecord()
        expect(store.saveSelection(StoredSelection(setOf(ALPHA), seq = 3, resetSeq = 3), setup = true), "setup")
        expect(store.clearLibraries(), "the choice clears")
        expect(store.loadSelection() == null, "nothing is chosen")
        expect(store.loadSelectionSeq() == 3L, "the seq is kept")

        expect(!store.saveSelection(StoredSelection(setOf(BETA), seq = 4, resetSeq = 3)), "a change is refused")
        expect(store.loadSelection() == null, "and nothing is stored")
        expect(!store.saveSelection(StoredSelection(setOf(BETA), seq = 2, resetSeq = 2), setup = true), "a seq below")
        val again = StoredSelection(setOf(BETA), seq = 4, resetSeq = 4)
        expect(store.saveSelection(again, setup = true), "a setup's save is taken again")
        expect(store.loadSelection() == again, "and read back: ${store.loadSelection()}")
    }

    private suspend fun anotherAddressDropsTheChoiceAndOnlyASetupSavesAgain(store: SelectionStore) {
        store.withRecord()
        expect(store.saveSelection(StoredSelection(setOf(ALPHA), seq = 2, resetSeq = 2), setup = true), "setup")
        expect(store.save(ServerRecord(OTHER)), "another address saves")
        expect(store.loadSelection() == null, "the choice belonged to the old address")
        expect(store.loadSelectionSeq() == 2L, "the seq is kept")

        expect(!store.saveSelection(StoredSelection(setOf(ALPHA), seq = 3, resetSeq = 2)), "a change is refused")
        expect(store.loadSelection() == null, "and nothing is stored")
        val setup = StoredSelection(setOf(BETA), seq = 3, resetSeq = 3)
        expect(store.saveSelection(setup, setup = true), "a setup's save is taken")
        expect(store.loadSelection() == setup, "and read back: ${store.loadSelection()}")
    }

    private fun expect(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw AssertionError(message)
    }
}
