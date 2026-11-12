// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import android.view.View
import java.util.WeakHashMap

/**
 * Who may write one view's keep-screen-on flag (#744, #377). A relaunch into the old task composes the screen a second
 * time on the same view while the first composition's dispose is still to come; that late dispose must not turn the
 * newer one's flag off. The newest [claim] owns the flag; writes and the [release] of an older owner change nothing.
 * The first owner remembers the flag as it found it and [release] puts that back. Main thread only.
 */
internal class KeepOnSlot(
    private val flag: Flag,
) {
    /** The view's flag, as far as the slot needs it. */
    interface Flag {
        var value: Boolean
    }

    private var owner: Any? = null
    private var before = false

    /** Takes the flag over from any older owner, which keeps the value found before the first owner. */
    fun claim(token: Any) {
        if (owner == null) before = flag.value
        owner = token
    }

    /** Writes [value] if [token] owns the flag; false when it does not and nothing was written. */
    fun write(
        token: Any,
        value: Boolean,
    ): Boolean {
        if (owner !== token) return false
        flag.value = value
        return true
    }

    /** The owner's end: the flag goes back to what it was before. False for an older owner, which changes nothing. */
    fun release(token: Any): Boolean {
        if (owner !== token) return false
        flag.value = before
        owner = null
        return true
    }

    companion object {
        private val slots = WeakHashMap<View, KeepOnSlot>()

        /** The slot of [view], shared by every composition on it. */
        fun of(view: View): KeepOnSlot =
            slots.getOrPut(view) {
                KeepOnSlot(
                    object : Flag {
                        override var value: Boolean
                            get() = view.keepScreenOn
                            set(v) {
                                view.keepScreenOn = v
                            }
                    },
                )
            }
    }
}
