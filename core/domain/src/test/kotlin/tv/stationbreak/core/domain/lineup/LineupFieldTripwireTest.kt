// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.repair.ChannelRepair
import tv.stationbreak.core.domain.lineup.repair.KeptBlock
import tv.stationbreak.core.domain.lineup.repair.LostFix
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.Recipe
import tv.stationbreak.core.rules.ChannelRules
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #589: a day taken over from an earlier build is right only while every field a day reads is in its `DayInput`. This
 * pins the fields of the types a day reads. A new property fails it until someone either adds a change for it to
 * `changesOf` in `DayInputReuseTest` (and `DayInputsDifferentialTest` where relevant) and the day input, or says
 * here that no day reads it.
 */
class LineupFieldTripwireTest {
    private fun fieldsOf(type: Class<*>): Set<String> =
        type.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && '$' !in it.name }
            .map { it.name }
            .toSet()

    private fun pinned(
        type: Class<*>,
        expected: Set<String>,
    ) {
        assertEquals(
            expected,
            fieldsOf(type),
            "${type.simpleName} changed. If a day reads the new field, add a change of it to `changesOf` in " +
                "DayInputReuseTest (and DayInputsDifferentialTest where relevant) and to LineupDays.inputsOf and " +
                "DayInput; if no day reads it, say so by adding it to the list here.",
        )
    }

    @Test
    fun lineup() =
        pinned(
            Lineup::class.java,
            // Read by a day: serverId, utcOffsetMinutes, channels, useMyCollections, curationBasis.
            // No day reads: userId, catalogVersion (inputsOf has the catalog's own), finalised, viewerChoicesKnown.
            setOf(
                "serverId",
                "userId",
                "catalogVersion",
                "utcOffsetMinutes",
                "finalised",
                "channels",
                "useMyCollections",
                "curationBasis",
                "viewerChoicesKnown",
            ),
        )

    @Test
    fun lineupChannel() =
        pinned(
            LineupChannel::class.java,
            // Read by a day: id, state, source, onSinceMs, leavesAtMs, and the number (the Kids band, the claim
            // order) through defaultNumber and numberOverride. No day reads: nameOverride, chosenByViewer, shelfReason.
            setOf(
                "id",
                "defaultNumber",
                "numberOverride",
                "nameOverride",
                "state",
                "source",
                "onSinceMs",
                "chosenByViewer",
                "leavesAtMs",
                "shelfReason",
            ),
        )

    @Test
    fun starter() =
        pinned(
            ChannelSource.Starter::class.java,
            // All read: the recipe (curation), edited, the rules, and the previous rules and their boundary.
            setOf("recipeKey", "edited", "rules", "previousRules", "rulesFromMs"),
        )

    @Test
    fun custom() = pinned(ChannelSource.Custom::class.java, setOf("rules"))

    @Test
    fun leftovers() = pinned(ChannelSource.Leftovers::class.java, setOf("recipeKey", "maxRating"))

    @Test
    fun unusable() = pinned(ChannelSource.Unusable::class.java, setOf("rawJson"))

    @Test
    fun recipe() =
        pinned(
            Recipe::class.java,
            // Read by a day: band, number, optIn, sources, exclusions, maxRating, ordering, collectionHints, filters,
            // theme (the curation, the opt-in withholding, the leftovers' ceilings). No day reads: name, personality
            // (text), and the key beyond which recipe a starter is.
            setOf(
                "key",
                "name",
                "personality",
                "band",
                "number",
                "optIn",
                "sources",
                "exclusions",
                "maxRating",
                "ordering",
                "collectionHints",
                "filters",
                "theme",
            ),
        )

    @Test
    fun channelRules() =
        pinned(
            ChannelRules::class.java,
            // The name is no day's input (inputsOf and the claims leave it out); the rest is.
            setOf(
                "formatVersion",
                "name",
                "seed",
                "sources",
                "exclusions",
                "maxRating",
                "ordering",
                "timing",
                "filler",
                "filters",
                "extras",
            ),
        )

    // The repair record (spec 26) is stored, so a new field is a new file version (and a change to RepairsCodec).
    // No day reads these fields directly: a repaired day reads them through TodayRepairs.digestFor, which writes each
    // one into the digest (the canonical text in TodayRepairs.kt), so a new field must be added there too.
    @Test
    fun todayRepairs() = pinned(TodayRepairs::class.java, setOf("selection", "fixes", "lostCheck", "lastSeq"))

    @Test
    fun channelRepair() =
        pinned(
            ChannelRepair::class.java,
            setOf(
                "channel",
                "n",
                "dayEndMs",
                "atMs",
                "seq",
                "libraries",
                "keptFromMs",
                "pinEndMs",
                "pinLibrary",
                "blocks",
                "tailHidden",
            ),
        )

    @Test
    fun lostFix() =
        pinned(
            LostFix::class.java,
            setOf("channel", "n", "dayEndMs", "blockStartMs", "blockEndMs", "gone", "pieces", "seq"),
        )

    @Test
    fun keptBlock() = pinned(KeptBlock::class.java, setOf("startMs", "endMs", "itemId", "mediaStartMs"))
}
