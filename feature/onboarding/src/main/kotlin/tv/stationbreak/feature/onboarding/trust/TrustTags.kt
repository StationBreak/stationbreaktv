// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

/** Test tags for the trust screen, public so `app`'s instrumented tests can find its parts. */
object TrustTags {
    const val SCREEN = "trust:screen"
    const val SERVER_ADDRESS = "trust:serverAddress"
    const val WARNING = "trust:warning"
    const val FINGERPRINT_LABEL = "trust:fingerprintLabel"
    const val FINGERPRINT = "trust:fingerprint"
    const val FINGERPRINT_NOTE = "trust:fingerprintNote"
    const val VALIDITY = "trust:validity"
    const val DONT_TRUST = "trust:dontTrust"
    const val TRUST = "trust:trust"
}
