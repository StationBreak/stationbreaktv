// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui.mark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/** The bundled channel logos are exactly the files recorded in `feature/guide/channelmarks.sha256`. */
class ChannelMarkManifestTest {
    @get:Rule
    val temp = TemporaryFolder()

    // Gradle runs a module's unit tests with the module directory as the working directory.
    private val module = File(".")

    private val lineFormat =
        Regex("^[0-9a-f]{64}  src/[a-zA-Z]+/assets/channelmarks/sl_\\d{3}_[a-z0-9-]+_32dp\\.png$")
    private val namePattern = Regex("sl_(\\d{3})_([a-z0-9-]+)_32dp\\.png")
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val allowedChunks = setOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND")

    private fun manifestEntries(): List<Pair<String, String>> {
        val manifest = File(module, "channelmarks.sha256")
        assertTrue("channelmarks.sha256 is missing", manifest.isFile)
        val lines = manifest.readLines().filter { it.isNotBlank() }
        // Check every line before any file is read.
        for (line in lines) assertTrue("malformed manifest line: '$line'", lineFormat.matches(line))
        return lines.map { line ->
            val (hash, path) = line.split("  ", limit = 2)
            hash to path
        }
    }

    /**
     * Every file (any extension) under any `src/<set>/assets/channelmarks/` of any module below [root]. Dot-folders,
     * `build/` and nested checkouts are skipped while walking from the root only, never inside `src/`.
     */
    internal fun scanMarkFiles(root: File): List<File> =
        root
            .walkTopDown()
            .onEnter { dir ->
                dir == root ||
                    inMarkFolder(dir, root) ||
                    (!dir.name.startsWith(".") && dir.name != "build" && !File(dir, ".git").exists())
            }.filter { it.isFile || Files.isSymbolicLink(it.toPath()) }
            .filter { isMarkPath(it.relativeTo(root).invariantSeparatorsPath.split("/")) }
            .toList()

    private fun inMarkFolder(
        dir: File,
        root: File,
    ): Boolean = isMarkPath(dir.relativeTo(root).invariantSeparatorsPath.split("/") + "x")

    /** `<module...>/src/<set>/assets/channelmarks/<file>`. */
    internal fun isMarkPath(parts: List<String>): Boolean =
        parts.indices.any { i ->
            parts[i] == "src" && parts.size >= i + 5 && parts[i + 2] == "assets" && parts[i + 3] == "channelmarks"
        }

    @Test
    fun theMarkPathCheckLooksAtEverySrcFolder() {
        fun mark(path: String) = isMarkPath(path.split("/"))
        assertTrue(mark("x/src/app/src/main/assets/channelmarks/a.png"))
        assertTrue(mark("src/main/assets/channelmarks/a.png"))
        assertTrue(mark("feature/guide/src/main/assets/channelmarks/a.png"))
        assertTrue(!mark("feature/guide/src/main/assets/other/a.png"))
        assertTrue(!mark("feature/guide/src/main/res/channelmarks/a.png"))
    }

    private fun repoRoot(): File = module.canonicalFile.parentFile.parentFile

    private fun moduleMarks(): List<File> =
        File(module, "src")
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?.filter { file ->
                val parts = file.relativeTo(File(module, "src")).invariantSeparatorsPath.split("/")
                parts.size >= 4 && parts[1] == "assets" && parts[2] == "channelmarks"
            }?.sortedBy { it.name }
            ?.toList()
            .orEmpty()

    /** Scan files that are not in [listed] (canonical paths), plus symlinks. */
    private fun problems(
        root: File,
        listed: Set<String>,
    ): List<String> =
        scanMarkFiles(root).mapNotNull { file ->
            when {
                Files.isSymbolicLink(file.toPath()) -> "symlink: ${file.relativeTo(root).path}"
                file.canonicalPath !in listed -> "not listed: ${file.relativeTo(root).path}"
                else -> null
            }
        }

    @Test
    fun bundledMarksMatchTheManifest() {
        val entries = manifestEntries()
        assertTrue("the manifest lists the marks", entries.isNotEmpty())
        assertEquals("duplicate manifest lines or paths", entries.size, entries.map { it.second }.toSet().size)
        for ((expected, path) in entries) {
            val bytes = File(module, path).readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val actual = digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
            assertEquals(path, expected, actual)
        }
    }

