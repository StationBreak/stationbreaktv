// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import tv.stationbreak.data.library.RoomLibraryRepository
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Spec 06 task 2.13 (Security: Backup): the saved library is a SQLite file in the app's `databases` directory, which is
 * the backup rules' `database` domain. It holds the owner's library titles, so it must never be backed up or copied to
 * another device. [BackupRulesTest] keeps every domain excluded; this test names the cache's own domain, so removing
 * just that line, or moving the file out of that domain, fails here with a message that says why.
 */
class BackupExclusionTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    // The database domain, and the device-to-device variant of it on API 31+.
    private val databaseDomains = setOf("database", "device_database")

    private fun parse(path: String): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }.newDocumentBuilder()
            .parse(File(path))

    private fun elements(
        parent: Element,
        tag: String,
    ): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    }

    private fun excludedWholeDomains(section: Element): Set<String> =
        elements(section, "exclude")
            .filter { it.getAttribute("path") == "." }
            .map { it.getAttribute("domain") }
            .toSet()

    @Test
    fun backupIsOffForTheWholeApp() {
        val app = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(androidNamespace, "allowBackup"))
    }

    @Test
    fun theOldBackupRulesExcludeTheDatabaseDomain() {
        val root = parse("src/main/res/xml/backup_rules.xml").documentElement
        val excluded = excludedWholeDomains(root)
        assertTrue("backup_rules.xml must exclude the database domain", excluded.containsAll(databaseDomains))
        assertEquals("backup_rules.xml includes something", emptyList<Element>(), elements(root, "include"))
    }

    @Test
    fun theDataExtractionRulesExcludeTheDatabaseDomainFromCloudBackupAndDeviceTransfer() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml").documentElement
        listOf("cloud-backup", "device-transfer").forEach { sectionName ->
            val section = elements(root, sectionName).single()
            val excluded = excludedWholeDomains(section)
            assertTrue("$sectionName must exclude the database domain", excluded.containsAll(databaseDomains))
            assertEquals("$sectionName includes something", emptyList<Element>(), elements(section, "include"))
        }
    }

    @Test
    fun theLibraryDatabaseIsCreatedByNameSoItLivesInTheDatabaseDomain() {
        // A bare name resolves to <app data>/databases/<name>; a path would put the file in some other domain.
        val name = RoomLibraryRepository.DATABASE_NAME
        assertFalse(name.contains('/') || name.contains('\\'))
        assertTrue(name.endsWith(".db"))
    }
}
