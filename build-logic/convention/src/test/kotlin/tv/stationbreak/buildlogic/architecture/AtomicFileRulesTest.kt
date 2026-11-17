// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtomicFileRulesTest {
    private val mainFile = "src/main/kotlin/tv/stationbreak/data/settings/keystore/BlobFiles.kt"

    private fun violations(
        text: String,
        module: String = ":data:settings",
        path: String = mainFile,
    ) = AtomicFileRules.violations(module, path, text)

    @Test
    fun anImportOrACallInDataSettingsMainIsRefusedWithItsLine() {
        val found = violations("package a\nimport android.util.AtomicFile\n\nval f = AtomicFile(file)")
        assertEquals(2, found.size)
        assertTrue(found[0].startsWith("$mainFile:2 "), found[0])
        assertTrue(found[1].startsWith("$mainFile:4 "), found[1])
    }

    @Test
    fun theKdocThatExplainsTheBanDoesNotTripIt() {
        val text = "/**\n * Not android.util.AtomicFile.\n */\n// AtomicFile is out\nclass A"
        assertEquals(emptyList(), violations(text))
    }

    @Test
    fun anotherModuleMayUseIt() {
        assertEquals(emptyList(), violations("val f = AtomicFile(file)", ":feature:other"))
    }

    @Test
    fun aTestSourceOfDataSettingsIsNotChecked() {
        for (set in listOf("test", "androidTest", "testFixtures")) {
            val path = "src/$set/kotlin/tv/stationbreak/data/settings/Thing.kt"
            assertEquals(emptyList(), violations("val f = AtomicFile(file)", path = path), set)
        }
    }

    @Test
    fun aVariantSourceSetOfDataSettingsIsCheckedToo() {
        val path = "src/release/kotlin/tv/stationbreak/data/settings/Thing.kt"
        assertEquals(1, violations("val f = AtomicFile(file)", path = path).size)
    }

    @Test
    fun aNameThatMerelyContainsItPasses() {
        assertEquals(emptyList(), violations("val f = AtomicFileLike(file)"))
    }
}