    @Test
    fun everyMarkFileInTheRepoIsInTheManifest() {
        val listed = manifestEntries().map { File(module, it.second).canonicalPath }.toSet()
        val shipped = scanMarkFiles(repoRoot())
        assertTrue("some marks ship", shipped.isNotEmpty())
        assertEquals("mark files not in channelmarks.sha256", emptyList<String>(), problems(repoRoot(), listed))
        val shippedPaths = shipped.map { it.canonicalPath }.toSet()
        assertEquals("manifest entries without a file", emptySet<String>(), listed - shippedPaths)
    }

    @Test
    fun theScanSeesMarkFilesInOtherModulesAndSymlinks() {
        val root = temp.newFolder("repo")
        val stray = File(root, "app/src/main/assets/channelmarks/x.png")
        stray.parentFile.mkdirs()
        stray.writeBytes(byteArrayOf(1))
        File(root, "app/build/src/main/assets/channelmarks").mkdirs()
        File(root, "app/build/src/main/assets/channelmarks/ignored.png").writeBytes(byteArrayOf(1))
        val link = File(root, "feature/guide/src/debug/assets/channelmarks/l.png")
        link.parentFile.mkdirs()
        Files.createSymbolicLink(link.toPath(), stray.toPath())
        val found = problems(root, emptySet())
        assertEquals(
            listOf(
                "not listed: app/src/main/assets/channelmarks/x.png",
                "symlink: feature/guide/src/debug/assets/channelmarks/l.png",
            ),
            found.sortedBy { it.substringAfter(": ") },
        )
    }

    @Test
    fun everyMarkIsAWellFormedSmallPng() {
        val files = moduleMarks()
        assertTrue("some marks ship", files.isNotEmpty())
        for (file in files) {
            val bytes = file.readBytes()
            val name = file.name
            assertTrue("$name is under 16 KB (was ${bytes.size})", bytes.size < 16 * 1024)
            assertTrue("$name is long enough for a PNG", bytes.size >= 8 + 25 + 12)
            assertTrue("$name has the PNG signature", bytes.copyOfRange(0, 8).contentEquals(pngSignature))
            val header = ByteBuffer.wrap(bytes, 8, 25) // big-endian: length, type, width, height
            assertEquals("$name first chunk length", 13, header.int)
            assertEquals("$name first chunk", "IHDR", ByteArray(4).also { header.get(it) }.toString(Charsets.US_ASCII))
            assertEquals("$name width", 64, header.int)
            assertEquals("$name height", 64, header.int)
            assertEquals("$name bit depth", 8, bytes[24].toInt())
            assertChunksEndCleanly(name, bytes)
        }
    }

    /** Walk the chunks by length (bounds-checked, no decoding): allowed types only, IEND last, nothing after it. */
    private fun assertChunksEndCleanly(
        name: String,
        bytes: ByteArray,
    ) {
        var pos = 8
        var sawEnd = false
        while (pos < bytes.size) {
            assertTrue("$name has data after IEND", !sawEnd)
            assertTrue("$name has a truncated chunk header at $pos", pos + 12 <= bytes.size)
            val length = ByteBuffer.wrap(bytes, pos, 4).int.toLong() and 0xFFFFFFFFL
            assertTrue("$name chunk at $pos overruns the file", pos + 12L + length <= bytes.size)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            assertTrue("$name has chunk type '$type'", type in allowedChunks)
            if (type == "IEND") {
                assertEquals("$name IEND length", 0L, length)
                sawEnd = true
            }
            pos += 12 + length.toInt()
        }
        assertTrue("$name ends with IEND", sawEnd)
        assertEquals("$name last chunk is IEND", "IEND", String(bytes, bytes.size - 8, 4, Charsets.US_ASCII))
        val crc = ByteBuffer.wrap(bytes, bytes.size - 4, 4).int.toLong() and 0xFFFFFFFFL
        assertEquals("$name IEND crc", 0xAE426082L, crc)
    }

    @Test
    fun fileNamesFollowThePattern() {
        val files = moduleMarks()
        assertTrue("some marks ship", files.isNotEmpty())
        for (file in files) {
            assertTrue("${file.name} does not match the pattern", namePattern.matches(file.name))
        }
    }

    @Test
    fun thereAreNinetySevenMarksWithDistinctKeys() {
        val matches = moduleMarks().mapNotNull { namePattern.matchEntire(it.name) }
        assertEquals(97, matches.size)
        assertEquals("keys are distinct", 97, matches.map { it.groupValues[2] }.toSet().size)
    }
}
