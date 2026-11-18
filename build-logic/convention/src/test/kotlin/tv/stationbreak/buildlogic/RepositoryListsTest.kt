// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The root build and build-logic each declare their repositories (#182). Repositories are a supply-chain surface, and
 * a plugin or library can resolve differently from another repository while the dependency verification file holds
 * one checksum per artifact (ADR-0013), so both builds must list the same ones in the same order. This fails when the
 * two lists drift.
 */
class RepositoryListsTest {
    private val root = File(System.getProperty("rootSettings")).readText()
    private val buildLogic = File(System.getProperty("buildLogicSettings")).readText()

    @Test
    fun `plugin repositories match the root build's`() {
        val expected = repositoriesIn(root, "pluginManagement")
        assertTrue(expected.isNotEmpty(), "no pluginManagement repositories found in the root settings")
        assertEquals(expected, repositoriesIn(buildLogic, "pluginManagement"))
    }

    @Test
    fun `dependency repositories match the root build's`() {
        val expected = repositoriesIn(root, "dependencyResolutionManagement")
        assertTrue(expected.isNotEmpty(), "no dependencyResolutionManagement repositories found in the root settings")
        assertEquals(expected, repositoriesIn(buildLogic, "dependencyResolutionManagement"))
    }

    @Test
    fun `the parser sees an added, a removed and a reordered repository`() {
        val base = settings("google()", "mavenCentral()")
        val list = repositoriesIn(base, "dependencyResolutionManagement")
        assertEquals(listOf("google()", "mavenCentral()"), list)
        val drifted =
            listOf(
                settings("google()", "mavenCentral()", "maven(\"https://example.com/repo\")"),
                settings("google()"),
                settings("mavenCentral()", "google()"),
            )
        for (other in drifted) {
            assertTrue(list != repositoriesIn(other, "dependencyResolutionManagement"), other)
        }
        // Two different URLs must not compare equal by being cut at "//".
        val one = repositoriesIn(settings("maven(\"https://one.example/repo\")"), "dependencyResolutionManagement")
        val two = repositoriesIn(settings("maven(\"https://two.example/repo\")"), "dependencyResolutionManagement")
        assertEquals(listOf("maven(\"https://one.example/repo\")"), one)
        assertTrue(one != two)
    }

    @Test
    fun `comments and blank lines don't count`() {
        val commented = settings("// A note.", "google()  // trailing note", "", "mavenCentral()")
        assertEquals(listOf("google()", "mavenCentral()"), repositoriesIn(commented, "dependencyResolutionManagement"))
    }

    private fun settings(vararg lines: String): String =
        "dependencyResolutionManagement {\n    repositories {\n" + lines.joinToString("\n") { "        $it" } +
            "\n    }\n}\n"

    // A `//` at the start of a line or after a space: a URL's "https://" is kept.
    private val comment = Regex("""(^|\s)//.*$""")

    /** The entries of the `repositories { }` inside the top-level [block], one per line, comments dropped. */
    private fun repositoriesIn(
        settings: String,
        block: String,
    ): List<String> {
        val outer = bodyOf(settings, Regex("""(?m)^$block\s*\{""")) ?: return emptyList()
        val repositories = bodyOf(outer, Regex("""\brepositories\s*\{""")) ?: return emptyList()
        return repositories
            .lines()
            .map { it.replace(comment, "").trim() }
            .filter { it.isNotEmpty() }
    }

    /** The text between the brace [opening] ends with and its matching closing brace, or null if there is none. */
    private fun bodyOf(
        text: String,
        opening: Regex,
    ): String? {
        val start = opening.find(text)?.range?.last ?: return null
        var depth = 1
        for (i in start + 1 until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start + 1, i)
            }
        }
        return null
    }
}
