// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.junit.jupiter.api.io.TempDir
import org.xml.sax.SAXParseException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestRulesTest {
    @TempDir
    lateinit var tempDir: File

    private fun manifest(name: String) = File(System.getProperty("fixtures"), "manifests/$name")

    @Test
    fun overrideLibraryIsFound() {
        assertTrue(ManifestRules.overridesLibraryMinSdk(manifest("override-library.xml")))
    }

    @Test
    fun overrideLibraryIsFoundWhateverTheToolsPrefix() {
        assertTrue(ManifestRules.overridesLibraryMinSdk(manifest("override-library-other-prefix.xml")))
    }

    @Test
    fun aManifestWithoutItPassesEvenIfACommentMentionsIt() {
        assertFalse(ManifestRules.overridesLibraryMinSdk(manifest("clean.xml")))
    }

    // XXE: the check fails closed on a DOCTYPE, before it reads the entity's file.
    @Test
    fun aManifestWithAnExternalEntityIsRejectedUnresolved() {
        val secret = tempDir.resolve("secret.txt").apply { writeText("SECRET-CONTENTS") }
        val manifest =
            tempDir.resolve("AndroidManifest.xml").apply {
                writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <!DOCTYPE manifest [ <!ENTITY xxe SYSTEM "${secret.toURI()}"> ]>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <application android:label="&xxe;" />
                    </manifest>
                    """.trimIndent(),
                )
            }

        val error = assertFailsWith<SAXParseException> { ManifestRules.overridesLibraryMinSdk(manifest) }

        assertTrue("DOCTYPE" in error.message.orEmpty(), error.message)
        assertFalse("SECRET-CONTENTS" in error.toString(), error.toString())
    }
}
