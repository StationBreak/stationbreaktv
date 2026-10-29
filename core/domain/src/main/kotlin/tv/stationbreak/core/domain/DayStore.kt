// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

/**
 * The port to the built days kept on disk (#480): a few opaque files per account, so that a new process can start from
 * the day it built last time instead of building it cold. It knows nothing of their format; the lineup code reads and
 * writes them. They are a cache: losing one only costs a build, so nothing here is precious.
 *
 * It is an [AccountCache], composed with the saved library's ([AccountCaches]): [forget] deletes the account's files,
 * and [useAccount] deletes every other account's, so sign-out and a switch of account clear them with the library.
 *
 * A file name is chosen by the caller from lower-case letters, digits, `_` and `-` only, at most [MAX_NAME] long; the
 * store refuses any other name, and an account whose server or user ID is not canonical (32 lower-case hexadecimal
 * characters) has no files.
 */
interface DayStore : AccountCache {
    /**
     * The file's bytes, [StoredBytes.Absent] when there is no such file (or the name or account is refused), or
     * [StoredBytes.Failed] when it is there but cannot be read or is over the store's size cap.
     */
    suspend fun read(
        account: LibraryAccount,
        name: String,
    ): StoredBytes

    /**
     * Replaces the file with [bytes], atomically: a fault or a file over the size cap leaves the old file (or none) and
     * never a torn one. True when the new bytes were stored. Never throws for a storage fault.
     */
    suspend fun write(
        account: LibraryAccount,
        name: String,
        bytes: ByteArray,
    ): Boolean

    /** The names of [account]'s files; empty when it has none. Never throws for a storage fault. */
    suspend fun names(account: LibraryAccount): List<String>

    /** Removes the file. True when it is gone. Never throws for a storage fault. */
    suspend fun delete(
        account: LibraryAccount,
        name: String,
    ): Boolean

    companion object {
        /** The longest file name a caller may use: far inside any file system's limit. */
        const val MAX_NAME = 64
    }
}
