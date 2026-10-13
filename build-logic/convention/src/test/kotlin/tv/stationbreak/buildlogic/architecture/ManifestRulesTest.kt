// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestRulesTest {
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
}
