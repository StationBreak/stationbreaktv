// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.content.ContextWrapper
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Spec 24: whether this device may warm, and why not. The app's denylist first (by model, without Media3), then a
 * decoder found missing earlier in the process, then Media3's list; a list that cannot be read fails closed.
 */
@UnstableApi
class DeviceWarmthTest {
    private var asked = 0
    private val insignia = setOf("AFTJMST12")

    private fun warmth(
        model: () -> String? = { "AFTKRT" },
        media3Lists: () -> Boolean = { false },
    ) = DeviceWarmth(model) {
        asked++
        media3Lists()
    }

    @Test
    fun aDenylistedModelCannotWithoutAskingMedia3() {
        assertEquals("denylist", warmth(model = { "AFTJMST12" }).cannot(insignia))
        assertEquals(0, asked)
    }

    @Test
    fun aModelOnMedia3sListCannot() {
        assertEquals("deviceList", warmth(media3Lists = { true }).cannot(insignia))
    }

    @Test
    fun aListThatCannotBeReadFailsClosed() {
        assertEquals("deviceCheck", warmth(media3Lists = { error("no codec list") }).cannot(insignia))
    }

    @Test
    fun aDeviceOnNeitherListCanWarm() {
        assertNull(warmth().cannot(insignia))
        assertNull("an empty denylist lets every model through", warmth(model = { "AFTJMST12" }).cannot(emptySet()))
    }

    @Test
    fun media3IsAskedOnce() {
        val device = warmth()
        repeat(3) { device.cannot(insignia) }
        assertEquals(1, asked)
    }

    @Test
    fun aMissingDecoderIsKeptForTheProcess() {
        val device = warmth()
        assertNull(device.cannot(insignia))
        device.markDecoderMissing()
        assertEquals("decoderMissing", device.cannot(insignia))
        assertEquals("decoderMissing", device.cannot(insignia))
    }

    @Test
    fun anUnreadableModelLeavesItToMedia3() {
        assertNull(warmth(model = { error("no build") }).cannot(insignia))
        assertEquals("deviceList", warmth(model = { null }, media3Lists = { true }).cannot(insignia))
    }

    @Test
    fun theProcessHasOneAndItFailsClosedOnTheJvm() {
        // No Android runtime on the JVM: the renderer or the codec list throws, which reads as "cannot".
        val one = DeviceWarmth.of(ContextWrapper(null))
        assertSame(one, DeviceWarmth.of(ContextWrapper(null)))
        assertEquals("deviceCheck", one.cannot(emptySet()))
    }
}
