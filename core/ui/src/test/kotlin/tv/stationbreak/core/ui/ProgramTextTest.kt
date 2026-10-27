// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The shared program text rules. The copy is read from `strings.xml` itself, so the test checks the shipped words. */
class ProgramTextTest {
    private val copy = StringsXml.programCopy()
    private val newYork = ZoneId.of("America/New_York")

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun episode(
        season: Int?,
        number: Int?,
    ) = ProgramText.episodeLine(true, "Test Series B", season, number, "Episode One", copy)

    @Test
    fun slotUsesTheDeviceZoneWithDst() {
        // 00:00 UTC on 1 July is 8:00 PM on 30 June in New York (EDT, UTC-4); standard time would say 7:00 PM.
        val summer = ProgramText.slot(ms("2026-07-01T00:00:00Z"), ms("2026-07-01T00:30:00Z"), newYork, false, Locale.US)
        assertEquals("8:00 – 8:30 PM", summer)
        val winter = ProgramText.slot(ms("2026-01-01T01:00:00Z"), ms("2026-01-01T01:30:00Z"), newYork, false, Locale.US)
        assertEquals("8:00 – 8:30 PM", winter)
    }

    @Test
    fun slotSaysBothMeridiemsWhenTheyDiffer() {
        val noon = ProgramText.slot(ms("2026-07-01T15:30:00Z"), ms("2026-07-01T16:00:00Z"), newYork, false, Locale.US)
        assertEquals("11:30 AM – 12:00 PM", noon)
    }

    @Test
    fun slotHonours24HourClock() {
        val slot = ProgramText.slot(ms("2026-07-01T00:00:00Z"), ms("2026-07-01T00:30:00Z"), newYork, true, Locale.US)
        assertEquals("20:00 – 20:30", slot)
    }

    @Test
    fun timeIs12And24Hour() {
        val at = ms("2026-07-01T00:30:00Z")
        assertEquals("8:30 PM", ProgramText.time(at, newYork, false, Locale.US))
        assertEquals("20:30", ProgramText.time(at, newYork, true, Locale.US))
    }

    @Test
    fun dayTimeHasTheWeekday() {
        // 00:12 UTC on Tuesday 27 October 2026 is 8:12 PM on Monday 26 October in New York.
        val at = ms("2026-10-27T00:12:00Z")
        assertEquals("Mon 8:12 PM", ProgramText.dayTime(at, newYork, false, Locale.US))
        assertEquals("Mon 20:12", ProgramText.dayTime(at, newYork, true, Locale.US))
    }

    @Test
    fun slotOnTodayHasNoWeekday() {
        val now = ms("2026-10-26T23:00:00Z")
        val start = ms("2026-10-27T00:00:00Z")
        val slot = ProgramText.slotOn(start, start + HALF_HOUR, now, newYork, false, Locale.US)
        assertEquals("8:00 – 8:30 PM", slot)
    }

    @Test
    fun slotOnAnotherDayStartsWithItsWeekday() {
        // 7:00 PM Monday in New York; the program is at 1:00 AM on Tuesday.
        val now = ms("2026-10-26T23:00:00Z")
        val start = ms("2026-10-27T05:00:00Z")
        val slot = ProgramText.slotOn(start, start + HALF_HOUR, now, newYork, false, Locale.US)
        assertEquals("Tue 1:00 – 1:30 AM", slot)
    }

    @Test
    fun episodeLineIsSeasonAndEpisodeAndTitle() {
        assertEquals("S2 E5 · Episode One", episode(2, 5))
    }

    @Test
    fun episodeLineWithOnlyOneNumberKeepsThatNumber() {
        assertEquals("S2 · Episode One", episode(2, null))
        assertEquals("E5 · Episode One", episode(null, 5))
    }

    @Test
    fun episodeLineWithoutNumbersIsTitleOnly() {
        assertEquals("Episode One", episode(null, null))
    }

    @Test
    fun movieHasNoEpisodeLine() {
        assertNull(ProgramText.episodeLine(false, null, null, null, "Test Movie A", copy))
    }

