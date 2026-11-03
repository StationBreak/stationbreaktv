// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Spec 23, Resolution 15: the extras filter (`extras-1`). Neutral names only. */
class ExtrasFilterTest {
    private val show = idOf(0x500)
    private val min = 60_000L

    @Suppress("LongParameterList") // A test builder: one defaulted parameter per field a test varies.
    private fun entry(
        n: Int,
        name: String? = null,
        runtimeMs: Long = 22 * min,
        kind: ScheduleItemKind = ScheduleItemKind.Episode,
        series: CanonicalId? = show,
        season: Int? = 1,
        number: Int? = n,
    ): RuleItem =
        RuleItem(
            item =
                ScheduleItem(
                    id = idOf(n),
                    kind = kind,
                    runtimeMs = runtimeMs,
                    createdAtMs = 0L,
                    deletedAtMs = null,
                    premiereDate = null,
                    productionYear = null,
                    seriesId = if (kind == ScheduleItemKind.Movie) null else series,
                    seasonNumber = if (kind == ScheduleItemKind.Movie) null else season,
                    episodeNumber = if (kind == ScheduleItemKind.Movie) null else number,
                ),
            libraryId = LIB,
            seasonId = null,
            officialRating = "TV-G",
            genres = listOf("Drama"),
            tags = emptyList(),
            name = name,
        )

    private fun snapshot(vararg items: RuleItem) =
        RuleSnapshot(
            cutoffMs = 5L,
            libraries = setOf(LIB),
            items = items.toList(),
            series = emptyMap(),
            collections = emptyMap(),
            collectionsSynced = true,
        )

    private fun keptIds(result: FilteredSnapshot) = result.snapshot.items.map { it.item.id }

    @Test
    fun creditlessOpeningsAndEndingsAreDropped() {
        val names =
            listOf(
                "NCOP1",
                "NCED 2",
                "Show A NCOP",
                "Show A (Creditless OP)",
                "Show A - NC OP2",
                "nc ed01",
                "OP1",
                "ED3",
                "Opening",
                "The Ending",
                "Trailer 1",
                "Show A Featurette",
                "Show A - Creditless Ending",
                "ncop",
            )
        for (name in names) {
            val result = ExtrasFilter.filter(snapshot(entry(1, name = name, runtimeMs = 2 * min, number = null)))
            assertTrue(result.snapshot.items.isEmpty(), name)
            assertEquals(mapOf(ExtrasReason.NamedExtra to 1), result.dropped, name)
        }
    }

    @Test
    fun bareOpOrEdIsNotAnExtra() {
        val bare = listOf("OP", "ED", "Op Ed", "Show A OP", "Show A ED", "nc op", "nc ed", "Shop", "Opus 1", "Bed2x")
        for (name in bare + "Send3") assertFalse(ExtrasFilter.isNamedExtra(name), name)
        assertTrue(ExtrasFilter.isNamedExtra("Show A OP1"))
        assertTrue(ExtrasFilter.isNamedExtra("Show A (ED2)"))
    }

    @Test
    fun anEpisodeCalledOpeningNightAirs() {
        val result = ExtrasFilter.filter(snapshot(entry(1, name = "Opening Night", runtimeMs = 22 * min)))
        assertEquals(listOf(idOf(1)), keptIds(result))
        assertTrue(result.dropped.isEmpty())
        // The same name on a short item is still an extra: the name rule is for 10 minutes or less.
        assertEquals(
            mapOf(ExtrasReason.NamedExtra to 1),
            ExtrasFilter.filter(snapshot(entry(2, name = "Opening Night", runtimeMs = 10 * min))).dropped,
        )
        val justOver = ExtrasFilter.filter(snapshot(entry(3, name = "Opening Night", runtimeMs = 10 * min + 1)))
        assertTrue(justOver.dropped.isEmpty())
    }

    @Test
    fun aNullNameMatchesNoPattern() {
        assertFalse(ExtrasFilter.isNamedExtra(null))
        val result = ExtrasFilter.filter(snapshot(entry(1, name = null, runtimeMs = 2 * min)))
        assertEquals(listOf(idOf(1)), keptIds(result))
    }

    @Test
    fun underAMinuteIsDropped() {
        val result =
            ExtrasFilter.filter(
                snapshot(entry(1, runtimeMs = 59_999L), entry(2, runtimeMs = 60_000L), entry(3, runtimeMs = 0L)),
            )
        assertEquals(listOf(idOf(2)), keptIds(result))
        assertEquals(mapOf(ExtrasReason.RuntimeUnderAMinute to 2), result.dropped)
    }

    @Test
    fun limitsAreInclusive() {
        val episodeLimit = ExtrasFilter.EPISODE_LIMIT_MS
        val filmLimit = ExtrasFilter.FILM_LIMIT_MS
        val result =
            ExtrasFilter.filter(
                snapshot(
                    entry(1, runtimeMs = episodeLimit),
                    entry(2, runtimeMs = episodeLimit + 1),
                    entry(3, runtimeMs = filmLimit, kind = ScheduleItemKind.Movie),
                    entry(4, runtimeMs = filmLimit + 1, kind = ScheduleItemKind.Movie),
                    entry(5, runtimeMs = episodeLimit + 1, kind = ScheduleItemKind.Movie),
                ),
            )
        assertEquals(listOf(idOf(1), idOf(3), idOf(5)), keptIds(result))
        assertEquals(mapOf(ExtrasReason.RuntimeOverLimit to 2), result.dropped)
        assertEquals(10_800_000L, episodeLimit)
        assertEquals(16_200_000L, filmLimit)
        assertEquals(60_000L, ExtrasFilter.MIN_RUNTIME_MS)
        assertEquals(600_000L, ExtrasFilter.NAME_RULE_UP_TO_MS)
        assertEquals("extras-1", ExtrasFilter.VERSION_NAME)
    }

