// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * R-PLAT-1: `tools:overrideLibrary` lets a library that needs a newer minSdk than 25 into the app, where it crashes
 * on the Insignia. Without it the manifest merger always fails on such a library.
 */
object ManifestRules {
    private const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"

    fun overridesLibraryMinSdk(manifest: File): Boolean {
        val document = parser().newDocumentBuilder().parse(manifest)
        val elements = document.getElementsByTagName("*")
        return (0 until elements.length).any {
            (elements.item(it) as Element).hasAttributeNS(TOOLS_NAMESPACE, "overrideLibrary")
        }
    }

    // Namespace-aware, so any prefix bound to the tools namespace counts and comments don't. No DTDs or external
    // entities: a manifest is never allowed to make the build read other files. Rejecting the DOCTYPE is enough on
    // its own; the rest is defence in depth should a different parser ignore it.
    private fun parser() =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
}
