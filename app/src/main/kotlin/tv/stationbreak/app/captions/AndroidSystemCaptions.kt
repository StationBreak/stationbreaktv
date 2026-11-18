// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.captions

import android.content.Context
import android.view.accessibility.CaptioningManager
import tv.stationbreak.core.domain.tracks.SystemCaptions
import java.util.Locale

/**
 * Android's caption setting (Settings, Accessibility, Captions), read each time the live screen asks for the carried
 * choice, so a change in Settings counts from the next programme (#725).
 */
internal class AndroidSystemCaptions(
    context: Context,
) : SystemCaptions {
    private val captioning = context.getSystemService(CaptioningManager::class.java)

    override fun enabled(): Boolean = captioning?.isEnabled == true

    // The caption language the viewer picked there, else the device's own language.
    override fun language(): String? = (captioning?.locale ?: Locale.getDefault()).language.takeIf { it.isNotBlank() }
}
