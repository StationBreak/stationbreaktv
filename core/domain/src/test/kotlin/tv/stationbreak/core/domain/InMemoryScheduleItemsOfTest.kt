// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.time.Instant

/** The fake keeps [ScheduleItemsOfContract] like the Room repository (spec 26). */
class InMemoryScheduleItemsOfTest {
    private val account =
        LibraryAccount(ServerId("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"), UserId("b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1"))

    @Test
    fun keepsTheContract() =
        runBlocking {
            val repo = InMemoryLibraryRepository()
            repo.useAccount(account)
            val now = Instant.parse("2026-10-19T12:00:00Z")
            ScheduleItemsOfContract.check(
                ScheduleItemsOfContract.Fixture(
                    repository = repo,
                    account = account,
                    save = { library, items -> repo.seed(account, library, *items.toTypedArray()) },
                    delete = { ids -> repo.tombstone(account, ids) },
                    cutoffMs = now.toEpochMilliseconds(),
                    createdAt = now,
                ),
            )
        }
}
