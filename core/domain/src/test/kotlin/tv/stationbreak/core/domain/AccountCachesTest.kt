// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.model.ServerId
import tv.stationbreak.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Sign-in and sign-out tell the saved library and what is derived from it (the days on disk, #480) as one. */
class AccountCachesTest {
    private val account = LibraryAccount(ServerId("a".repeat(32)), UserId("b".repeat(32)))
    private val told = mutableListOf<String>()

    private inner class Recording(
        val name: String,
        val fails: Boolean = false,
    ) : AccountCache {
        override suspend fun useAccount(account: LibraryAccount) {
            told += "$name.use"
            if (fails) error("$name failed")
        }

        override suspend fun forget(account: LibraryAccount) {
            told += "$name.forget"
            if (fails) error("$name failed")
        }
    }

    @Test
    fun eachIsToldInOrder() {
        val caches = AccountCaches(Recording("library"), Recording("days"))
        runBlocking {
            caches.useAccount(account)
            caches.forget(account)
        }
        assertEquals(listOf("library.use", "days.use", "library.forget", "days.forget"), told)
    }

    @Test
    fun aFailureStillTellsTheRestAndIsRethrown() {
        val caches = AccountCaches(Recording("library", fails = true), Recording("days"))
        val thrown = assertFailsWith<IllegalStateException> { runBlocking { caches.forget(account) } }
        assertEquals("library failed", thrown.message)
        assertEquals(listOf("library.forget", "days.forget"), told)
    }
}
