// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import tv.stationbreak.core.domain.LibraryAccount
import tv.stationbreak.core.domain.LibraryRepository
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.model.ItemId

/**
 * Whose the stored choice is, and what features see of it (spec 26, Part 4 §2): the active account, the account that
 * adopted an older build's record, and the publication of only the choice the account owns.
 *
 * Part of [LibrarySelection], which builds it: it takes no lock of its own and runs only inside the selection's lock
 * (`locked {}` or the resume), so its state is read and written one operation at a time.
 */
internal class SelectionOwnership(
    private val library: LibraryRepository,
) {
    private val view = MutableStateFlow<ChoiceView?>(null)
    private val seqs = MutableStateFlow(0L)

    // The adds the add pass gave up in this process, each by its account and stamp: published as stalled while still
    // stored for that account, and dropped when the choice is published without them.
    private val givenUp = mutableSetOf<GivenUp>()

    private data class GivenUp(
        val account: LibraryAccount,
        val id: ItemId,
        val stamp: Long,
    )

    // The account that adopted an older build's record (one naming no owner) at the resume: that record is its own
    // for the life of the process, even when the write naming it the owner failed. Set only by the resume.
    @Volatile var adopted: LibraryAccount? = null
        private set

    /** The published choice: only the active account's own. */
    val selection: StateFlow<ChoiceView?> = view.asStateFlow()

    /** The published seq of the stored selection: it never moves back. */
    val selectionChanges: StateFlow<Long> = seqs.asStateFlow()

    // The resume's adoption of an older build's record for [account].
    fun adopt(account: LibraryAccount) {
        adopted = account
    }

    suspend fun activeAccount(): LibraryAccount? = library.activeAccount().first()

    // Whether [stored] is [account]'s choice: a stored owner says so. A record that names none is an older build's:
    // it is the account's only when the resume adopted it for that account (those builds cleared the choice at every
    // sign-in and switch of user, so it was the signed-in one's). Otherwise it is nobody's, and foreign to every
    // account: a sign-in clears it first, and a setup clears it when that clear failed.
    fun owns(
        account: LibraryAccount,
        stored: StoredSelection,
    ): Boolean = if (stored.owner != null) stored.owner == account else adopted == account

    // Publishes [stored] only when it is [account]'s own choice (the active account's when not given), and null
    // otherwise: features never see another account's choice.
    suspend fun publishOwned(stored: StoredSelection?) = publishOwned(stored, activeAccount())

    fun publishOwned(
        stored: StoredSelection?,
        account: LibraryAccount?,
    ) {
        publish(stored?.takeIf { account != null && owns(account, it) })
    }

    // Records that the add pass gave up [account]'s add of [id] under [stamp] for the life of the process.
    fun giveUp(
        account: LibraryAccount,
        id: ItemId,
        stamp: Long,
    ) {
        givenUp += GivenUp(account, id, stamp)
    }

    internal fun givenUpCount(): Int = givenUp.size

    // Publishes the selection to features, and its seq to the refresher (never back).
    fun publish(stored: StoredSelection?) {
        // The record's account, as [owns] reads it: its owner, or the account that adopted it when it names none.
        val account = stored?.let { it.owner ?: adopted }
        // A give-up that no stored add of its account matches is over: nothing is left to show or to ask again.
        // Nothing published (no choice read, or another account's) prunes nothing: the next stored choice decides.
        if (stored != null) givenUp.retainAll { g -> account == g.account && stored.adding[g.id] == g.stamp }
        view.value =
            stored?.let { saved ->
                val stalled = givenUp.map { it.id }.toSet()
                ChoiceView(saved.chosen, saved.adding.keys, saved.gone, stalled)
            }
        if (stored != null) seqs.value = maxOf(seqs.value, stored.seq)
    }
}

// A choice that names its owner, and the owner is not [account]: another account's.
internal fun StoredSelection.isOtherThan(account: LibraryAccount): Boolean = owner != null && owner != account
