// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
@file:Suppress("MagicNumber") // Test data: the numbers are the fixture's values.

package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.Source
import tv.stationbreak.core.rules.Timing
import kotlin.test.assertIs

/** A small snapshot with every field of every type set to something, including the odd values. */
internal object SnapshotFixture {
    fun id(n: Int): CanonicalId = CanonicalId.parse(n.toString(16).padStart(32, '0'))

    val library = id(0xA0)
    val showId = id(0x500)

    private fun film() =
        RuleItem(
            item =
                ScheduleItem(
                    id(0x10),
                    ScheduleItemKind.Movie,
                    5_400_000L,
                    1_700_000_000_123L,
                    null,
                    "1999-12-31",
                    1999,
                    null,
                    null,
                    null,
                ),
            libraryId = library,
            seasonId = null,
            officialRating = "",
            genres = listOf("Drama", "Crime"),
            tags = listOf("slow burn"),
            studios = listOf("Studio One"),
            name = "A Film",
        )

    private fun episode() =
        RuleItem(
            item = ScheduleItem(id(0x20), ScheduleItemKind.Episode, 1_320_000L, 5L, 9L, null, null, showId, 2, 7),
            libraryId = library,
            seasonId = id(0x600),
            officialRating = " ",
            genres = emptyList(),
            tags = emptyList(),
            studios = emptyList(),
            name = null,
        )

    /** "" and " " ratings, a null name, a collection with every kind of member and one with none. */
    fun full(): RuleSnapshot =
        RuleSnapshot(
            cutoffMs = 1_800_000_000_000L,
            libraries = setOf(library, id(0xA1)),
            items = listOf(film(), episode()),
            series = mapOf(showId to RuleSeries(showId, null, listOf("Comedy"), listOf("t"), listOf("Net"), "A Show")),
            collections =
                mapOf(
                    id(0x700) to
                        RuleCollection(
                            id(0x700),
                            setOf(id(0x10)),
                            setOf(id(0x20)),
                            setOf(showId),
                            setOf(id(0x600)),
                            "A Collection",
                        ),
                    id(0x701) to RuleCollection(id(0x701), emptySet(), emptySet(), emptySet(), emptySet(), null),
                ),
            collectionsSynced = false,
        )

    fun customRules() =
        ChannelRules(
            formatVersion = 1,
            name = null,
            seed = Seed(0uL),
            sources = listOf(Source.Genre("Drama")),
            exclusions = null,
            maxRating = null,
            ordering = null,
            timing = Timing(null, null, null, null, null, null, BroadcastDay(0, null)),
            filler = null,
        )

    fun text(snapshot: RuleSnapshot = full()): String = AuditSnapshotCodec.write(AuditInput(snapshot, null))

    fun read(text: String): AuditInput = assertIs<SnapshotResult.Ok>(AuditSnapshotCodec.read(text)).input

    fun invalid(text: String): String = assertIs<SnapshotResult.Invalid>(AuditSnapshotCodec.read(text)).reason
}
