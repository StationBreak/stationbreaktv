// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.MediaItem
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** The fake keeps [LibraryRowsContract] like the Room repository (spec 26, schema 11). */
class InMemoryLibraryRowsTest {
    private val account = LibraryAccount(ServerId("server-a"), UserId("user-a"))
    private val other = LibraryAccount(ServerId("server-a"), UserId("user-b"))

    @Test
    fun keepsTheContract() =
        runBlocking {
            LibraryRowsContract.check {
                val repo = InMemoryLibraryRepository()
                repo.useAccount(account)
                LibraryRowsContract.Fixture(
                    repository = repo,
                    account = account,
                    other = other,
                    proveWalk = { library, stamp -> repo.proveWalk(account, library, stamp) },
                    saveRow = { library, _ ->
                        repo.seed(
                            account,
                            library,
                            MediaItem(
                                ItemId("f0f0f0f0f0f0f0f0f0f0f0f0f0f0f0f1"),
                                "Film",
                                ItemKind.Movie,
                                90.minutes,
                                library,
                            ),
                        )
                    },
                    signOut = { repo.forget(account) },
                )
            }
        }
}
