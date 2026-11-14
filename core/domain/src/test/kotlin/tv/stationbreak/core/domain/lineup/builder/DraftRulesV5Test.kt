// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.OrderingCaps
import tv.stationbreak.core.rules.RulesReader
import tv.stationbreak.core.rules.RulesResult
import tv.stationbreak.core.rules.RulesWriter
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The builder with rules format 5, the spread day end (Task 6): a new channel, copies included, is written at 5 (a
 * floor over ADR-0026's write version), and an edit of a custom channel keeps ADR-0026's write version, so a saved
 * channel keeps its version and a v5 file stays 5.
 */
class DraftRulesV5Test {
    private val offset = 60
    private val timing = Timing(null, false, 24, null, null, null, BroadcastDay(offset, "06:00"))
    private val comedy = DraftSource.Genre("Comedy", "Comedy")

    private fun rules(
        version: Int,
        sources: List<Source> = listOf(Source.Studio("Studio A"), Source.Genre("Comedy")),
        ordering: Ordering? = null,
    ) = ChannelRules(
        formatVersion = version,
        name = "Stored",
        seed = Seed(7uL),
        sources = sources,
        exclusions = null,
        maxRating = null,
        ordering = ordering,
        timing = timing,
        filler = null,
    )

    private fun fromRules(rules: ChannelRules) = DraftRules.fromRules(rules, null) { it.key }

    private fun newDraft(): ChannelDraft {
        val seed = Seed(9uL)
        return ChannelDraft("New", listOf(comedy), emptyList(), DraftFilters(), kids = false, seed = seed)
    }

    private fun assertReadable(rules: ChannelRules) {
        assertEquals(rules, assertIs<RulesResult.Ok>(RulesReader.read(RulesWriter.write(rules))).rules)
    }

    @Test
    fun aNewChannelIsWrittenAtV5() {
        val saved = DraftRules.toRules(newDraft(), offset, RulesTarget.NewChannel(null))
        assertEquals(5, saved.formatVersion)
        assertTrue(saved.requiredVersion < 5, "5 is the floor, not what the rules need")
        assertTrue(saved.spreadsDayEnd)
        assertReadable(saved)
        // A new studio channel (a copy of a studio starter that says 4) is at 5 too.
        // Above ADR-0026's 4, whatever its gate says.
        val starter = rules(4)
        val copy = fromRules(starter).copy(name = "Mine")
        val studio = DraftRules.toRules(copy, offset, RulesTarget.NewChannel(starter), gatePassed = true)
        assertEquals(5, studio.formatVersion)
        assertEquals(studio, DraftRules.toRules(copy, offset, RulesTarget.NewChannel(starter), gatePassed = false))
        assertEquals(Source.Studio("Studio A"), studio.sources.first())
        assertReadable(studio)
    }

    @Test
    fun aNewStudioChannelBeforeAdr0026sGateIsV5AndMatchesUnderFold2() {
        val starter = rules(3)
        val copy = fromRules(starter).copy(name = "Mine")
        // ADR-0026's write version alone, before its gate, would be 3 (studio fold 1).
        val built = DraftRules.toRules(copy, offset, RulesTarget.Edit(starter), gatePassed = false)
        assertEquals(3, built.formatVersion)

        val saved = DraftRules.toRules(copy, offset, RulesTarget.NewChannel(starter), gatePassed = false)

        assertEquals(5, saved.formatVersion)
        assertTrue(saved.matchesStudiosByFold2)
        assertEquals(built.copy(formatVersion = 5), saved, "only the version differs")
    }

    @Test
    fun aCopiedChannelIsWrittenAtV5() {
        // A copy of a version 1 starter in chronological order: the kept order is carried, and the copy is at 5.
        val chronological = Ordering(OrderingMode.Chronological)
        val starter = rules(1, sources = listOf(Source.Genre("Comedy")), ordering = chronological)
        val copy = fromRules(starter).copy(name = "My copy")
        val kept = DraftRules.toRules(copy, offset, RulesTarget.NewChannel(starter))
        assertEquals(5, kept.formatVersion)
        assertEquals(OrderingMode.Chronological, kept.orderingMode)
        assertReadable(kept)
        // With the builder's settings instead, nothing is carried, and it is still at 5.
        val plain = DraftRules.toRules(copy.copy(kept = null), offset, RulesTarget.NewChannel(starter))
        assertEquals(5, plain.formatVersion)
        assertEquals(OrderingMode.Shuffle, plain.orderingMode)
    }

    @Test
    fun savingAV2CustomChannelStillLowersToV1WhenItCan() {
        // A version 2 file that uses only version 1 features: an edit is written at the lowest version (ADR-0022).
        val stored = rules(2, sources = listOf(Source.Genre("Comedy")), ordering = Ordering(OrderingMode.Chronological))
        assertEquals(1, stored.requiredVersion)

        val saved = DraftRules.toRules(fromRules(stored), offset, RulesTarget.Edit(stored))

        assertEquals(1, saved.formatVersion)
        assertEquals(stored.copy(formatVersion = 1), saved)
    }

    @Test
    fun savingAV4StudioCustomChannelStaysV4() {
        val stored = rules(4)
        for (gate in listOf(false, true)) {
            val saved = DraftRules.toRules(fromRules(stored), offset, RulesTarget.Edit(stored), gate)
            assertEquals(stored, saved, "gate $gate")
            val renamed = DraftRules.toRules(fromRules(stored).copy(name = "Renamed"), offset, RulesTarget.Edit(stored))
            assertEquals(4, renamed.formatVersion, "not raised to 5")
        }
    }

    @Test
    fun savingAV5CustomChannelStaysV5() {
        val capped = Ordering(OrderingMode.Shuffle, OrderingCaps(3, 1))
        val plain = rules(5, sources = listOf(Source.Genre("Comedy")), ordering = capped)
        for (stored in listOf(plain, rules(5))) {
            val draft = fromRules(stored)
            assertEquals(stored, DraftRules.toRules(draft, offset, RulesTarget.Edit(stored)))
            val renamed = DraftRules.toRules(draft.copy(name = "Renamed"), offset, RulesTarget.Edit(stored))
            assertEquals(5, renamed.formatVersion)
        }
    }
}
