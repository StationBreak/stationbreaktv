// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.domain.lineup.SERVER
import tv.stationbreak.core.engine.lineup.RecipeResolver
import tv.stationbreak.core.engine.lineup.StarterCatalog
import tv.stationbreak.core.model.CollectionNames
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.OrderingCaps
import tv.stationbreak.core.rules.RulesReader
import tv.stationbreak.core.rules.RulesResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A copy opened from real rules saves back as they were: every starter recipe and every example file. */
class DraftRulesRealDataTest {
    // Caps the file leaves partly or wholly out have defaults, 3 and 1; the draft shows them and writes them out. That
    // is the one reformatting: the schedule is the same, and every other field is written back as it was.
    private fun asWritten(rules: ChannelRules): ChannelRules {
        val caps = rules.effectiveCaps
        val ordering = rules.ordering
        return if (caps != null && ordering != null) {
            val filled = OrderingCaps(caps.effectiveSeries, caps.effectiveFilm, ordering.caps?.extras ?: caps.extras)
            rules.copy(ordering = ordering.copy(caps = filled))
        } else {
            rules
        }
    }

    private fun roundTrip(rules: ChannelRules): ChannelRules {
        val draft = DraftRules.fromRules(rules, null) { it.key }
        assertEquals(null, draft.problem(), "the draft of ${rules.name} can be saved")
        return DraftRules.toRules(draft, rules.timing.broadcastDay.utcOffsetMinutes, rules)
    }

    @Test
    fun everyStarterRecipeSavesBackAsItWas() {
        val names = CollectionNames(emptyMap())
        val recipes = StarterCatalog.recipes
        assertTrue(recipes.size > 20, "the catalog is read")
        for (recipe in recipes) {
            val rules = RecipeResolver.resolve(recipe, SERVER, 60, names)
            assertEquals(asWritten(rules), roundTrip(rules), recipe.key)
        }
    }

    private fun examples(): List<Pair<String, ChannelRules>> {
        val root = File("../../docs/rules-format/examples")
        check(root.isDirectory) { "examples not found at ${root.absolutePath}" }

        fun jsonIn(dir: File) = dir.listFiles().orEmpty().filter { it.extension == "json" }
        val files = jsonIn(root) + jsonIn(File(root, "v2"))
        // A file the reader finds valid but this app does not schedule (a filler mode) is not one a channel can hold,
        // so the builder never opens it. Anything else that is not readable is a failure.
        val unsupported =
            setOf("filler-between-every-n.json", "filler-pad-to-grid.json", "filler-tag-source.json", "full.json")
        return files.sortedBy { it.path }.mapNotNull { file ->
            val result = RulesReader.read(file.readText())
            if (file.relativeTo(root).path in unsupported) {
                assertIs<RulesResult.Unsupported>(result, "${file.name} is unsupported")
                null
            } else {
                file.relativeTo(root).path to assertIs<RulesResult.Ok>(result, "${file.name} reads").rules
            }
        }
    }

    @Test
    fun everyExampleFileSavesBackAsItWas() {
        val all = examples()
        assertTrue(all.size > 20, "the examples are read: ${all.size}")
        for ((name, rules) in all) assertEquals(asWritten(rules), roundTrip(rules), name)
    }
}
