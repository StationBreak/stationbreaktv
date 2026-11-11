// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.details

/**
 * Where fetched logos are kept between runs, per account. Blocking: a caller runs it off the main thread. A store
 * never throws for a file problem: a read that fails is a miss and a write that fails is not kept. [account] and
 * [key] are lowercase hex (they are made from hashes), never text from a server.
 */
interface LogoStore {
    /** The bytes held under [key] for [account], or null. A read counts as a use, for eviction. */
    fun get(
        account: String,
        key: String,
    ): ByteArray?

    /** Keeps [bytes] under [key] for [account], evicting the least recently used when the store is over its cap. */
    fun put(
        account: String,
        key: String,
        bytes: ByteArray,
    )

    /** Drops everything held for any account but [account]; with null, everything. */
    fun retainOnly(account: String?)
}
