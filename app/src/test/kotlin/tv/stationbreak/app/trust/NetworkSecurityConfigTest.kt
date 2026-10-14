// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Spec 03, Unit 4 (R16): the network security config trusts the system store plus the bundled roots and nothing
 * else, permits cleartext in exactly one place, and carries exactly one lint suppression, scoped to that place.
 * Reads every source set, so a flavor or build-type copy of the config can't slip past.
 */
class NetworkSecurityConfigTest {
    private val sources = File("src")
    private val toolsNamespace = "http://schemas.android.com/tools"
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    private fun configFiles(): List<File> =
        sources
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .flatMap { sourceSet ->
                File(sourceSet, "res").walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
            }.filter { it.parentFile.name == "xml" && it.name.startsWith("network_security_config") }

    private fun parse(file: File): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }.newDocumentBuilder()
            .parse(file)

    private fun elements(
        document: Document,
        tag: String,
    ): List<Element> {
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun allElements(document: Document): List<Element> = elements(document, "*")

    private fun bundledRawNames(): Set<String> =
        File("src/main/res/raw")
            .listFiles { file -> file.extension == "pem" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .toSet()

    @Test
    fun theConfigExistsAndTheManifestPointsAtIt() {
        assertEquals(
            listOf("src/main/res/xml/network_security_config.xml"),
            configFiles().map { it.invariantSeparatorsPath },
        )
        val manifest = parse(File("src/main/AndroidManifest.xml"))
        val application = elements(manifest, "application").single()
        assertEquals(
            "@xml/network_security_config",
            application.getAttributeNS(androidNamespace, "networkSecurityConfig"),
        )
    }

    @Test
    fun trustsTheSystemStorePlusExactlyTheBundledRoots() {
        val sourcesSeen =
            configFiles().flatMap { file -> elements(parse(file), "certificates").map { it.getAttribute("src") } }
        val expected = listOf("system") + bundledRawNames().sorted().map { "@raw/$it" }
        assertEquals(expected, sourcesSeen.sortedBy { if (it == "system") "" else it })
        assertEquals(2, bundledRawNames().size)
    }

    @Test
    fun noUserInstalledCertificateAuthorityAnywhere() {
        configFiles().forEach { file ->
            val text = file.readText()
            assertFalse("${file.path} trusts user CAs", Regex("""src\s*=\s*["']user["']""").containsMatchIn(text))
        }
    }

    @Test
    fun noDebugOverridesOutsideADebugSourceSet() {
        val offenders =
            configFiles().filter { file ->
                val sourceSet = file.invariantSeparatorsPath.split("/")[1]
                elements(parse(file), "debug-overrides").isNotEmpty() && sourceSet != "debug"
            }
        assertTrue("debug-overrides in $offenders", offenders.isEmpty())
    }

    @Test
    fun cleartextIsPermittedInExactlyOnePlace() {
        val permitting =
            configFiles().flatMap { file ->
                allElements(parse(file)).filter { it.hasAttribute("cleartextTrafficPermitted") }
            }
        assertEquals(1, permitting.size)
        assertEquals("base-config", permitting.single().tagName)
        assertEquals("true", permitting.single().getAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun exactlyOneLintSuppressionScopedToTheCleartextElement() {
        val suppressions =
            configFiles().flatMap { file ->
                allElements(parse(file)).filter { it.hasAttributeNS(toolsNamespace, "ignore") }
            }
        assertEquals(1, suppressions.size)
        val suppressed = suppressions.single()
        assertEquals("InsecureBaseConfiguration", suppressed.getAttributeNS(toolsNamespace, "ignore"))
        assertTrue(suppressed.hasAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun noCustomTrustMachineryInTheMainSources() {
        val forbidden = Regex("""X509TrustManager|HostnameVerifier|SSLSocketFactory|setDefaultHostnameVerifier""")
        val offenders =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && forbidden.containsMatchIn(it.readText()) }
                .toList()
        assertTrue("custom TLS code in $offenders", offenders.isEmpty())
    }
}
