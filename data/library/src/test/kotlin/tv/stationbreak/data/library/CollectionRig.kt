// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import tv.stationbreak.core.domain.CollectionSource
import tv.stationbreak.core.domain.PagedMediaServer
import tv.stationbreak.core.model.CollectionInfo
import tv.stationbreak.core.model.CollectionMember
import tv.stationbreak.core.model.CollectionMemberKind
import tv.stationbreak.core.model.ItemId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * One signed-in account and a fake server that holds BoxSets (spec 10, task 2.5): the world the collection pass tests
 * start from. IDs are canonical, so a member passes the pass's ID check. [box] and [film] make them from numbers.
 */
internal class CollectionRig(
    val clock: FakeClock = FakeClock(),
    private val relist: Duration = 6.hours,
    private val pageSize: Int = 100,
) {
    val repository = TestLibraries.inMemory(clock, signedIn = listOf(ALICE))
    val source = CollectionSource()
    val server = PagedMediaServer(emptyList(), emptyMap()).also { it.collectionSource = source }

    fun box(n: Int) = ItemId(hexId(1_000 + n))

    fun film(n: Int) = ItemId(hexId(n))

    /** Adds (or replaces) the BoxSet [n] on the server, holding [members] as movies unless they are [CollectionMember]. */
    fun serve(
        n: Int,
        vararg members: CollectionMember,
        name: String = "Collection $n",
    ) {
        source.collections.removeAll { it.id == box(n) }
        source.collections += CollectionInfo(box(n), name)
        source.members[box(n)] = members.toMutableList()
        saved(n)
    }

    /** Every BoxSet was last saved an hour ago: before a later pass's cursor, less its margin. */
    fun age() {
        source.collections.forEach { source.saved[it.id] = Instant.fromEpochMilliseconds(clock.nowMs - 60 * 60_000) }
    }

    /** The server saved BoxSet [n] now, as it does when a member is added, removed or renamed. */
    fun saved(n: Int) {
        source.saved[box(n)] = Instant.fromEpochMilliseconds(clock.nowMs)
    }

    fun movies(vararg numbers: Int) = numbers.map { CollectionMember(film(it), CollectionMemberKind.Movie) }.toTypedArray()

    fun pass(clock: Clock = this.clock) = CollectionPass(repository, server, clock, pageSize = pageSize, relistInterval = relist)

    suspend fun run(): CollectionResult = pass().run(SyncWorld.session, ALICE)

    /** The current members of [n] as numbers of films, in order. */
    suspend fun current(n: Int): List<String> = repository.currentMembers(ALICE, box(n).value).map { it.substringBefore(':') }
}
