// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.EntryPointAccessors
import tv.stationbreak.core.ui.ChannelReadyNotice

/**
 * Draws [content] and, over it, the notice that a channel the viewer switched on is ready (#776), while there is one.
 * The notice takes no focus and no key. It reads the app-wide state, so a recreated activity shows the same notice.
 */
@Composable
internal fun ReadyNoticeHost(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val readiness =
        remember(context) {
            EntryPointAccessors.fromApplication(context, ChannelReadinessEntryPoint::class.java).readiness()
        }
    val notice by readiness.notice.collectAsState()
    Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
        content()
        notice?.let { ChannelReadyNotice(it.channels.map { channel -> channel.number to channel.name }) }
    }
}
