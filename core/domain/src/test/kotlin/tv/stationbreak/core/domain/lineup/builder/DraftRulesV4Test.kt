// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Ordering
import tv.stationbreak.core.rules.RulesReader
import tv.stationbreak.core.rules.RulesResult
import tv.stationbreak.core.rules.RulesWriter
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.StudioFold2Gate
import tv.stationbreak.core.rules.Timing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The builder with rules format 4 (docs/adr/0026-studio-fold-2.md, Versioning and Tests): it writes at the rules'
 * write version, never lowers a studio file that says 4, and writes 4 for studio rules once the gate has passed.
 */
class DraftRulesV4Test {
    private val offset = 60
    private val timing = Timing(null, false, 24, null, null, null, BroadcastDay(offset, "06:00"))

    private fun rules(
        version: Int,
        sources: List<Source> = listOf(Source.Studio("Studio A"), Source.Genre("Comedy")),
        ordering: Ordering? = Ordering(OrderingMode.Curated),
        exclusions: List<Source>? = null,
    ) = ChannelRules(
        formatVersion = version,
        name = "Copy",
        seed = Seed(7uL),
        sources = sources,
        exclusions = exclusions,
        maxRating = null,
        ordering = ordering,
        timing = timing,
        filler = null,
    )

    private fun fromRules(rules: ChannelRules) = DraftRules.fromRules(rules, null) { it.key }

    // A save of the custom channel whose stored rules are [stored].
    private fun save(
        draft: ChannelDraft,
        stored: ChannelRules,
        gatePassed: Boolean = StudioFold2Gate.WRITES_FORMAT_4,
    ) = DraftRules.toRules(draft, offset, RulesTarget.Edit(stored), gatePassed)

    // A new channel copied from the built-in channel whose rules are [carry].
    private fun create(
        draft: ChannelDraft,
        carry: ChannelRules,
        gatePassed: Boolean = StudioFold2Gate.WRITES_FORMAT_4,
    ) = DraftRules.toRules(draft, offset, RulesTarget.NewChannel(carry), gatePassed)

    @Test
    fun theGateHasPassed() {
        assertTrue(StudioFold2Gate.WRITES_FORMAT_4)
    }

    @Test
    fun aFormat4FileWithAStudioSourceAndACuratedOrderKeepsBothAndStays4() {
        val stored = rules(4)
        val draft = fromRules(stored)
        val saved = save(draft, stored)
        assertEquals(stored, saved)
        assertEquals(4, saved.formatVersion)
        assertEquals(OrderingMode.Curated, saved.orderingMode)
        assertEquals(Source.Studio("Studio A"), saved.sources.first())
        // An edit the builder shows keeps it at 4 too.
        val renamed = save(draft.copy(name = "Renamed"), stored)
        assertEquals(4, renamed.formatVersion)
        assertEquals(OrderingMode.Curated, renamed.orderingMode)
    }

    @Test
    fun aFormat4StudioExclusionIsNotLoweredEither() {
        val stored = rules(4, sources = listOf(Source.Genre("Comedy")), exclusions = listOf(Source.Studio("Studio B")))
        val saved = save(fromRules(stored), stored)
        assertEquals(4, saved.formatVersion)
        assertEquals(listOf<Source>(Source.Studio("Studio B")), saved.exclusions)
    }

    @Test
    fun aFormat3StudioFileStays3WithTheGateOffAnd4WithItOn() {
        val stored = rules(3, ordering = null)
        val draft = fromRules(stored)
        assertEquals(3, save(draft, stored, gatePassed = false).formatVersion)
        val after = save(draft, stored, gatePassed = true)
        assertEquals(stored.copy(formatVersion = 4), after)
        assertEquals(after, save(draft, stored), "the app's default: the gate has passed")
    }

    // Task 6: a new studio channel (a copy of a starter with a studio source, whose rules are carried) is written at
    // 5, above ADR-0026's 4 and whatever its gate says.
    @Test
    fun aNewStudioChannelIsWrittenAt5AboveAdr0026s4() {
        val starter = rules(3, ordering = null)
        val copy = fromRules(starter).copy(name = "My copy")
        val saved = create(copy, starter, gatePassed = true)
        assertEquals(5, saved.formatVersion)
        assertEquals(Source.Studio("Studio A"), saved.sources.first())
        assertIs<RulesResult.Ok>(RulesReader.read(RulesWriter.write(saved)))
        assertEquals(5, create(copy, starter, gatePassed = false).formatVersion)
        assertEquals(5, create(copy, starter).formatVersion, "the default")
    }

    @Test
    fun aFormat3StudioFileIsReadAsValid() {
        val text = RulesWriter.write(rules(3))
        val read = assertIs<RulesResult.Ok>(RulesReader.read(text)).rules
        assertEquals(3, read.formatVersion)
        assertEquals(3, read.requiredVersion)
    }

    @Test
    fun withoutStudiosAFormat4FileIsWrittenAtItsRequiredVersion() {
        val stored = rules(4, sources = listOf(Source.Genre("Comedy")))
        for (gate in listOf(false, true)) {
            assertEquals(3, save(fromRules(stored), stored, gatePassed = gate).formatVersion)
        }
    }

    // Task 6: a new channel is written at 5 (the spread day end), not at the lowest version it needs.
    @Test
    fun aNewChannelIsWrittenAt5NotItsLowestVersion() {
        val comedy = DraftSource.Genre("Comedy", "Comedy")
        val draft = ChannelDraft("New", listOf(comedy), emptyList(), DraftFilters(), kids = false, seed = Seed(9uL))
        val saved = DraftRules.toRules(draft, offset)
        assertEquals(2, saved.requiredVersion)
        assertEquals(5, saved.formatVersion)
        // A new studio channel (a copy of a starter at 3) is at 5 too, above ADR-0026's 4.
        val starter = rules(3, ordering = null)
        val copy = fromRules(starter).copy(name = "Mine")
        assertEquals(5, create(copy, starter).formatVersion)
    }

    // A file that says 5 is never written lower by the builder, whatever it holds.
    @Test
    fun aV5FileEditedInTheBuilderStays5() {
        val plain = rules(5, sources = listOf(Source.Genre("Comedy")), ordering = null)
        val studio = rules(5)
        for (stored in listOf(plain, studio)) {
            val draft = fromRules(stored)
            for (gate in listOf(false, true)) {
                assertEquals(stored, save(draft, stored, gatePassed = gate))
                assertEquals(5, save(draft.copy(name = "Renamed"), stored, gate).formatVersion)
            }
        }
    }
}
