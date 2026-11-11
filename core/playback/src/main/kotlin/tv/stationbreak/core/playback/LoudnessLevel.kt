// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

/**
 * The gain volume levelling has learned, in dB, shared by every player slot (the front and the warm standby) so a
 * hand-over carries it on instead of starting the next player at 0 dB. The processor behind the audible player writes
 * it; the others read it. One value, written from an audio thread and read from others.
 */
class LoudnessLevel {
    @Volatile
    var gainDb: Double = 0.0
        internal set
}

/**
 * Volume levelling for a player: whether it is [enabled] now (asked as audio is processed, so a change in Settings
 * takes effect at once) and the [level] its slots share. A factory owns one, so the learned gain outlives a player.
 */
class VolumeLevelling(
    val enabled: () -> Boolean,
    val level: LoudnessLevel = LoudnessLevel(),
)
