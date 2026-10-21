// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback.probe

import android.media.MediaCodecList
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.stationbreak.core.model.Codecs

/** The real platform, on the API 25 emulator: the inventory lists decoders, and only decoders. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AndroidCodecInventoryTest {
    private val inventory = AndroidCodecInventory(ApplicationProvider.getApplicationContext())

    @Test
    fun theEmulatorHasAnH264DecoderAndNoEncodersListed() {
        val avc = inventory.videoDecoders().filter { it.mime == "video/avc" }
        assertTrue("an H.264 decoder is listed", avc.isNotEmpty())

        // Every MIME type the inventory lists has a real decoder behind it, never only an encoder.
        val decoderMimes =
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.asList() }
                .map { it.lowercase() }
                .toSet()
        assertTrue(inventory.videoDecoders().all { it.mime in decoderMimes })
        assertTrue(inventory.audioDecoders().all { it in decoderMimes })
        assertTrue(avc.all { it.maxWidth > 0 && it.maxHeight > 0 })

        val caps = CapabilityProbe(inventory).probe()
        assertTrue(caps.videoCodecs.any { it.codec == Codecs.H264 })
    }
}
