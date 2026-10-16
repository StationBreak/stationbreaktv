// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.MediaItem
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [LibraryRepository] in memory for tests (ADR-0004). It follows the port's contract (snapshot filtering, the
 * exclusion count, account isolation, pruning on selection) and records the [AccountCache] calls it got, so a test of
 * the sign-in path can prove who was activated and who was forgotten. It is not a database: the real one is
 * `data:library`'s.
 */
class InMemoryLibraryRepository : LibraryRepository {
    private class Row(
        val account: LibraryAccount,
        val library: ItemId,
        val item: MediaItem,
        val tombstoned: Boolean,
    )

    private val rows = mutableListOf<Row>()
    private val pending = mutableMapOf<LibraryAccount, MutableSet<ItemId>>()
    private val statuses = MutableStateFlow<Map<LibraryAccount, LibraryStatus>>(emptyMap())

    /** Every account passed to [useAccount], in order. */
    val activated = mutableListOf<LibraryAccount>()

    /** Every account passed to [forget], in order. */
    val forgotten = mutableListOf<LibraryAccount>()

    /** Makes [forget] throw, to prove a failing cache cannot stop a sign-out. */
    var failForget = false

    /** Saves [items] for [account] under [library]; with [tombstoned] they read as deleted. */
    fun seed(
        account: LibraryAccount,
        library: ItemId,
        vararg items: MediaItem,
        tombstoned: Boolean = false,
    ) {
        items.forEach { rows += Row(account, library, it, tombstoned) }
    }

    /** Sets what [status] reports for [account]. */
    fun setStatus(
        account: LibraryAccount,
        status: LibraryStatus,
    ) {
        statuses.value = statuses.value + (account to status)
    }

    override fun status(account: LibraryAccount): Flow<LibraryStatus> =
        statuses.map { it[account] ?: LibraryStatus(SyncState.NeverSynced, null) }

    override suspend fun snapshot(
        account: LibraryAccount,
        libraries: Set<ItemId>,
        createdBefore: Instant,
    ): List<MediaItem> =
        live(account, libraries)
            .map { it.item }
            .filter { (it.dateCreated ?: return@filter false) < createdBefore && it.runtime.usable() }
            .sortedBy { it.id.value }

    override suspend fun excludedCount(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ): Int = live(account, libraries).count { !it.item.runtime.usable() }

    override suspend fun select(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ) {
        rows.removeAll { it.account == account && it.library !in libraries }
        val saved = rows.filter { it.account == account }.map { it.library }.toSet()
        val held = pending.getOrPut(account) { mutableSetOf() }
        held.retainAll(libraries)
        held += libraries - saved
    }

    override suspend fun pendingLibraries(account: LibraryAccount): Set<ItemId> = pending[account].orEmpty().toSet()

    override suspend fun useAccount(account: LibraryAccount) {
        activated += account
        rows.removeAll { it.account != account }
        pending.keys.retainAll(setOf(account))
        statuses.value = statuses.value.filterKeys { it == account }
    }

    override suspend fun forget(account: LibraryAccount) {
        forgotten += account
        check(!failForget) { "the cache could not be cleared" }
        rows.removeAll { it.account == account }
        pending.remove(account)
        statuses.value = statuses.value - account
    }

    private fun live(
        account: LibraryAccount,
        libraries: Set<ItemId>,
    ) = rows.filter { it.account == account && it.library in libraries && !it.tombstoned }

    private fun Duration?.usable() = this != null && this > Duration.ZERO
}
