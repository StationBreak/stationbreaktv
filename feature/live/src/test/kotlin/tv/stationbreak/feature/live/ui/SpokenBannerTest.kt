// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** What TalkBack is told for a banner: channel, program and minutes left, and nothing that ticks. */
class SpokenBannerTest {
    private val copy =
        SpokenCopy(
            channel = { number, name -> "$number $name" },
            withProgram = { channel, title -> "$channel, $title" },
            withMinutes = { spoken, minutes -> "$spoken, $minutes left" },
        )

    private fun spoken(
        title: String?,
        left: Int?,
    ) = BannerText.spoken("07", "Test Channel", title, left, copy)

    @Test
    fun namesTheChannelTheProgramAndTheMinutesLeft() {
        assertEquals("07 Test Channel, Test Movie A, 12 left", spoken("Test Movie A", 12))
    }

    @Test
    fun aCardForWhatComesNextHasNoMinutesLeft() {
        assertEquals("07 Test Channel, Test Movie A", spoken("Test Movie A", null))
    }

    @Test
    fun aChannelWithNoProgramIsJustItsNumberAndName() {
        assertEquals("07 Test Channel", spoken(null, null))
        assertEquals("07 Test Channel", spoken(" ", null))
    }

    @Test
    fun theShippedWordsFillInEveryPlaceholder() {
        val xml = java.io.File("src/main/res/values/strings.xml").readText()
        listOf("live_spoken_channel", "live_spoken_program", "live_spoken_minutes_left", "live_actions_pane")
            .forEach { assertEquals(it, true, xml.contains("\"$it\"")) }
    }
}
