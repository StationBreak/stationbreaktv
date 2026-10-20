// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

/** What the store holds for an account: nothing, or the bytes exactly as they were saved. */
sealed interface StoredBytes {
    data object Absent : StoredBytes

    class Present(
        val bytes: ByteArray,
    ) : StoredBytes {
        // A constant: the bytes are the user's lineup and must not reach a log through this.
        override fun toString(): String = "StoredBytes.Present"
    }
}

/**
 * The port to the stored channel lineup, one opaque byte blob per account (spec 12). It knows nothing of the lineup
 * format; the codec reads and writes the bytes. Nothing here throws for a storage fault: a read that can't be answered
 * is [StoredBytes.Absent], and a write reports whether it reached storage. An account whose server or user ID is not
 * in canonical form (32 lower-case hexadecimal characters) has no file: the store refuses it, and the service
 * canonicalises first.
 *
 * This is deliberately not an `AccountCache`: a lineup is user-authored state, and the library cache can be re-synced
 * but a lineup cannot. Session expiry, a server mismatch, an address change and switching accounts never delete one.
 * [forget] is for an explicit user action only.
 */
interface LineupStore {
    /** The saved bytes for [account], or [StoredBytes.Absent] when there are none (or they couldn't be read). */
    suspend fun load(account: LibraryAccount): StoredBytes

    /** Replaces the account's bytes in one atomic write. Returns true when they were stored. */
    suspend fun save(
        account: LibraryAccount,
        bytes: ByteArray,
    ): Boolean

    /**
     * Moves an unreadable lineup out of the way so a rebuild doesn't destroy it. One set-aside copy is kept per
     * account; a later one replaces it. True when nothing was left to set aside or the move happened.
     */
    suspend fun setAside(account: LibraryAccount): Boolean

    /** Deletes the account's lineup (and nothing of any other account). True when none remains. */
    suspend fun forget(account: LibraryAccount): Boolean
}
