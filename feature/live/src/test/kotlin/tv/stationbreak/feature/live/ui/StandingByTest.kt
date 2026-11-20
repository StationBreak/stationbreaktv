// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.feature.live.Banner
import tv.stationbreak.feature.live.BannerLabel
import tv.stationbreak.feature.live.ChannelTag
import tv.stationbreak.feature.live.LiveState

/**
 * When live TV stops standing by ([isStandingBy]): what `LiveDestination` tells the app, which lifts setup's cheer it
 * held over the picture. Only a start, a lineup still building and a tune with no picture keep it up.
 */
class StandingByTest {
    private fun banner(label: BannerLabel = BannerLabel.UpNext) = Banner(7, "Test", label, null, null, 1L, 2L, null)

    @Test
    fun aStartALineupStillBuildingAndATuneOverNoPictureStandBy() {
        val standing =
            listOf(
                LiveState.Starting,
                LiveState.BuildingChannels,
                LiveState.Tuning(banner(BannerLabel.TuningIn)),
                LiveState.Choosing(ChannelTag(12, "Test Channel"), overPicture = false),
            )
        standing.forEach { assertTrue("$it stands by", isStandingBy(it)) }
    }

    @Test
    fun thePictureAndEveryCardThatSaysWhyNotEndTheStandBy() {
        val showing =
            listOf(
                LiveState.Watching(null),
                LiveState.Watching(banner(BannerLabel.Now)),
                LiveState.Choosing(ChannelTag(12, "Test Channel"), overPicture = true),
                LiveState.TechnicalDifficulties(banner()),
                LiveState.Ident(banner()),
                LiveState.NoChannels,
                LiveState.NoLibraries,
                LiveState.SignedOut,
            )
        showing.forEach { assertFalse("$it is no stand-by", isStandingBy(it)) }
    }
}
