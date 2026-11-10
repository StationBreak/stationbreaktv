// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tv.stationbreak.core.domain.SelectionStoreContract
import java.util.concurrent.Executor

/** The file store keeps [SelectionStoreContract], the same as the in-memory fake. */
class FileSelectionStoreTest {
    private val inPlace = Executor(Runnable::run).asCoroutineDispatcher()

    @Test
    fun theFileStoreKeepsTheSelectionStoreContract() =
        runBlocking { SelectionStoreContract.check { FileServerSettings(MemoryRecordFile(), inPlace) } }
}
