// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.playback

/**
 * Why a flip did not take the warm path; the `warmReason=` field of the SBLIVE lines (spec 24). Each reason has one
 * producer: the player ([VideoPlayer], at the play that was given a [PlayerSource.warmKey]) or the controller (the
 * tuner, for a play it gave no key). A reason the player produces reaches the controller in
 * [PlayerEvent.WarmDecided]; the controller's own it decides before the play.
 */
enum class WarmReason(
    val label: String,
) {
    /** Player: the play was a hand-over to the ready standby. */
    None("none"),

    /**
     * Player: warming is off for this player (the tunable or a switch, the device cannot, the breaker is open, or a
     * memory trim paused it). Controller: warming is not wanted at all, so it gave no key.
     */
    Disabled("disabled"),

    /** Player: the standby for the key was still being built. */
    NotReady("notReady"),

    /** Player: no standby is held for the key, it holds another key, or it was built from a different source. */
    Mismatch("mismatch"),

    /** Player: the standby failed (a player error, or it was not usable at the swap). */
    Error("error"),

    /** Player: the standby did not become ready within the ready timeout. */
    Timeout("timeout"),

    /** Controller: the channel flipped to is not the neighbour that was warmed. */
    WrongDirection("wrongDirection"),

    /** Controller: the channel flipped to plays through a transcode, which is never warmed. */
    Transcode("transcode"),

    /** Controller: the tune had no prepared handle to warm (the first tune, or a handle that was stale). */
    NoHandle("noHandle"),
}
