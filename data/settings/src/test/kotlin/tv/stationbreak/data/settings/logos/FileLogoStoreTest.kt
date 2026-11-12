// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.settings.logos

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileLogoStoreTest {
    private val dir: File = Files.createTempDirectory("logos").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun bytes(n: Int) = ByteArray(n) { it.toByte() }

    @Test
    fun whatIsPutIsGot() {
        val store = FileLogoStore(dir)
        store.put("aa", "01", bytes(10))

        assertArrayEquals(bytes(10), store.get("aa", "01"))
        assertNull(store.get("aa", "02"))
        assertNull(store.get("bb", "01"))
    }

    @Test
    fun anOverwriteReplaces() {
        val store = FileLogoStore(dir)
        store.put("aa", "01", bytes(10))
        store.put("aa", "01", bytes(4))

        assertArrayEquals(bytes(4), store.get("aa", "01"))
    }

    @Test
    fun retainOnlyKeepsOneAccountAndWithNullNone() {
        val store = FileLogoStore(dir)
        store.put("aa", "01", bytes(3))
        store.put("bb", "01", bytes(3))

        store.retainOnly("bb")
        assertNull(store.get("aa", "01"))
        assertArrayEquals(bytes(3), store.get("bb", "01"))

        store.retainOnly(null)
        assertNull(store.get("bb", "01"))
    }

    @Test
    fun theLeastRecentlyUsedGoesFirstWhenOverTheCap() {
        val store = FileLogoStore(dir, maxBytes = 25)
        store.put("aa", "01", bytes(10))
        store.put("aa", "02", bytes(10))
        // Make the order plain whatever the file system's clock granularity: 01 is the oldest, then 02.
        File(File(dir, "aa"), "01").setLastModified(System.currentTimeMillis() - 20_000)
        File(File(dir, "aa"), "02").setLastModified(System.currentTimeMillis() - 10_000)
        // A read is a use: 01 is now the newest.
        assertArrayEquals(bytes(10), store.get("aa", "01"))

        store.put("aa", "03", bytes(10))

        assertNull(store.get("aa", "02"))
        assertArrayEquals(bytes(10), store.get("aa", "01"))
        assertArrayEquals(bytes(10), store.get("aa", "03"))
    }

    @Test
    fun aLogoOverTheWholeCapIsNotKeptAndNeitherIsAnEmptyOne() {
        val store = FileLogoStore(dir, maxBytes = 8)
        store.put("aa", "01", bytes(9))
        store.put("aa", "02", ByteArray(0))

        assertNull(store.get("aa", "01"))
        assertNull(store.get("aa", "02"))
    }

    @Test
    fun aNameThatIsNotHexNeverTouchesTheFileSystem() {
        val store = FileLogoStore(File(dir, "inner"))
        store.put("../escape", "01", bytes(3))
        store.put("aa", "../escape", bytes(3))
        store.put("aa", "a/b", bytes(3))

        assertNull(store.get("../escape", "01"))
        assertNull(store.get("aa", "../escape"))
        assertFalse(File(dir, "escape").exists())
        assertFalse(File(dir, "inner").exists())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun noPartialFileIsLeftBehind() {
        val store = FileLogoStore(dir)
        store.put("aa", "01", bytes(5))

        assertEquals(listOf("01"), File(dir, "aa").list().orEmpty().toList())
    }
}
