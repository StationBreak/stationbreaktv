// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import tv.stationbreak.core.ui.ProgramCopy
import java.io.File
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
