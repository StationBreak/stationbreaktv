// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The contract of [LibraryRepository.scheduleItemsOf] (spec 26), run by the tests of every implementation: the fake's
 * and the Room one's. Synthetic IDs only. A failure throws [AssertionError].
 */
object ScheduleItemsOfContract {
    /**
     * One implementation under test: [repository] for [account], which [save] writes items to (as a sync of
     * [library] would) and [delete] marks deleted. [cutoffMs] is a cut-off after every save and delete; [createdAt]
     * a creation time well before it.
     */
    class Fixture(
        val repository: LibraryRepository,
        val account: LibraryAccount,
        val save: suspend (library: ItemId, items: List<MediaItem>) -> Unit,
        val delete: suspend (ids: List<ItemId>) -> Unit,
        val cutoffMs: Long,
        val createdAt: Instant,
    )

    val LIBRARY_A = ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e1")
    val LIBRARY_B = ItemId("e0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e2")

    private fun hex(n: Int) = n.toString(16).padStart(32, '0')

    private fun movie(
        n: Int,
        library: ItemId,
        createdAt: Instant,
        runtimeMinutes: Int? = 90,
    ) = MediaItem(
        ItemId(hex(n)),
        "Synthetic $n",
        ItemKind.Movie,
        runtimeMinutes?.minutes,
        library,
        dateCreated = createdAt - 1.days,
    )

    private fun canonical(n: Int) = CanonicalId.parse(hex(n))

    /** Runs every case against a fresh [fixture]: call it once per new repository. */
    suspend fun check(fixture: Fixture) {
        val repo = fixture.repository
        val account = fixture.account
        repo.applySelection(account, setOf(LIBRARY_A, LIBRARY_B), nowMs = 0)
        fixture.save(
            LIBRARY_A,
            listOf(
                movie(0x7001, LIBRARY_A, fixture.createdAt, runtimeMinutes = 45),
                movie(0x7002, LIBRARY_A, fixture.createdAt),
                movie(0x7003, LIBRARY_A, fixture.createdAt, runtimeMinutes = null),
                movie(0x7005, LIBRARY_A, fixture.createdAt),
                movie(0x7006, LIBRARY_A, fixture.createdAt),
            ),
        )
        fixture.save(LIBRARY_B, listOf(movie(0x7004, LIBRARY_B, fixture.createdAt)))
        fixture.delete(listOf(ItemId(hex(0x7005))))

        expect(repo.scheduleItemsOf(account, emptySet(), fixture.cutoffMs).isEmpty(), "no ids give nothing")

        // Two of one library, one of another, one with no runtime, one deleted, one unknown, one not asked for.
        val asked = listOf(0x7004, 0x7002, 0x7001, 0x7003, 0x7005, 0x7999).mapTo(LinkedHashSet()) { canonical(it) }
        val got = repo.scheduleItemsOf(account, asked, fixture.cutoffMs)

        expect(
            got.map { it.id } == listOf(canonical(0x7001), canonical(0x7002), canonical(0x7004)),
            "the live, schedulable ids asked for, in canonical-ID order, whatever library: ${got.map { it.id }}",
        )
        expect(got.first().runtimeMs == 45.minutes.inWholeMilliseconds, "the held runtime")
        val cutoff = Instant.fromEpochMilliseconds(fixture.cutoffMs)
        val all = repo.scheduleItems(account, setOf(LIBRARY_A, LIBRARY_B), cutoff)
        expect(got == all.filter { it.id in asked }, "the same items scheduleItems gives at the cut-off, narrowed")
        expect(
            repo.scheduleItemsOf(account, setOf(canonical(0x7006)), fixture.cutoffMs).map { it.id } ==
                listOf(canonical(0x7006)),
            "one id",
        )
    }

    private fun expect(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw AssertionError(message)
    }
}
