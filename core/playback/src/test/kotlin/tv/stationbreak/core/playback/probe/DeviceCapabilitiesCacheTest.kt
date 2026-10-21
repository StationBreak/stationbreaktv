// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.model.DeviceCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

    @Test
    fun refreshDoesNotWaitForTheProbe() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow =
            DeviceCapabilitiesCache(
                probe = {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    base
                },
                withSink = { it.copy(maxAudioChannels = 6) },
            )
        val getter = thread { slow.get() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val refresher = thread { slow.refreshSink() }
        refresher.join(2_000)
        val waited = refresher.isAlive
        release.countDown()
        getter.join(10_000)
        refresher.join(10_000)
        assertFalse("refreshSink waited for the probe", waited)
    }

    @Test
    fun refreshDuringProbeIsNotLost() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow =
            DeviceCapabilitiesCache(
                probe = {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    base
                },
                withSink = { it.copy(audioPassthrough = setOf("ac3"), maxAudioChannels = 6) },
            )
        val getter = thread { slow.get() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        slow.refreshSink()
        release.countDown()
        getter.join(10_000)
        val after = slow.get()
        assertEquals(setOf("ac3"), after.audioPassthrough)
        assertEquals(6, after.maxAudioChannels)
    }
}
