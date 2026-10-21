// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.playback

import tv.stationbreak.core.model.DeviceCapabilities
import java.util.concurrent.atomic.AtomicReference

/**
 * The device's capabilities, probed once and kept. A full probe is slow (it walks the codec list), so it runs on the
 * first [get] and not again; [refreshSink] re-reads only the part that changes while the app runs, the HDMI sink's
 * audio. [probe] and [withSink] are `CapabilityProbe`'s two calls.
 */
class DeviceCapabilitiesCache(
    private val probe: () -> DeviceCapabilities,
    private val withSink: (DeviceCapabilities) -> DeviceCapabilities,
) {
    private val cached = AtomicReference<DeviceCapabilities?>(null)

    fun get(): DeviceCapabilities =
        cached.get() ?: synchronized(this) {
            cached.get() ?: probe().also { cached.set(it) }
        }

    /** The sink came or went. Before the first [get] there is nothing to refresh: the probe will see the new sink. */
    fun refreshSink() {
        synchronized(this) {
            cached.get()?.let { cached.set(withSink(it)) }
        }
    }
}
