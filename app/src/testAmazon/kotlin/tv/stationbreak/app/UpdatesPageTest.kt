// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.app.di.PRO_SKU
import tv.stationbreak.app.di.Reply
import tv.stationbreak.app.di.StoreReceipt
import tv.stationbreak.app.di.updatesPageOf

/** A page whose receipts cannot all be read is a failed page: nothing is dropped to look like "no Pro". */
class UpdatesPageTest {
    private val pro = StoreReceipt("r1", PRO_SKU, canceled = false)

    @Test
    fun aPageWithEveryReceiptReadableIsKept() {
        val page = updatesPageOf("id", Reply.Ok, listOf(pro), hasMore = true)
        assertEquals(Reply.Ok, page.reply)
        assertEquals(listOf(pro), page.receipts)
        assertTrue(page.hasMore)
    }

    @Test
    fun oneUnreadableReceiptFailsTheWholePage() {
        val page = updatesPageOf("id", Reply.Ok, listOf(pro, null), hasMore = false)
        assertEquals(Reply.Failed, page.reply)
        assertEquals(emptyList<StoreReceipt>(), page.receipts)
    }

    @Test
    fun aMissingReceiptListFailsThePage() {
        assertEquals(Reply.Failed, updatesPageOf("id", Reply.Ok, null, hasMore = false).reply)
    }

    @Test
    fun anErrorStaysAnErrorAndUnsupportedStaysUnsupported() {
        assertEquals(Reply.Failed, updatesPageOf("id", Reply.Failed, listOf(pro), hasMore = false).reply)
        assertEquals(Reply.Unsupported, updatesPageOf("id", Reply.Unsupported, null, hasMore = false).reply)
    }
}
