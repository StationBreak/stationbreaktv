// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

import android.util.Log

/**
 * Device-side switches over the spec 24 tunables ([WarmConfig]): a debug or benchmark build reads them so a device run
 * can turn warming (or the standby's audio) off without a new APK; a release build uses [None]. A switch can only turn
 * a tunable off, never on.
 */
interface WarmSwitches {
    /** False when a switch turns warming off. */
    fun warmAllowed(): Boolean

    /** False when a switch turns the standby's audio off. */
    fun audioAllowed(): Boolean

    companion object {
        /** The system property tag of the master switch (`log.tag.SBWARM`), also the tag of the SBWARM lines. */
        const val MASTER_TAG = "SBWARM"

        /** The system property tag of the audio switch (`log.tag.SBWARMAUD`). */
        const val AUDIO_TAG = "SBWARMAUD"

        /** No switches (release builds): the tunables alone decide. */
        val None: WarmSwitches =
            object : WarmSwitches {
                override fun warmAllowed(): Boolean = true

                override fun audioAllowed(): Boolean = true
            }

        /**
         * The `log.tag.<tag>` switches, for debug and benchmark builds: a tag set to ASSERT turns its switch off.
         * [read] answers whether WARN is loggable for a tag, or null when it cannot say (then the switch stays on).
         */
        fun logTags(read: (String) -> Boolean? = ::systemLoggable): WarmSwitches =
            object : WarmSwitches {
                override fun warmAllowed(): Boolean = read(MASTER_TAG) != false

                override fun audioAllowed(): Boolean = read(AUDIO_TAG) != false
            }
    }
}

/** True when warming is wanted: the tunable, and no switch turns it off. */
fun WarmConfig.warmOn(switches: WarmSwitches): Boolean = warmNextDecoder && switches.warmAllowed()

/** True when the standby keeps its audio track open (muted): the tunable, and no switch turns it off. */
fun WarmConfig.audioOn(switches: WarmSwitches): Boolean = warmNextAudio && switches.audioAllowed()

/** Reads `log.tag.<tag>`: true when WARN is loggable (not ASSERT), null where the log service is unreadable. */
internal fun systemLoggable(tag: String): Boolean? = runCatching { Log.isLoggable(tag, Log.WARN) }.getOrNull()
