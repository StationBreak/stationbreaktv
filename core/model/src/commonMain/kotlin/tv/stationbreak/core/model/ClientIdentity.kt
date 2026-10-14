// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * What the app tells a media server about itself: the client name, this device's name and the app version. The
 * adapter puts them in the `Authorization` header of every request after the probe. Not secrets, but the device name
 * is user-chosen text, so the adapter percent-encodes it.
 */
data class ClientIdentity(
    val clientName: String,
    val deviceName: String,
    val appVersion: String,
)
