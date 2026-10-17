// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.library

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.ItemKind
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.schedule.ScheduleItem
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.model.schedule.ScheduleItemProblem
import tv.stationbreak.core.model.schedule.SchedulerSettings
import tv.stationbreak.core.scheduler.EpochPool
import tv.stationbreak.core.scheduler.buildDay
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Spec 09 task 2.8 (Vimes M3): the as-of read leaves out, and counts, what the scheduler would refuse, and every drop
 * reason names the check it mirrors. It is tested in both directions: every returned item is accepted by `ScheduleItem`
 * (and `buildDay`), and every dropped one, built directly from the same fields, is refused.
 */
class ScheduleInputFilterTest {
    private val settings =
        SchedulerSettings(OrderingMode.Shuffle, Seed(1uL), 1, true, 30, 24, 0, 360, 6, 60_000L)
    private val id = hexId(1)
    private val series = hexId(2)
    private val ok =
        HeldRow(id, MOVIES.value, "Movie", null, null, null, 5_400_000L, 1_000L, null, null, null, 0L)
    private val episode = ok.copy(kind = "Episode", seriesId = series, seasonNumber = 1, episodeNumber = 2)
    private val beyond = (1L shl 52) + 1

    /** The item the scheduler would be given for [row], built directly with no filter. Throws if it can't be. */
    private fun direct(row: HeldRow): ScheduleItem {
        val item =
            ScheduleItem(
                id = CanonicalId.parse(row.itemId),
                kind = ScheduleItemKind.valueOf(row.kind),
                runtimeMs = row.runtimeMs!!,
                createdAtMs = row.createdAtMs!!,
                deletedAtMs = row.deletedAtMs,
                premiereDate = row.premiereDate,
                productionYear = row.productionYear,
                seriesId = row.seriesId?.let(CanonicalId::parse),
                seasonNumber = row.seasonNumber,
                episodeNumber = row.episodeNumber,
            )
        buildDay(settings, listOf(item), 0)
        return item
    }

    private class Case(
        val reason: DropReason,
        val row: HeldRow,
    )

    private val cases =
        listOf(
            Case(DropReason.KindNotMovieOrEpisode, ok.copy(kind = "Series")),
            Case(DropReason.KindNotMovieOrEpisode, ok.copy(kind = "Other")),
            Case(DropReason.IdNotCanonical, ok.copy(itemId = "not-an-id")),
            Case(DropReason.IdNotCanonical, ok.copy(itemId = hexId(1).dropLast(1))),
            Case(DropReason.SeriesIdNotCanonical, episode.copy(seriesId = "series-1")),
            Case(DropReason.RuntimeMissing, ok.copy(runtimeMs = null)),
            Case(DropReason.RuntimeMissing, ok.copy(runtimeMs = 0)),
            Case(DropReason.RuntimeMissing, ok.copy(runtimeMs = -5)),
            Case(DropReason.CreatedAtMissing, ok.copy(createdAtMs = null)),
            Case(DropReason.TimeOutOfRange, ok.copy(createdAtMs = beyond)),
            Case(DropReason.TimeOutOfRange, ok.copy(createdAtMs = -beyond)),
            Case(DropReason.TimeOutOfRange, ok.copy(deletedAtMs = beyond)),
            Case(DropReason.PremiereDateMalformed, ok.copy(premiereDate = "2001-13-01")),
            Case(DropReason.PremiereDateMalformed, ok.copy(premiereDate = "0000-01-01")),
            Case(DropReason.PremiereDateMalformed, ok.copy(premiereDate = "2001-1-1")),
            Case(DropReason.PremiereDateMalformed, ok.copy(premiereDate = "last week")),
            Case(DropReason.ProductionYearOutOfRange, ok.copy(productionYear = 0)),
            Case(DropReason.ProductionYearOutOfRange, ok.copy(productionYear = 10_000)),
            Case(DropReason.ProductionYearOutOfRange, ok.copy(productionYear = -1)),
            Case(DropReason.SeasonNegative, episode.copy(seasonNumber = -1)),
            Case(DropReason.EpisodeNegative, episode.copy(episodeNumber = -1)),
            Case(DropReason.MovieHasSeriesData, ok.copy(seriesId = series)),
            Case(DropReason.MovieHasSeriesData, ok.copy(seasonNumber = 1)),
            Case(DropReason.MovieHasSeriesData, ok.copy(episodeNumber = 1)),
        )

