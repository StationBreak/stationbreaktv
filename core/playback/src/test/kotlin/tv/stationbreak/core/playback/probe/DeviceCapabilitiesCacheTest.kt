// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.model.DeviceCapabilities

class DeviceCapabilitiesCacheTest {
    private val base =
        DeviceCapabilities(
            containers = setOf("mkv"),
            videoCodecs = emptyList(),
            audioDecoders = setOf("aac"),
            audioPassthrough = emptySet(),
            maxAudioChannels = 2,
        )
    private var probes = 0
    private val cache =
        DeviceCapabilitiesCache(
            probe = { base.also { probes++ } },
            withSink = { it.copy(audioPassthrough = setOf("ac3"), maxAudioChannels = 6) },
        )

    @Test
    fun theProbeRunsOnceAndIsKept() {
        cache.get()
        cache.get()
        assertEquals(1, probes)
    }

    @Test
    fun aSinkChangeRefreshesOnlyTheSinkPartWithoutProbingAgain() {
        cache.get()
        cache.refreshSink()
        val after = cache.get()
        assertEquals(1, probes)
        assertEquals(setOf("ac3"), after.audioPassthrough)
        assertEquals(6, after.maxAudioChannels)
        assertEquals(base.containers, after.containers)
    }

    @Test
    fun aSinkChangeBeforeTheFirstProbeIsLeftToTheProbe() {
        cache.refreshSink()
        assertEquals(0, probes)
        assertEquals(base, cache.get())
    }
}
