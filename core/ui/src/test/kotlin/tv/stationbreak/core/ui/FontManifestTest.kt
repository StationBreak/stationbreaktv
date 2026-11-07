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

    private val lineFormat = Regex("^[0-9a-f]{64}  \\S.*$")
    private val fontExtensions = setOf("ttf", "otf", "ttc", "woff", "woff2")

    private fun manifestPaths(): List<Pair<String, String>> =
        File(module, "fonts.sha256").readLines().filter { it.isNotBlank() }.map { line ->
            assertTrue("malformed manifest line: '$line'", lineFormat.matches(line))
            val (hash, path) = line.split("  ", limit = 2)
            hash to path
        }

    @Test
    fun bundledFontsMatchTheManifest() {
        val entries = manifestPaths()
        assertTrue("the manifest lists the fonts and licences", entries.size >= 4)
        for ((expected, path) in entries) {
            val bytes = File(module, path).readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val actual = digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            assertEquals(path, expected, actual)
        }
    }

    @Test
    fun everyFontAndLicenceInTheRepoIsInTheManifest() {
        val root = module.canonicalFile.parentFile.parentFile
        val listed = manifestPaths().map { File(module, it.second).canonicalPath }.toSet()
        val shipped =
            root
                .walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" }
                .filter { it.isFile }
                .filter { file ->
                    val parts = file.relativeTo(root).invariantSeparatorsPath.split("/")
                    val inAssets = parts.contains("assets")
                    val isLicence = inAssets && parts.contains("licenses")
                    val isFont = file.extension.lowercase(Locale.ROOT) in fontExtensions
                    isLicence || (isFont && (inAssets || parts.any { it.startsWith("font") }))
                }.map { it.canonicalPath }
                .toSet()
        assertTrue("some fonts ship", shipped.isNotEmpty())
        assertEquals("font or licence files not in core/ui/fonts.sha256", emptySet<String>(), shipped - listed)
    }
}
