// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.playback.SubtitleChoice
import tv.stationbreak.feature.live.SwitchFixtures.ENGLISH_SDH
import tv.stationbreak.feature.live.SwitchFixtures.FRENCH
import tv.stationbreak.feature.live.SwitchFixtures.SPANISH
import tv.stationbreak.feature.live.SwitchFixtures.facts

/** What a picker lists, whether its chip can open, and which rows restart the picture (design doc B2.8, B3). */
class PickerRowsTest {
    @Test
    fun aChipOpensOnlyWithSomethingToChooseFrom() {
        assertTrue(facts().canPick(PickerKind.Audio))
        assertTrue(facts().canPick(PickerKind.Subtitles))
        assertFalse("one audio track", facts(audio = listOf(SPANISH)).canPick(PickerKind.Audio))
        assertFalse("no subtitle tracks", facts(subtitles = emptyList()).canPick(PickerKind.Subtitles))
        assertFalse("no facts at all", (null as tv.stationbreak.core.playback.StreamFacts?).canPick(PickerKind.Audio))
    }

    @Test
    fun theAudioRowsAreTheTracksInServerOrderWithTheOneInUseMarked() {
        val rows = facts(audioIndex = 2).audioRows()
        assertEquals(listOf(1, 2, 3, 4), rows.map { (it.selection as TrackSelection.Audio).index })
        assertEquals(listOf(false, true, false, false), rows.map { it.playing })
    }

    @Test
    fun aRowRestartsThePictureExactlyWhenItsCostSaysSo() {
        val rows = facts().audioRows()
        assertEquals(listOf(false, false, true, true), rows.map { it.restarts })
    }

    @Test
    fun subtitlesOffIsAlwaysTheFirstRowAndTheServerDefaultTheLast() {
        val rows = facts().subtitleRows()
        assertTrue(rows.first() is PickerRow.Off)
        assertEquals(TrackSelection.Subtitles(SubtitleChoice.Off), rows.first().selection)
        assertEquals(TrackSelection.Subtitles(SubtitleChoice.Default), rows.last().selection)
        assertEquals(5, rows.size)
    }

    @Test
    fun withNoSubtitleInUseOffIsTheRowThatPlays() {
        val none = facts(subtitleIndex = null).subtitleRows()
        assertEquals(listOf(true, false, false, false, false), none.map { it.playing })
        val sdh = facts(subtitleIndex = ENGLISH_SDH.index).subtitleRows()
        assertEquals(listOf(false, false, true, false, false), sdh.map { it.playing })
    }

    @Test
    fun aPictureSubtitleRestartsThePictureAndOffDoesNot() {
        val rows = facts().subtitleRows()
        assertFalse("off is instant on direct play", rows.first().restarts)
        assertTrue("a picture subtitle is burned in", rows[3].restarts)
        assertTrue("the server decides: only a fresh resolve says", rows.last().restarts)
    }

    @Test
    fun theRowsOfAnotherVersionAreNotListed() {
        val rows = facts(audio = listOf(FRENCH, SPANISH)).audioRows()
        assertEquals(2, rows.size)
    }
}
