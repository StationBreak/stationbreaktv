// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** The bundled channel logos are exactly the files recorded in `feature/guide/channelmarks.sha256`. */
class ChannelMarkManifestTest {
    // Gradle runs a module's unit tests with the module directory as the working directory.
    private val module = File(".")

    private val lineFormat = Regex("^[0-9a-f]{64}  \\S.*$")
    private val namePattern = Regex("sl_(\\d{3})_([a-z0-9-]+)_32dp\\.png")
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun manifestEntries(): List<Pair<String, String>> {
        val manifest = File(module, "channelmarks.sha256")
        assertTrue("channelmarks.sha256 is missing", manifest.isFile)
        return manifest.readLines().filter { it.isNotBlank() }.map { line ->
            assertTrue("malformed manifest line: '$line'", lineFormat.matches(line))
            val (hash, path) = line.split("  ", limit = 2)
            hash to path
        }
    }

    /** Every file (any extension) under any `src/<set>/assets/channelmarks/` of this module. */
    private fun shippedFiles(): List<File> {
        val src = File(module, "src")
        if (!src.isDirectory) return emptyList()
        return src
            .walkTopDown()
            .onEnter { dir -> dir == src || (!dir.name.startsWith(".") && dir.name != "build") }
            .filter { it.isFile }
            .filter { file ->
                val parts = file.relativeTo(src).invariantSeparatorsPath.split("/")
                parts.size >= 4 && parts[1] == "assets" && parts[2] == "channelmarks"
            }.toList()
    }

    private fun markFiles(): List<File> = shippedFiles().sortedBy { it.name }

    @Test
    fun bundledMarksMatchTheManifest() {
        val entries = manifestEntries()
        assertTrue("the manifest lists the marks", entries.isNotEmpty())
        for ((expected, path) in entries) {
            val bytes = File(module, path).readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val actual = digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            assertEquals(path, expected, actual)
        }
    }

    @Test
    fun everyMarkFileInTheModuleIsInTheManifest() {
        val listed = manifestEntries().map { File(module, it.second).canonicalPath }.toSet()
        val shipped = shippedFiles().map { it.canonicalPath }.toSet()
        assertTrue("some marks ship", shipped.isNotEmpty())
        assertEquals("mark files not in channelmarks.sha256", emptySet<String>(), shipped - listed)
        assertEquals("manifest entries without a file", emptySet<String>(), listed - shipped)
    }

    @Test
    fun everyMarkIsAWellFormedSmallPng() {
        val files = markFiles()
        assertTrue("some marks ship", files.isNotEmpty())
        for (file in files) {
            val bytes = file.readBytes()
            assertTrue("${file.name} is under 16 KB (was ${bytes.size})", bytes.size < 16 * 1024)
            assertTrue("${file.name} is long enough for a PNG header", bytes.size >= 33)
            assertTrue("${file.name} has the PNG signature", bytes.copyOfRange(0, 8).contentEquals(pngSignature))
            val header = ByteBuffer.wrap(bytes, 8, 25) // big-endian: length, type, width, height
            assertEquals("${file.name} first chunk length", 13, header.int)
            val type = ByteArray(4).also { header.get(it) }.toString(Charsets.US_ASCII)
            assertEquals("${file.name} first chunk", "IHDR", type)
            assertEquals("${file.name} width", 64, header.int)
            assertEquals("${file.name} height", 64, header.int)
        }
    }

    @Test
    fun fileNamesFollowThePattern() {
        val files = shippedFiles()
        assertTrue("some marks ship", files.isNotEmpty())
        for (file in files) {
            assertTrue("${file.name} does not match the pattern", namePattern.matches(file.name))
        }
    }

    @Test
    fun thereAreNinetySevenMarksWithDistinctKeys() {
        val matches = markFiles().mapNotNull { namePattern.matchEntire(it.name) }
        assertEquals(97, matches.size)
        assertEquals("keys are distinct", 97, matches.map { it.groupValues[2] }.toSet().size)
    }
}
