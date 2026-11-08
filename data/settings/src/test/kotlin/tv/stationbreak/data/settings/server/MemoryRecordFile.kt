// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.server

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A [RecordFile] in memory that counts its reads and writes and can be told to fail or to stall. */
internal class MemoryRecordFile : RecordFile {
    @Volatile var text: String? = null

    @Volatile var failReads = false

    @Volatile var failWrites = false

    /** The write lands on "disk" and then reports a fault, as a write whose outcome is unknown does. */
    @Volatile var landThenFail = false

    /** Reads start failing as soon as a write is tried: the disk went bad during the write. */
    @Volatile var failReadsAfterAWrite = false

    @Volatile var reads = 0

    @Volatile var writes = 0

    /** When set, a write waits for [release] after [entered] counts down: a writer caught inside the store's lock. */
    @Volatile var stall: Stall? = null

    class Stall {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    /** Runs inside every read, before it answers: a probe of what others see meanwhile. */
    @Volatile var onRead: (() -> Unit)? = null

    override fun read(): String? {
        onRead?.invoke()
        reads++
        if (failReads) throw IOException("unreadable")
        return text
    }

    override fun write(text: String) {
        stall?.let {
            it.entered.countDown()
            it.release.await(STALL_SECONDS, TimeUnit.SECONDS)
        }
        if (failReadsAfterAWrite) failReads = true
        if (failWrites) throw IOException("disk full")
        writes++
        this.text = text
        if (landThenFail) throw IOException("reported lost")
    }

    private companion object {
        const val STALL_SECONDS = 10L
    }
}