    @Test
    fun theLongestDuplicateIsKept() {
        val result =
            ExtrasFilter.filter(
                snapshot(
                    entry(1, runtimeMs = 22 * min, number = 1),
                    entry(2, runtimeMs = 22 * min, number = 1),
                    entry(3, runtimeMs = 3 * min, number = 1),
                ),
            )
        // As long: the smaller canonical ID wins.
        assertEquals(listOf(idOf(1)), keptIds(result))
        assertEquals(mapOf(ExtrasReason.ShorterDuplicate to 2), result.dropped)
        // entry(n) numbers an episode n, so these two are different episodes and both stay.
        val different = ExtrasFilter.filter(snapshot(entry(1, runtimeMs = 20 * min), entry(2, runtimeMs = 25 * min)))
        assertEquals(2, different.snapshot.items.size)
        val same =
            ExtrasFilter.filter(
                snapshot(entry(1, runtimeMs = 20 * min, number = 7), entry(2, runtimeMs = 25 * min, number = 7)),
            )
        assertEquals(listOf(idOf(2)), keptIds(same))
    }

    @Test
    fun aDuplicateIsOnlyAgainstASurvivor() {
        // The longest copy is over the limit and goes first, so the 22 minute copy stays.
        val result =
            ExtrasFilter.filter(
                snapshot(
                    entry(1, runtimeMs = ExtrasFilter.EPISODE_LIMIT_MS + 1, number = 1),
                    entry(2, runtimeMs = 22 * min, number = 1),
                ),
            )
        assertEquals(listOf(idOf(2)), keptIds(result))
        assertEquals(mapOf(ExtrasReason.RuntimeOverLimit to 1), result.dropped)
    }

    @Test
    fun differentSeasonsOrSeriesAreNotDuplicates() {
        val result =
            ExtrasFilter.filter(
                snapshot(
                    entry(1, number = 1, season = 1),
                    entry(2, number = 1, season = 2),
                    entry(3, number = 1, season = 1, series = idOf(0x501)),
                ),
            )
        assertEquals(3, result.snapshot.items.size)
    }

    @Test
    fun episodesWithoutNumbersAreNeverDuplicates() {
        val result =
            ExtrasFilter.filter(
                snapshot(
                    entry(1, number = null),
                    entry(2, number = null),
                    entry(3, season = null, number = 1),
                    entry(4, season = null, number = 1),
                    entry(5, series = null, number = 1),
                    entry(6, series = null, number = 1),
                ),
            )
        assertEquals(6, result.snapshot.items.size)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun aMovieIsNeverADuplicate() {
        val result =
            ExtrasFilter.filter(
                snapshot(
                    entry(1, kind = ScheduleItemKind.Movie),
                    entry(2, kind = ScheduleItemKind.Movie),
                ),
            )
        assertEquals(2, result.snapshot.items.size)
    }

    @Test
    fun firstReasonWinsAndCountsAddUp() {
        val items =
            listOf(
                entry(1, runtimeMs = 30_000L, name = "NCOP1", number = 1),
                entry(2, runtimeMs = ExtrasFilter.EPISODE_LIMIT_MS + 1, name = "NCOP1", number = 2),
                entry(3, runtimeMs = 5 * min, name = "NCED1", number = 3),
                entry(4, runtimeMs = 5 * min, number = 4),
                entry(5, runtimeMs = 4 * min, number = 4),
                entry(6, runtimeMs = 22 * min, number = 6),
            )
        val result = ExtrasFilter.filter(snapshot(*items.toTypedArray()))
        assertEquals(
            mapOf(
                ExtrasReason.RuntimeUnderAMinute to 1,
                ExtrasReason.RuntimeOverLimit to 1,
                ExtrasReason.NamedExtra to 1,
                ExtrasReason.ShorterDuplicate to 1,
            ),
            result.dropped,
        )
        assertEquals(listOf(idOf(4), idOf(6)), keptIds(result))
        assertEquals(items.size, result.snapshot.items.size + result.dropped.values.sum())
    }

    @Test
    fun theFilterIsPureAndKeepsOrder() {
        val original =
            snapshot(
                entry(9, number = 9),
                entry(3, runtimeMs = 1L, number = 3),
                entry(5, number = 5),
                entry(1, number = 5, runtimeMs = 10 * min),
                entry(7, kind = ScheduleItemKind.Movie),
            )
        val before = original.items.toList()
        val first = ExtrasFilter.filter(original)
        val second = ExtrasFilter.filter(original)
        assertEquals(before, original.items)
        assertEquals(keptIds(first), keptIds(second))
        assertEquals(first.dropped, second.dropped)
        assertEquals(listOf(idOf(9), idOf(5), idOf(7)), keptIds(first))
        val out = first.snapshot
        assertEquals(original.cutoffMs, out.cutoffMs)
        assertEquals(original.libraries, out.libraries)
        assertEquals(original.series, out.series)
        assertEquals(original.collections, out.collections)
        assertEquals(original.collectionsSynced, out.collectionsSynced)
    }

    @Test
    fun aCleanSnapshotComesBackAsItIs() {
        val clean = snapshot(entry(1), entry(2))
        val result = ExtrasFilter.filter(clean)
        assertSame(clean, result.snapshot)
        assertTrue(result.dropped.isEmpty())
    }
}
