// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import javax.inject.Inject

/**
 * The user said yes to a certificate (spec 05, Unit 3, Angua (Security)). It trusts exactly the SPKI the failed
 * handshake carried, probes the address afresh on the client that now trusts it, and only on success stores the address
 * and the pin as one record. No second fetch decides what is trusted, and nothing here sends a credential: sign-in
 * comes after, on a connection that has already been checked.
 *
 * Accepting a **changed** certificate also drops the stored session, because a token must not follow a server whose
 * identity changed (the user signs in again).
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
        ): ServerResult<ResolvedServer> {
            val pin = prompt.certificate.spki
            val result = server.trust(input, pin)
            if (result is ServerResult.Success) {
                // The pin is in force now. Either it is recorded or it is taken back, even if the caller has gone.
                val stillWanted = currentCoroutineContext().isActive
                withContext(NonCancellable) {
                    if (stillWanted) {
                        if (prompt.reason == TrustReason.Changed) sessions.clear()
                        settings.save(ServerRecord(result.value.address, pin))
                    } else {
                        settings.clearPin()
                    }
                }
                currentCoroutineContext().ensureActive()
            }
            return result
        }
    }
