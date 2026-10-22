// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSamplerTest {
    @Test
    fun noClosingSampleWhenAudioNeverStarted() {
        assertFalse(AudioSampler().takeClosingSample())
    }

    @Test
    fun oneClosingSampleAfterAudioStarted() {
        val sampler = AudioSampler()
        sampler.advancing()
        assertTrue(sampler.takeClosingSample())
        assertFalse(sampler.takeClosingSample())
    }

    @Test
    fun aNewPlayForgetsEarlierAudio() {
        val sampler = AudioSampler()
        sampler.advancing()
        sampler.reset()
        assertFalse(sampler.takeClosingSample())
    }
}
