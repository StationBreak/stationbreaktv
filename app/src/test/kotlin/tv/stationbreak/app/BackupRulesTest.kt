// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Spec 04, Unit 2: nothing of the app leaves the device by backup or device transfer, so the Keystore blobs and the
 * session can't be lifted from a backup. The manifest turns backup off and points both rule files, and every domain is
 * excluded in `full-backup-content`, `cloud-backup` and `device-transfer`. The test fails if any of them is removed or
 * loosened. It reads every source set's manifest, so a flavor or build-type copy can't slip past.
 */
class BackupRulesTest {
    private val sources = File("src")
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    // Every domain the platform defines for the backup rules (API 23+ and 31+ include the device_* ones).
    private val allDomains =
        setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )

    private fun manifests(): List<File> =
        sources
            .listFiles { file -> file.isDirectory }
            .orEmpty()
            .map { File(it, "AndroidManifest.xml") }
            .filter { it.isFile }

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

    private fun children(
        parent: Element,
        tag: String,
    ): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    }

    private fun applications(): Map<String, List<Element>> =
        manifests().associate { manifest ->
            val nodes = parse(manifest).getElementsByTagName("application")
            manifest.invariantSeparatorsPath to (0 until nodes.length).map { nodes.item(it) as Element }
        }

    private val mainApplication: Element
        get() = applications().getValue("src/main/AndroidManifest.xml").single()

    private fun rules(name: String): Document = parse(File("src/main/res/xml/$name.xml"))

    // The excluded domains of one rule section; every entry must be a whole-domain `<exclude path=".">`.
    private fun excludedDomains(section: Element): Set<String> {
        val nodes = section.childNodes
        val entries = (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
        val tags = entries.map { it.tagName }
        assertTrue("only <exclude> entries allowed, found $tags", tags.all { it == "exclude" })
        entries.forEach {
            val domain = it.getAttribute("domain")
            assertEquals("exclude of $domain is not the whole domain", ".", it.getAttribute("path"))
        }
        return entries.map { it.getAttribute("domain") }.toSet()
    }

    @Test
    fun backupIsOffInTheMainManifestAndEverySourceSetIsAbsentOrExactlyFalse() {
        assertEquals("false", mainApplication.getAttributeNS(androidNamespace, "allowBackup"))
        applications().forEach { (path, apps) ->
            apps.forEach {
                // Absent, or exactly "false": a placeholder such as ${x} or "TRUE" is not good enough.
                val value = it.getAttributeNS(androidNamespace, "allowBackup")
                val ok = !it.hasAttributeNS(androidNamespace, "allowBackup") || value == "false"
                assertTrue("$path sets allowBackup to \"$value\"", ok)
            }
        }
    }

    @Test
    fun noOtherSourceSetOverridesARuleFile() {
        val overrides =
            sources
                .listFiles { file -> file.isDirectory && file.name != "main" }
                .orEmpty()
                .flatMap { sourceSet ->
                    listOf("backup_rules.xml", "data_extraction_rules.xml").map { File(sourceSet, "res/xml/$it") }
                }.filter { it.exists() }
                .map { it.invariantSeparatorsPath }
        assertEquals(emptyList<String>(), overrides)
    }

    @Test
    fun noOtherSourceSetDeclaresABackupAgentOrItsOwnRuleFiles() {
        applications().filterKeys { it != "src/main/AndroidManifest.xml" }.forEach { (path, apps) ->
            apps.forEach { app ->
                listOf("backupAgent", "fullBackupContent", "dataExtractionRules").forEach {
                    assertTrue("$path sets $it", !app.hasAttributeNS(androidNamespace, it))
                }
            }
        }
        assertTrue("main declares a backupAgent", !mainApplication.hasAttributeNS(androidNamespace, "backupAgent"))
    }

    @Test
    fun theManifestPointsAtBothRuleFiles() {
        assertEquals("@xml/backup_rules", mainApplication.getAttributeNS(androidNamespace, "fullBackupContent"))
        val extraction = mainApplication.getAttributeNS(androidNamespace, "dataExtractionRules")
        assertEquals("@xml/data_extraction_rules", extraction)
    }

    @Test
    fun fullBackupContentExcludesEveryDomain() {
        val root = rules("backup_rules").documentElement
        assertEquals("full-backup-content", root.tagName)
        assertEquals(allDomains, excludedDomains(root))
    }

    @Test
    fun dataExtractionRulesExcludeEveryDomainFromCloudBackupAndDeviceTransfer() {
        val root = rules("data_extraction_rules").documentElement
        assertEquals("data-extraction-rules", root.tagName)
        val sections = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(listOf("cloud-backup", "device-transfer"), sections.map { it.tagName })
        sections.forEach { section ->
            assertTrue(
                "${section.tagName} must not opt in to disabling encryption",
                !section.hasAttribute("disableIfNoEncryptionCapabilities"),
            )
            assertEquals("${section.tagName} leaves a domain in", allDomains, excludedDomains(section))
            assertEquals(emptyList<Element>(), children(section, "include"))
        }
    }
}