    @Test
    fun anEpisodeWithoutASeriesNameHasNoEpisodeLine() {
        assertNull(ProgramText.episodeLine(true, null, 2, 5, "Episode One", copy))
    }

    @Test
    fun countdownCopyIsTheShippedWords() {
        assertEquals("Next: Test Show at 8:30 PM", copy.next("Test Show", "8:30 PM"))
        assertEquals("Off air until 6:00 AM", copy.offAirUntil("6:00 AM"))
        assertEquals("Starts in 5 min", copy.startsIn(5))
        assertEquals("12 min left", copy.minutesLeft(12))
    }

    @Test
    fun minutesUntilIsTheClockMinuteToTheSlotMinuteAsShown() {
        // Back-to-back programs end on any second: this one at 8:51:40, which the slot shows as 8:51. The viewer reads
        // the clock's minute against that, as on a cable box: 23 at 8:28, 22 all through 8:29, 21 from 8:30.
        val end = ms("2026-10-27T00:51:40Z")
        assertEquals(23, ProgramText.minutesUntil(end, ms("2026-10-27T00:28:59Z")))
        assertEquals(22, ProgramText.minutesUntil(end, ms("2026-10-27T00:29:00.800Z")))
        assertEquals(22, ProgramText.minutesUntil(end, ms("2026-10-27T00:29:59Z")))
        assertEquals(21, ProgramText.minutesUntil(end, ms("2026-10-27T00:30:01.500Z")))
    }

    @Test
    fun minutesUntilSaysOneInTheLastMinuteAndNoneOnceReached() {
        val end = ms("2026-10-27T00:51:40Z")
        assertEquals(1, ProgramText.minutesUntil(end, ms("2026-10-27T00:50:59Z")))
        assertEquals(1, ProgramText.minutesUntil(end, ms("2026-10-27T00:51:10Z")))
        assertEquals(1, ProgramText.minutesUntil(end, end - 1))
        assertEquals(0, ProgramText.minutesUntil(end, end))
        assertEquals(0, ProgramText.minutesUntil(end, end + 90_000))
    }

    @Test
    fun minutesUntilAWholeMinuteIsTheDifference() {
        val end = ms("2026-10-27T00:30:00Z")
        assertEquals(30, ProgramText.minutesUntil(end, ms("2026-10-27T00:00:00Z")))
        assertEquals(30, ProgramText.minutesUntil(end, ms("2026-10-27T00:00:59.999Z")))
        assertEquals(29, ProgramText.minutesUntil(end, ms("2026-10-27T00:01:00Z")))
    }

    @Test
    fun formattersAreMadeOncePerLocaleAndClock() {
        val us12 = ProgramText.formatter("h:mm a", Locale.US)
        assertSame(us12, ProgramText.formatter("h:mm a", Locale.US))
        assertNotSame(us12, ProgramText.formatter("HH:mm", Locale.US))
        assertNotSame(us12, ProgramText.formatter("h:mm a", Locale.UK))
    }

    private companion object {
        const val HALF_HOUR = 30 * 60_000L
    }
}

/** Reads the shipped `strings.xml` (the unit test's working directory is the module) into [ProgramCopy]. */
object StringsXml {
    private val file = File("src/main/res/values/strings.xml")

    fun text(name: String): String {
        val match = Regex("""<string name="$name"[^>]*>(.*?)</string>""").find(file.readText())
        return checkNotNull(match) { "missing string $name" }.groupValues[1]
    }

    fun programCopy() =
        ProgramCopy(
            episodeBoth = { s, e, t -> String.format(Locale.US, text("program_episode_both"), s, e, t) },
            episodeSeason = { s, t -> String.format(Locale.US, text("program_episode_season"), s, t) },
            episodeNumber = { e, t -> String.format(Locale.US, text("program_episode_number"), e, t) },
            next = { t, at -> String.format(Locale.US, text("program_next"), t, at) },
            offAirUntil = { String.format(Locale.US, text("program_off_air_until"), it) },
            startsIn = { String.format(Locale.US, text("program_starts_in"), it) },
            minutesLeft = { String.format(Locale.US, text("program_minutes_left"), it) },
        )
}
