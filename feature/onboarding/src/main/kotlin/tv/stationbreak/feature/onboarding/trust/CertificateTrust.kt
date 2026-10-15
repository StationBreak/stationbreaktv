// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.model.SpkiPin
import javax.inject.Inject

/** How a confirmation ended. */
internal sealed interface TrustOutcome {
    /** The pin is in force and the address and pin are stored. */
    data class Trusted(
        val server: ResolvedServer,
    ) : TrustOutcome

    /** The probe failed, or showed another certificate than the one confirmed. No pin was kept. */
    data class Refused(
        val error: MediaServerError,
    ) : TrustOutcome

    /** The probe passed but the record could not be stored, so the pin was taken back. Nothing is connected. */
    data object NotSaved : TrustOutcome
}

/**
 * The user said yes to a certificate (spec 05, Unit 3, Angua (Security)). It trusts exactly the SPKI the failed
 * handshake carried, probes the address afresh on the client that now trusts it, and only on success stores the address
 * and the pin as one record. No second fetch decides what is trusted, and nothing here sends a credential: sign-in
 * comes after, on a connection that has already been checked.
 *
 * **Every** confirmation drops the stored session first, before the trust is even tried: a token must not follow a
 * server whose certificate the user is now vouching for, and the user signs in again.
 */
internal class CertificateTrust
    @Inject
    constructor(
        private val server: MediaServer,
        private val settings: ServerSettings,
        private val sessions: SessionRepository,
    ) {
        /** [input] is what was typed (or the stored address's URL), the same text the failed attempt used. */
        suspend fun confirm(
            input: String,
            prompt: TrustPrompt,
        ): TrustOutcome {
            withContext(NonCancellable) { sessions.clear() }
            val pin = prompt.certificate.spki
            return when (val result = server.trust(input, pin)) {
                is ServerResult.Failure -> TrustOutcome.Refused(result.error)
                is ServerResult.Success -> record(result.value, pin)
            }
        }

        // The pin is in force now. Either it is recorded or it is taken back, even if the caller has gone.
        private suspend fun record(
            trusted: ResolvedServer,
            pin: SpkiPin,
        ): TrustOutcome {
            val stillWanted = currentCoroutineContext().isActive
            val saved =
                withContext(NonCancellable) {
                    val written = stillWanted && settings.save(ServerRecord(trusted.address, pin))
                    if (!written) settings.clearPin()
                    written
                }
            currentCoroutineContext().ensureActive()
            return if (saved) TrustOutcome.Trusted(trusted) else TrustOutcome.NotSaved
        }
    }