    @Test
    fun everyExcludedCaseIsDroppedUnderItsReasonAndIsRefusedWhenBuiltDirectly() {
        cases.forEach { case ->
            val converted = ScheduleInputs.convert(case.row)
            assertEquals("${case.row}", Converted.Dropped(case.reason), converted)
            // The other direction: built directly from the same fields, the scheduler's own types refuse it. A runtime
            // that is not positive is the one exception: the format calls it "no runtime" and the pool leaves it out.
            val notPositive = case.row.runtimeMs
            if (case.reason == DropReason.RuntimeMissing && notPositive != null) {
                val item = direct(case.row.copy(runtimeMs = 5_400_000L, createdAtMs = -1_000L))
                assertEquals("control: the pool takes it", 1, EpochPool.build(settings, listOf(item), 0).size)
                val noRuntime = listOf(item.copy(runtimeMs = notPositive))
                assertEquals(emptyList<ScheduleItem>(), EpochPool.build(settings, noRuntime, 0))
            } else {
                assertThrows("${case.row} should be refused", Exception::class.java) { direct(case.row) }
            }
        }
    }

    @Test
    fun everyDropReasonIsNamedAndCoveredByACase() {
        val covered = cases.map { it.reason }.toSet()
        // The duplicate ID is the read's own (it sees many items), and the last reason is the safety net.
        val readsOwn = setOf(DropReason.DuplicateId, DropReason.RejectedByScheduleItem)
        assertEquals(DropReason.entries.toSet() - readsOwn, covered)
        DropReason.entries.forEach { assertTrue(it.name, it.mirrors.isNotBlank()) }
    }

    @Test
    fun theSafetyNetNeverCatchesAnythingInTheWholeCorpus() {
        // Granny M1: the fields ScheduleItem validates come from ScheduleItem.problem, so its init has nothing left to
        // refuse. If the two ever drift, this count (and the live one in diagnostics) stops being 0.
        val dated = ok.copy(premiereDate = "2001-02-03", productionYear = 2001)
        val corpus = cases.map { it.row } + listOf(ok, episode, dated)
        val net = Converted.Dropped(DropReason.RejectedByScheduleItem)
        val rejected = corpus.map { ScheduleInputs.convert(it) }.count { it == net }
        assertEquals(0, rejected)
    }

    @Test
    fun everyProblemScheduleItemCanFindHasADropReasonThatNamesIt() {
        ScheduleItemProblem.entries.forEach { problem ->
            assertEquals(problem.name, DropReason.of(problem).name)
        }
    }

    @Test
    fun everyAcceptedCaseIsAcceptedByTheSchedulersOwnValidation() {
        val accepted =
            listOf(
                ok,
                episode,
                ok.copy(deletedAtMs = 5_000),
                ok.copy(premiereDate = "0001-01-01", productionYear = 1),
                ok.copy(premiereDate = "9999-12-31", productionYear = 9999),
                episode.copy(seasonNumber = 0, episodeNumber = 0),
                episode.copy(seriesId = null, seasonNumber = null, episodeNumber = null),
                ok.copy(createdAtMs = -(1L shl 52), deletedAtMs = 1L shl 52),
                ok.copy(itemId = hexId(1).uppercase()),
                ok.copy(itemId = "{" + hexId(1).chunked(4).joinToString("-") + "}"),
                episode.copy(seriesId = series.uppercase()),
            )
        accepted.forEach { row ->
            val converted = ScheduleInputs.convert(row)
            assertTrue("$row was dropped as $converted", converted is Converted.Accepted)
            val item = (converted as Converted.Accepted).item
            assertEquals(item, direct(row))
            assertEquals(hexId(1), item.id.value)
        }
    }

