// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** The bundled fonts and licence texts are exactly the files recorded in `core/ui/fonts.sha256`. */
class FontManifestTest {
    // Gradle runs a module's unit tests with the module directory as the working directory.
    private val module = File(".")

    @Test
    fun bundledFontsMatchTheManifest() {
        val entries =
            File(module, "fonts.sha256").readLines().filter { it.isNotBlank() }.map { it.split("  ", limit = 2) }
        assertTrue("the manifest lists the fonts and licences", entries.size >= 4)
        for ((expected, path) in entries) {
            val bytes = File(module, path).readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val actual = digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            assertEquals(path, expected, actual)
        }
    }
}
