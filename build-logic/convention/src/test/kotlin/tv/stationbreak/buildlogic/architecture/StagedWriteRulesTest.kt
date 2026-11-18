// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StagedWriteRulesTest {
    private val mainFile = "src/main/kotlin/tv/stationbreak/data/settings/Other.kt"
    private val syncs = listOf("out.fd.sync()", "stream.getFD().sync()", "channel.force(true)", "Os.fsync(fd)")
    private val renames = listOf("a.renameTo(b)", "Files.move(a, b)", "Os.rename(a, b)")

    private fun violations(
        text: String,
        module: String = ":data:settings",
        path: String = mainFile,
    ) = StagedWriteRules.violations(module, path, text)

    @Test
    fun everySyncWithEveryRenameIsRefusedWithTheLineOfTheSync() {
        for (s in syncs) {
            for (r in renames) {
                val found = violations("package a\n$s\n$r")
                assertEquals(1, found.size, "$s + $r")
                assertTrue(found[0].startsWith("$mainFile:2 "), found[0])
                assertTrue("StagedFiles" in found[0], found[0])
            }
        }
    }

    @Test
    fun theStagedFilesSourceOfDataSettingsIsAllowed() {
        val path = "src/main/kotlin/tv/stationbreak/data/settings/StagedFiles.kt"
        assertEquals(emptyList(), violations("out.fd.sync()\nstaged.renameTo(file)", path = path))
    }

    @Test
    fun aFileOfTheSameNameInAnotherModuleIsRefused() {
        val path = "src/main/kotlin/tv/stationbreak/data/other/StagedFiles.kt"
        assertEquals(1, violations("out.fd.sync()\nstaged.renameTo(file)", ":data:other", path).size)
    }

    @Test
    fun anotherModuleIsFlaggedToo() {
        assertEquals(1, violations("out.fd.sync()\na.renameTo(b)", ":data:library").size)
    }

    @Test
    fun aTestSourceIsIgnored() {
        for (set in listOf("test", "androidTest", "testFixtures")) {
            val path = "src/$set/kotlin/tv/stationbreak/Thing.kt"
            assertEquals(emptyList(), violations("out.fd.sync()\na.renameTo(b)", path = path), set)
        }
    }

    @Test
    fun aCommentMentioningBothIsIgnored() {
        val text = "/**\n * fd.sync() then renameTo(x)\n */\n// Os.fsync( and Files.move(\nclass A"
        assertEquals(emptyList(), violations(text))
    }

    @Test
    fun aRenameAloneOrASyncAlonePasses() {
        assertEquals(emptyList(), violations("temporary.renameTo(file)"))
        assertEquals(emptyList(), violations("out.fd.sync()"))
    }
}
