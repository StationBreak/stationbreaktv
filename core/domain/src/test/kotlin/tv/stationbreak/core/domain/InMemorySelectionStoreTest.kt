// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** The fake keeps [SelectionStoreContract] like the file store does. */
class InMemorySelectionStoreTest {
    @Test
    fun theFakeKeepsTheContract() = runBlocking { SelectionStoreContract.check { InMemoryServerSettings() } }
}
