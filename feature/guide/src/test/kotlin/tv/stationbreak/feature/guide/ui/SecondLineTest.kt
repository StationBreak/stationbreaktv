// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ProgramInfo
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.episodeInfo
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.id
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.info
import tv.stationbreak.core.model.schedule.ScheduleItemKind

/** The second line of an episode cell ("S1 E19"): when there is one, and where it is dropped rather than clipped. */
class SecondLineTest {
    private val row = 57f

    private fun episode(
        season: Int?,
        episode: Int?,
    ) = ProgramInfo(id(1), ScheduleItemKind.Episode, "Test Episode", "Test Series", season, episode)

    @Test
    fun anEpisodeWithRoomGetsItsSeasonAndEpisode() {
        assertEquals("S1 E19", secondLineOf(episodeInfo(1, "Test Series", 1, 19), 200f, 1f, row))
    }

    @Test
    fun anEpisodeWithOnlyAnEpisodeNumberGetsE19() {
        assertEquals("E19", secondLineOf(episode(season = null, episode = 19), 200f, 1f, row))
    }

    @Test
    fun noNumbersMeansOneLine() {
        assertNull(secondLineOf(episode(season = null, episode = null), 200f, 1f, row))
        assertNull(secondLineOf(episode(season = 1, episode = null), 200f, 1f, row))
        assertNull(secondLineOf(info(1), 200f, 1f, row))
        assertNull(secondLineOf(null, 200f, 1f, row))
    }

    @Test
    fun aNarrowCellKeepsOneLine() {
        val show = episodeInfo(1, "Test Series", 1, 19)
        assertNull(secondLineOf(show, 120f, 1f, row))
        assertNull(secondLineOf(show, 159f, 1f, row))
        assertEquals("S1 E19", secondLineOf(show, 160f, 1f, row))
    }

    @Test
    fun theLargestFontScaleDropsTheSecondLineRatherThanClipIt() {
        val show = episodeInfo(1, "Test Series", 1, 19)
        assertEquals("S1 E19", secondLineOf(show, 200f, 1f, row))
        assertNull(secondLineOf(show, 200f, 1.5f, row))
        // The same scale has room in a taller row: it is the row height that decides.
        assertEquals("S1 E19", secondLineOf(show, 200f, 1.5f, 70f))
    }

    @Test
    fun twoLinesFitWhileTheirHeightsAndTheInsetsFitTheRow() {
        // 19 sp and 16 sp at 1.25 line height, plus 1 dp of inset each side: 43.75 + 2 = 45.75 at scale 1.
        assertTrue(twoLinesFit(1f, 45.75f))
        assertFalse(twoLinesFit(1f, 45.7f))
        // Scale 1.25 needs 54.6875 + 2 = 56.6875, so a 57 dp row still takes it and scale 1.3 (58.9) does not.
        assertTrue(twoLinesFit(1.25f, row))
        assertFalse(twoLinesFit(1.3f, row))
    }
}
