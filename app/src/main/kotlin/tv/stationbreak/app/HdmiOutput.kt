// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.stationbreak.feature.live.DisplayOutput

/**
 * Whether the TV's HDMI sink is there (#423), from the platform's own `ACTION_HDMI_AUDIO_PLUG` broadcast. The system
 * sends it when the TV is switched off or to another input, and keeps the last one, so the state is right from the
 * start. Only an explicit "unplugged" counts as gone: a device that never reports one (an emulator, a box with another
 * kind of output) is never held still. Registered for the life of the process, like the singleton it is.
 */
internal class HdmiOutput(
    context: Context,
) : DisplayOutput {
    private val state = MutableStateFlow(true)
    override val present: StateFlow<Boolean> = state.asStateFlow()

    init {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    received: Context,
                    intent: Intent,
                ) {
                    state.value = sinkPresent(intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, PLUGGED))
                }
            }
        val last =
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        last?.let { state.value = sinkPresent(it.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, PLUGGED)) }
    }

    private companion object {
        const val PLUGGED = 1
    }
}

/** The broadcast's plug state (`EXTRA_AUDIO_PLUG_STATE`): 0 is unplugged, anything else is a sink that is there. */
internal fun sinkPresent(plugState: Int): Boolean = plugState != 0
