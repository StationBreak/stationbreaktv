// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.feature.channels.builder.ui.BuilderTags
import tv.stationbreak.feature.channels.ui.ChannelsTags

class BuilderTagsTest {
    @Test
    fun thePublicSourcesStepTagIsTheStepsOwn() {
        assertEquals(BuilderTags.step(BuilderStep.Sources), ChannelsTags.BUILDER_SOURCES_STEP)
    }
}
