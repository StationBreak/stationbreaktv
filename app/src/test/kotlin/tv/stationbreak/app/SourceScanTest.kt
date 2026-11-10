// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scan's own helpers: the string-aware comment stripper, and each rule shown to catch a planted violation. */
class SourceScanTest {
    private fun strip(source: String) = SourceScan.stripComments(source)

    private fun hits(
        pattern: Regex,
        source: String,
    ) = pattern.containsMatchIn(strip(source))

    @Test
    fun stripComments_keepsAUrlInAString() {
        val stripped = strip("""val u = "https://example.test/x" // note""")

        assertTrue(stripped, stripped.startsWith("""val u = "https://example.test/x""""))
        assertFalse(stripped, stripped.contains("note"))
    }

    @Test
    fun stripComments_dropsALineCommentAndKeepsTheLineBreak() {
        assertEquals("val a = 1 \nval b = 2", strip("val a = 1 // saveSelection(x)\nval b = 2"))
    }

    @Test
    fun stripComments_dropsBlockAndKdocComments_evenNestedAndMultiLine() {
        val stripped = strip("/** saveSelection\n * clearLibraries /* inner */ still comment\n */\nval a = 1")

        assertFalse(stripped.contains("saveSelection"))
        assertFalse(stripped.contains("clearLibraries"))
        assertFalse(stripped.contains("still"))
        assertTrue(stripped.endsWith("val a = 1"))
    }

    @Test
    fun stripComments_keepsASlashStarInsideAString() {
        assertEquals("""val g = "a/*b" ; saveSelection()""", strip("""val g = "a/*b" ; saveSelection()"""))
    }

    @Test
    fun stripComments_handlesEscapedQuotesCharLiteralsAndRawStrings() {
        assertTrue(strip("""val s = "q\" // kept" // dropped""").contains("// kept"))
        assertFalse(strip("""val s = "q\" // kept" // dropped""").contains("dropped"))
        assertFalse(strip("""val c = '"' // dropped""").contains("dropped"))
        val raw = strip("val r = \"\"\"a // kept \"b\" \"\"\" // dropped")
        assertTrue(raw.contains("// kept"))
        assertFalse(raw.contains("dropped"))
    }

    @Test
    fun writeCalls_catchesAReceiverCallABareCallAndAMethodReference_butNotADeclaration() {
        val p = SourceScan.selectionWrites
        assertTrue(hits(p, "store.saveSelection(a, b)"))
        assertTrue(hits(p, "with(store) { saveSelection(a, b) }"))
        assertTrue(hits(p, "run(store::saveSelection)"))
        assertTrue(hits(p, "store.clearLibraries()"))
        assertTrue(hits(p, "val f = ::clearLibraries"))
        assertFalse(hits(p, "override suspend fun saveSelection(a: A): Boolean ="))
        assertFalse(hits(p, "// store.saveSelection(a)"))
    }

    @Test
    fun cacheRowWrites_catchesBareCallsAndReferences_butNotDeclarations() {
        val p = SourceScan.cacheRowWrites
        assertTrue(hits(p, "cache.applySelection(x)"))
        assertTrue(hits(p, "applySelection(x)"))
        assertTrue(hits(p, "run(cache::landAdd)"))
        assertFalse(hits(p, "override suspend fun revertUnproven(x: X)"))
        assertFalse(hits(p, "dao.applySelectionRows(x)"))
    }

    @Test
    fun daoRowWriters_areCaught() {
        val p = SourceScan.daoRowWriters
        for (name in listOf("applySelectionRows", "deleteLeavers", "clearStamp", "revertStamp")) {
            assertTrue(name, hits(p, "dao.$name(a)"))
            assertTrue(name, hits(p, "run(dao::$name)"))
            assertFalse(name, hits(p, "protected abstract suspend fun $name(a: A)"))
        }
    }

    @Test
    fun resumeAndGate_areCaught() {
        assertTrue(hits(SourceScan.resumeAndGate, "fun f(r: SelectionResume)"))
        assertTrue(hits(SourceScan.resumeAndGate, "val g = ResumeGate()"))
        assertFalse(hits(SourceScan.resumeAndGate, "/** the SelectionResume */ val x = 1"))
    }

    @Test
    fun appStart_isCaught() {
        assertTrue(hits(SourceScan.appStart, "@AppStart val x: Y"))
        assertTrue(hits(SourceScan.appStart, "import tv.stationbreak.app.di.AppStart"))
        assertFalse(hits(SourceScan.appStart, "val x = SelectionStart()"))
    }

    @Test
    fun storeParameter_catchesPlainLazyProviderAndSplitLines() {
        val p = SourceScan.storeParameter
        assertEquals(1, p.findAll(strip("fun a(store: SelectionStore)")).count())
        assertEquals(1, p.findAll(strip("fun a(store: Lazy<SelectionStore>)")).count())
        assertEquals(1, p.findAll(strip("fun a(store: Provider<SelectionStore>)")).count())
        val split = "fun a(\n    store:\n        dagger.Lazy<\n            SelectionStore,\n        >,\n)"
        assertEquals(1, p.findAll(strip(split)).count())
        assertEquals(0, p.findAll(strip("fun a(store: SelectionStoreFake)")).count())
    }
}
