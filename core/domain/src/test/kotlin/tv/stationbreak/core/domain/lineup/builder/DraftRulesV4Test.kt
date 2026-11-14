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
 * write version, never lowers a studio file that says 4, and writes 4 for studio rules once the gate has passed. These
 * are edits (saves); a new channel is written at 5 whatever the gate says (`DraftRulesV5Test`).
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
