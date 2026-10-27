// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.feature.live.NextProgram
import java.io.File
import java.time.ZoneOffset
import java.util.Locale

/**
 * The banner's text rules. The copy is read from `strings.xml` itself, so the test checks the shipped words. The slot
 * and episode rules themselves are tested in core:ui; here only the banner's delegation to them is checked.
 */
class BannerTextTest {
    private val banner = StringsXml.bannerCopy()
    private val copy = StringsXml.programCopy()
    private val id = CanonicalId.parse("1".padStart(32, '0'))

    private fun episode(
        season: Int?,
        number: Int?,
    ) = ProgramInfo(id, ScheduleItemKind.Episode, "Episode One", "Test Series B", season, number)

    @Test
    fun joinedUnderAMinuteIsJustStarted() {
        assertEquals("Just started", BannerText.joined(0, banner))
        assertEquals("Just started", BannerText.joined(59_999, banner))
    }

    @Test
    fun joinedTwelveMinutesIn() {
        assertEquals("joining 12 min in", BannerText.joined(12 * 60_000L + 20_000, banner))
        assertEquals("joining 1 min in", BannerText.joined(60_000, banner))
    }

    @Test
    fun noJoinedLineWhenTheShowCutInAtItsStart() {
        assertNull(BannerText.joined(null, banner))
    }

    @Test
    fun episodeLineIsSeasonAndEpisodeAndTitle() {
        assertEquals("S2 E5 · Episode One", BannerText.episodeLine(episode(2, 5), copy))
    }

    @Test
    fun episodeLineWithOnlyOneNumberKeepsThatNumber() {
        assertEquals("S2 · Episode One", BannerText.episodeLine(episode(2, null), copy))
        assertEquals("E5 · Episode One", BannerText.episodeLine(episode(null, 5), copy))
    }

    @Test
    fun episodeLineWithoutNumbersIsTitleOnly() {
        assertEquals("Episode One", BannerText.episodeLine(episode(null, null), copy))
    }

    @Test
    fun movieHasNoEpisodeLine() {
        val movie = ProgramInfo(id, ScheduleItemKind.Movie, "Test Movie A", null, null, null)
        assertNull(BannerText.episodeLine(movie, copy))
        assertNull(BannerText.episodeLine(null, copy))
    }

    @Test
    fun progressIsTheShareOfTheSlotGoneByAndNeverLeavesZeroToOne() {
        assertEquals(0.25f, BannerText.progress(1_000, 5_000, 2_000), 0f)
        assertEquals(0f, BannerText.progress(1_000, 5_000, 500), 0f)
        assertEquals(1f, BannerText.progress(1_000, 5_000, 9_000), 0f)
        assertEquals(0f, BannerText.progress(1_000, 1_000, 1_000), 0f)
    }

    @Test
    fun minutesLeftSaysOneInTheLastMinuteAndStopsAtZero() {
        assertEquals(30, BannerText.minutesLeft(0, 30 * 60_000L, 1_000))
        assertEquals(1, BannerText.minutesLeft(0, 60_000L, 59_999))
        assertEquals(0, BannerText.minutesLeft(0, 60_000L, 90_000))
    }

    @Test
    fun theProgressTickIsDueOnTheClocksNextMinute() {
        assertEquals(59_200L, BannerText.untilNextMinute(29 * 60_000L + 800))
        assertEquals(60_000L, BannerText.untilNextMinute(30 * 60_000L))
        assertEquals(1L, BannerText.untilNextMinute(30 * 60_000L - 1))
    }

    @Test
    fun minutesLeftReadsTheClockMinuteAgainstTheSlotsEndMinute() {
        // A back-to-back program ending 51 min 40 s in, shown as ending at :51: at 29 min 0.8 s that is 22 minutes,
        // as the clock and the slot read, not 23 (22 min 39 s rounded up).
        val end = 51 * 60_000L + 40_000L
        assertEquals(22, BannerText.minutesLeft(0, end, 29 * 60_000L + 800))
        assertEquals(22, BannerText.minutesLeft(0, end, 29 * 60_000L + 59_000))
        assertEquals(21, BannerText.minutesLeft(0, end, 30 * 60_000L + 1_500))
    }

    @Test
    fun nextLineNamesTheProgramAndWhenItStarts() {
        val next = NextProgram("Test Movie B", 21 * 3_600_000L)
        val line = BannerText.nextLine(next, copy, ZoneOffset.UTC, false, Locale.US)
        assertEquals("Next: Test Movie B at 9:00 PM", line)
    }

    @Test
    fun aLongNextTitleIsCutBeforeTheLineIsMadeSoTheTimeStaysInView() {
        val title = "A Very Long Synthetic Name ".repeat(5).trim()
        val line = BannerText.nextLine(NextProgram(title, 21 * 3_600_000L), copy, ZoneOffset.UTC, true, Locale.US)
        assertEquals("Next: ${title.take(39)}\u2026 at 21:00", line)
        assertEquals(40, BannerText.capTitle(title).length)
        assertEquals("Test Movie B", BannerText.capTitle("Test Movie B"))
    }
}

/** Reads the shipped `strings.xml` files (the unit test's working directory is the module) into the copy. */
internal object StringsXml {
    private val file = File("src/main/res/values/strings.xml")
    private val shared = File("../../core/ui/src/main/res/values/strings.xml")

    private fun read(
        source: File,
        name: String,
    ): String {
        val match = Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(source.readText())
        return checkNotNull(match) { "missing string $name" }.groupValues[1]
    }

    fun text(name: String): String = read(file, name)

    fun bannerCopy(): BannerCopy =
        BannerCopy(
            justStarted = text("live_just_started"),
            joining = { String.format(Locale.US, text("live_joining"), it) },
        )

    /** The words shared with the guide live in core:ui; the banner's episode lines are checked against them. */
    fun programCopy(): ProgramCopy {
        fun fmt(name: String): String = read(shared, name)
        return ProgramCopy(
            episodeBoth = { s, e, t -> String.format(Locale.US, fmt("program_episode_both"), s, e, t) },
            episodeSeason = { s, t -> String.format(Locale.US, fmt("program_episode_season"), s, t) },
            episodeNumber = { e, t -> String.format(Locale.US, fmt("program_episode_number"), e, t) },
            next = { t, at -> String.format(Locale.US, fmt("program_next"), t, at) },
            offAirUntil = { String.format(Locale.US, fmt("program_off_air_until"), it) },
            startsIn = { String.format(Locale.US, fmt("program_starts_in"), it) },
            minutesLeft = { String.format(Locale.US, fmt("program_minutes_left"), it) },
        )
    }
}
