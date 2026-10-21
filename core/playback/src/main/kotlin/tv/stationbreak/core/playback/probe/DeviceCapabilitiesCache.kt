// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback.probe

import tv.stationbreak.core.model.DeviceCapabilities
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The device's capabilities, probed once and kept. A full probe is slow (it walks the codec list), so it runs on the
 * first [get] and not again; [refreshSink] re-reads only the part that changes while the app runs, the HDMI sink's
 * audio. [probe] and [withSink] are [CapabilityProbe]'s two calls.
 *
 * [refreshSink] never waits for a probe: it runs on the main thread (a broadcast), and the probe takes seconds on a
 * slow TV. Only concurrent [get] callers wait for each other, so the probe runs once. A sink change that arrives while
 * the probe is running is remembered and applied as soon as the probe's result is stored.
 *
 * Warm it off the main thread at start (`StationBreakApp`), so the first screen that plays never pays for the probe.
 */
class DeviceCapabilitiesCache(
    private val probe: () -> DeviceCapabilities,
    private val withSink: (DeviceCapabilities) -> DeviceCapabilities,
) {
    private val cached = AtomicReference<DeviceCapabilities?>(null)
    private val sinkChanged = AtomicBoolean(false)
    private val probeLock = Any()

    fun get(): DeviceCapabilities =
        cached.get() ?: synchronized(probeLock) {
            cached.get() ?: run {
                // A change before this point is seen by the probe itself.
                sinkChanged.set(false)
                val probed = probe()
                cached.set(probed)
                applyPendingSink()
                cached.get() ?: probed
            }
        }

    /**
     * The sink came or went. Never blocks. Before the first probe there is nothing to refresh (the probe will see the
     * new sink); during it, the change is flagged and applied when the probe has stored its result.
     */
    fun refreshSink() {
        sinkChanged.set(true)
        applyPendingSink()
    }

    /** Applies a flagged sink change to the stored capabilities, if there are any yet. Safe from any thread. */
    private fun applyPendingSink() {
        while (cached.get() != null && sinkChanged.getAndSet(false)) {
            while (true) {
                val current = cached.get() ?: break
                if (cached.compareAndSet(current, withSink(current))) break
            }
        }
    }
}
