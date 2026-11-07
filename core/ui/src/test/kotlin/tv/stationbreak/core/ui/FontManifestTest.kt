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
                .onEnter { dir ->
                    // Skip dot-folders, build output and nested checkouts (a directory below the root with a .git).
                    dir == root || (!dir.name.startsWith(".") && dir.name != "build" && !File(dir, ".git").exists())
                }.filter { it.isFile }
                .filter { isShipped(it.relativeTo(root).invariantSeparatorsPath.split("/")) }
                .map { it.canonicalPath }
                .toSet()
        assertTrue("some fonts ship", shipped.isNotEmpty())
        assertEquals("font or licence files not in core/ui/fonts.sha256", emptySet<String>(), shipped - listed)
    }

    /** A font file anywhere under a module's `src/`, or any file under `src/<set>/assets/**/licenses/`. */
    private fun isShipped(parts: List<String>): Boolean {
        val src = parts.indexOf("src")
        if (src < 1) return false
        val isFont = parts.last().substringAfterLast('.', "").lowercase(Locale.ROOT) in fontExtensions
        val isLicence = parts.getOrNull(src + 2) == "assets" && "licenses" in parts.drop(src + 3).dropLast(1)
        return isFont || isLicence
    }
}