    // Through the database, with the counts.

    private val clock = FakeClock()
    private val repository = TestLibraries.inMemory(clock)
    private val cutoff: Instant = EPOCH + 1.hours

    @After
    fun close() = repository.close()

    private suspend fun read() = repository.scheduleInputs(ALICE, setOf(MOVIES), cutoff)

    @Test
    fun theReadDropsAndCountsEachReasonAndReturnsOnlyWhatTheSchedulerAccepts() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            val good = sitem(10)
            repository.put(
                ALICE,
                MOVIES,
                good,
                sitem(11).copy(kind = ItemKind.Series),
                sitem(12).copy(id = ItemId("not-canonical")),
                sitem(13, runtime = null),
                sitem(14, runtime = 0.minutes),
                sitem(15).copy(dateCreated = null),
                sitem(16).copy(premiereDate = "soon"),
                sitem(17).copy(productionYear = 0),
                sitem(18).copy(seriesId = ItemId(hexId(99))),
                sitem(19).copy(kind = ItemKind.Episode, seriesId = ItemId(hexId(99)), seasonNumber = -2),
                sitem(20).copy(dateCreated = Instant.fromEpochMilliseconds(beyond)),
            )

            val result = read()

            assertEquals(listOf(good.id.value), result.items.map { it.id.value })
            assertEquals(
                mapOf(
                    DropReason.KindNotMovieOrEpisode to 1,
                    DropReason.IdNotCanonical to 1,
                    DropReason.RuntimeMissing to 2,
                    DropReason.CreatedAtMissing to 1,
                    DropReason.PremiereDateMalformed to 1,
                    DropReason.ProductionYearOutOfRange to 1,
                    DropReason.MovieHasSeriesData to 1,
                    DropReason.SeasonNegative to 1,
                    DropReason.TimeOutOfRange to 1,
                ),
                result.dropped,
            )
            assertEquals(null, result.dropped[DropReason.RejectedByScheduleItem])
            // No throw anywhere: the scheduler builds a day from exactly what came back.
            assertEquals(0L, buildDay(settings, result.items, 0).n)
        }

    @Test
    fun anItemOutsideTheChosenLibrariesOrDeletedBeforeTheCutoffIsNotACountedDrop() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES, SHOWS))
            repository.put(ALICE, MOVIES, sitem(1, runtime = null))
            repository.put(ALICE, SHOWS, sitem(2, runtime = null, library = SHOWS))
            repository.put(ALICE, MOVIES, sitem(3, runtime = null))
            clock.stepMs(1_000)
            repository.tombstone(ALICE, listOf(ItemId(hexId(3))))

            val result = repository.scheduleInputs(ALICE, setOf(MOVIES), EPOCH + 1.hours)

            assertEquals(emptyList<ScheduleItem>(), result.items)
            assertEquals(mapOf(DropReason.RuntimeMissing to 1), result.dropped)
        }

    @Test
    fun twoRowsThatNormaliseToOneIdKeepOneAndCountTheOtherAsADuplicate() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            // "...0B" and "...0b" are two rows in storage and one canonical ID: buildDay would throw on the pair.
            repository.put(ALICE, MOVIES, sitem(11).copy(id = ItemId(hexId(11).uppercase())), sitem(11))

            val result = read()

            assertEquals(listOf(hexId(11)), result.items.map { it.id.value })
            assertEquals(mapOf(DropReason.DuplicateId to 1), result.dropped)
            assertEquals(0L, buildDay(settings, result.items, 0).n)
        }

    @Test
    fun theItemsComeBackInCanonicalIdOrderNotStorageOrder() =
        runBlocking {
            repository.select(ALICE, setOf(MOVIES))
            // In storage "...0B" sorts before "...0a"; as canonical IDs "...0a" is the smaller.
            repository.put(ALICE, MOVIES, sitem(11).copy(id = ItemId(hexId(11).uppercase())), sitem(10))

            val ids = read().items.map { it.id.value }

            assertEquals(listOf(hexId(10), hexId(11)), ids)
        }
}
