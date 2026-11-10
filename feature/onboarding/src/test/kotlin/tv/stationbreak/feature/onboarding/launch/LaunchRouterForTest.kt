// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.launch

import tv.stationbreak.core.domain.FirstSync
import tv.stationbreak.core.domain.MediaServer
import tv.stationbreak.core.domain.ServerSettings
import tv.stationbreak.core.domain.SessionRepository
import tv.stationbreak.core.domain.selection.SelectionReady

/**
 * A router for a test that is not about the selection's gate: the gate is already open, as it is once the resume has
 * run. A test of the gate passes its own. Production builds the router through Hilt and must be given the real gate.
 */
internal fun launchRouterForTest(
    settings: ServerSettings,
    sessions: SessionRepository,
    mediaServer: MediaServer,
    firstSync: FirstSync = FirstSync.Finished,
    ready: SelectionReady = SelectionReady.Complete,
) = LaunchRouter(settings, sessions, mediaServer, firstSync, ready)
