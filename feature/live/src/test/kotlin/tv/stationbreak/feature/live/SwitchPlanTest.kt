// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.domain.lineup.ScheduleFixtures.itemOf
import tv.stationbreak.core.domain.tracks.SubtitlePreference
import tv.stationbreak.core.playback.SubtitleChoice
import tv.stationbreak.core.playback.SubtitleSwitch
import tv.stationbreak.core.playback.SwitchCost
import tv.stationbreak.core.playback.TrackChoice
import tv.stationbreak.core.playback.TrackOverride
import tv.stationbreak.core.playback.TrackSwitch
import tv.stationbreak.feature.live.SwitchFixtures.ALT
import tv.stationbreak.feature.live.SwitchFixtures.COMMENTARY
import tv.stationbreak.feature.live.SwitchFixtures.ENGLISH_SDH
import tv.stationbreak.feature.live.SwitchFixtures.ENGLISH_SUBS
import tv.stationbreak.feature.live.SwitchFixtures.FRENCH
import tv.stationbreak.feature.live.SwitchFixtures.SOURCE
import tv.stationbreak.feature.live.SwitchFixtures.SPANISH
import tv.stationbreak.feature.live.SwitchFixtures.SPANISH_PICTURE
import tv.stationbreak.feature.live.SwitchFixtures.facts
import tv.stationbreak.feature.live.SwitchFixtures.twoVersions

/** What a choice means: the choice to tune with, the in-place switch, its cost, and what it leaves behind. */
class SwitchPlanTest {
    private val item = itemOf(1)

    private fun plan(
        selection: TrackSelection,
        now: TrackOverride? = null,
        facts: tv.stationbreak.core.playback.StreamFacts = facts(),
    ) = checkNotNull(planSwitch(selection, facts, now, item))

    @Test
    fun anInstantAudioSwitchNamesTheTrackToPickAndKeepsTheSubtitlesAsTheyArePlaying() {
        val p = plan(TrackSelection.Audio(SPANISH.index), facts = facts(subtitleIndex = ENGLISH_SUBS.index))
        assertEquals(SwitchCost.Instant, p.cost)
        assertEquals(TrackSwitch(audio = SPANISH.pick), p.inPlace)
        val keeps = TrackOverride(SPANISH.index, SubtitleChoice.Track(ENGLISH_SUBS.index))
        assertEquals(TrackChoice(SOURCE, keeps), p.choice)
        assertEquals(SPANISH.index, p.facts.audioIndex)
        assertEquals(BannerLabel.SwitchingAudio, p.label)
    }

    @Test
    fun anAudioSwitchKeepsASubtitleOffTheViewerChose() {
        val off = TrackOverride(null, SubtitleChoice.Off)
        val p = plan(TrackSelection.Audio(FRENCH.index), now = off)
        assertEquals(SubtitleChoice.Off, p.choice.tracks?.subtitle)
    }

    @Test
    fun aTrackThePlayerCannotDecodeRetunesWithNoInPlaceSwitch() {
        val p = plan(TrackSelection.Audio(FRENCH.index))
        assertEquals(SwitchCost.Retune, p.cost)
        assertNull(p.inPlace)
    }

    @Test
    fun theAudioOfAnEarlierChoiceIsKeptWhenSubtitlesChange() {
        val chose = TrackOverride(SPANISH.index, SubtitleChoice.Default)
        val p = plan(TrackSelection.Subtitles(SubtitleChoice.Track(ENGLISH_SUBS.index)), now = chose)
        assertEquals(SPANISH.index, p.choice.tracks?.audioIndex)
        assertEquals(TrackSwitch(subtitles = SubtitleSwitch.To(ENGLISH_SUBS.pick!!)), p.inPlace)
    }

    @Test
    fun subtitlesOffIsInstantAndLeavesOffAsThePreference() {
        val p = plan(TrackSelection.Subtitles(SubtitleChoice.Off))
        assertEquals(SwitchCost.Instant, p.cost)
        assertEquals(TrackSwitch(subtitles = SubtitleSwitch.Off), p.inPlace)
        assertEquals(Remembered.Subtitles(SubtitlePreference.Off), p.remember)
        assertNull(p.facts.subtitleIndex)
    }

    @Test
    fun theWayBackToServerDecidesAlwaysRetunesAndForgetsTheChoice() {
        val p = plan(TrackSelection.Subtitles(SubtitleChoice.Default), now = TrackOverride(null, SubtitleChoice.Off))
        assertEquals(SwitchCost.Retune, p.cost)
        assertNull(p.inPlace)
        assertEquals(SubtitleChoice.Default, p.choice.tracks?.subtitle)
        assertEquals(Remembered.Subtitles(null), p.remember)
    }

    @Test
    fun aPictureSubtitleRetunesAndItsLanguageCarries() {
        val p = plan(TrackSelection.Subtitles(SubtitleChoice.Track(SPANISH_PICTURE.index)))
        assertEquals(SwitchCost.Retune, p.cost)
        assertNull(p.inPlace)
        assertEquals(Remembered.Subtitles(SubtitlePreference.On("es")), p.remember)
    }

    @Test
    fun theHearingImpairedBitRidesAlongWithTheLanguage() {
        val p = plan(TrackSelection.Subtitles(SubtitleChoice.Track(ENGLISH_SDH.index)))
        assertEquals(Remembered.Subtitles(SubtitlePreference.On("en", hearingImpaired = true)), p.remember)
        val plain = plan(TrackSelection.Subtitles(SubtitleChoice.Track(ENGLISH_SUBS.index)))
        assertEquals(Remembered.Subtitles(SubtitlePreference.On("en")), plain.remember)
    }

    @Test
    fun aCommentaryTrackIsPlayedButLeavesNothingForTheNextProgramme() {
        val p = plan(TrackSelection.Audio(COMMENTARY.index))
        assertNull(p.remember)
        assertNotNull(p.choice.tracks)
    }

    @Test
    fun aTrackThePlayingVersionDoesNotHaveIsNoPlan() {
        assertNull(planSwitch(TrackSelection.Audio(99), facts(), null, item))
        assertNull(planSwitch(TrackSelection.Subtitles(SubtitleChoice.Track(99)), facts(), null, item))
    }

    @Test
    fun anotherVersionRetunesWithTheVersionNamedNoTracksAndIsRemembered() {
        val p = plan(TrackSelection.Version(ALT), facts = twoVersions())
        assertEquals(SwitchCost.Retune, p.cost)
        assertNull(p.inPlace)
        // The version alone: the new version's tracks come from the carried preferences.
        assertEquals(TrackChoice(ALT, null), p.choice)
        assertEquals(BannerLabel.SwitchingVersion, p.label)
        assertEquals(Remembered.Version(item, ALT), p.remember)
    }

    @Test
    fun anOldAudioChoiceIsNotCarriedToAnotherVersion() {
        val chose = TrackOverride(SPANISH.index, SubtitleChoice.Track(ENGLISH_SUBS.index))
        assertNull(plan(TrackSelection.Version(ALT), now = chose, facts = twoVersions()).choice.tracks)
    }

    @Test
    fun theVersionThatPlaysOrOneTheItemLacksIsNoPlan() {
        assertNull(planSwitch(TrackSelection.Version(SOURCE), twoVersions(), null, item))
        assertNull(
            planSwitch(
                TrackSelection.Version(
                    tv.stationbreak.core.model
                        .MediaSourceId("gone"),
                ),
                twoVersions(),
                null,
                item,
            ),
        )
    }
}
