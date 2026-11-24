// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.app.di.StoreReceipt
import java.io.File

/** Nothing the Appstore adapter handles may be logged (#99): no receipt, user ID, marketplace ID or token. */
class AmazonNoLoggingTest {
    private val blockComment = Regex("""/\*[\s\S]*?\*/""")
    private val lineComment = Regex("""//[^\n]*""")

    // A tripwire over the Amazon source set (tests run in the module directory), with comments removed.
    private val sources =
        File("src/amazon/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to withoutComments(it.readText()) }

    private fun withoutComments(text: String): String = text.replace(blockComment, "").replace(lineComment, "")

    @Test
    fun theAmazonSourceSetNeverLogsOrPrints() {
        assertTrue("the scan found the adapter", "AmazonStore.kt" in sources)
        val banned =
            Regex("""android\.util\.Log|\bLog\.|\bprintln\b|\bprint\(|Timber|Logger|printStackTrace|util\.logging""")
        val hits = sources.filterValues { banned.containsMatchIn(it) }.keys
        assertEquals("logging in the Amazon source set", emptySet<String>(), hits)
    }

    @Test
    fun noResponseOrUserDataIsPrintedOrInterpolated() {
        // The user ID and marketplace ID are never read, and a response is never turned into text but its request ID.
        val banned =
            Regex(
                """\.userData\b|getUserData|marketplace|\.toJSON|\$\{?(response|receipt|result|page)""",
                RegexOption.IGNORE_CASE,
            )
        val hits = sources.filterValues { banned.containsMatchIn(it) }.keys
        assertEquals("user data or a response in text", emptySet<String>(), hits)
    }

    @Test
    fun aReceiptPrintsNoIdOrSku() {
        val text = StoreReceipt("secret-receipt-id", "secret.sku", false).toString()
        assertFalse(text.contains("secret"))
    }
}
