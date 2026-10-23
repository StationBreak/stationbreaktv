// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.schedule.ScheduleItemKind
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The banner's text rules. The copy is read from `strings.xml` itself, so the test checks the shipped words. */
class BannerTextTest {
    private val copy = StringsXml.bannerCopy()
    private val newYork = ZoneId.of("America/New_York")
    private val id = CanonicalId.parse("1".padStart(32, '0'))

    private fun episode(
        season: Int?,
        number: Int?,
    ) = ProgramInfo(id, ScheduleItemKind.Episode, "Episode One", "Test Series B", season, number)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun joinedUnderAMinuteIsJustStarted() {
        assertEquals("Just started", BannerText.joined(0, copy))
        assertEquals("Just started", BannerText.joined(59_999, copy))
    }

    @Test
    fun joinedTwelveMinutesIn() {
        assertEquals("joining 12 min in", BannerText.joined(12 * 60_000L + 20_000, copy))
        assertEquals("joining 1 min in", BannerText.joined(60_000, copy))
    }

    @Test
    fun noJoinedLineWhenTheShowCutInAtItsStart() {
        assertNull(BannerText.joined(null, copy))
    }

    @Test
    fun slotUsesTheDeviceZoneWithDst() {
        // 00:00 UTC on 1 July is 8:00 PM on 30 June in New York (EDT, UTC-4); standard time would say 7:00 PM.
        val summer = BannerText.slot(ms("2026-07-01T00:00:00Z"), ms("2026-07-01T00:30:00Z"), newYork, false, Locale.US)
        assertEquals("8:00 – 8:30 PM", summer)
        val winter = BannerText.slot(ms("2026-01-01T01:00:00Z"), ms("2026-01-01T01:30:00Z"), newYork, false, Locale.US)
        assertEquals("8:00 – 8:30 PM", winter)
    }

    @Test
    fun slotSaysBothMeridiemsWhenTheyDiffer() {
        val noon = BannerText.slot(ms("2026-07-01T15:30:00Z"), ms("2026-07-01T16:00:00Z"), newYork, false, Locale.US)
        assertEquals("11:30 AM – 12:00 PM", noon)
    }

    @Test
    fun slotHonours24HourClock() {
        val slot = BannerText.slot(ms("2026-07-01T00:00:00Z"), ms("2026-07-01T00:30:00Z"), newYork, true, Locale.US)
        assertEquals("20:00 – 20:30", slot)
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

/** Reads the shipped `strings.xml` (the unit test's working directory is the module) into the banner's copy. */
internal object StringsXml {
    private val file = File("src/main/res/values/strings.xml")

    fun text(name: String): String {
        val match = Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(file.readText())
        return checkNotNull(match) { "missing string $name" }.groupValues[1]
    }

    fun bannerCopy(): BannerCopy =
        BannerCopy(
            justStarted = text("live_just_started"),
            joining = { String.format(Locale.US, text("live_joining"), it) },
            episodeBoth = { s, e, t -> String.format(Locale.US, text("live_episode_both"), s, e, t) },
            episodeSeason = { s, t -> String.format(Locale.US, text("live_episode_season"), s, t) },
            episodeNumber = { e, t -> String.format(Locale.US, text("live_episode_number"), e, t) },
        )
}
