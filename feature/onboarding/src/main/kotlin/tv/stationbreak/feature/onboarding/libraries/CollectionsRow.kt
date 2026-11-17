// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.libraries

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.FocusStyle
import tv.stationbreak.core.ui.screenBody
import tv.stationbreak.core.ui.screenDetail
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.politeLiveRegion

private val RowHeight = 64.dp
private val TrackSize = 28.dp
private val TrackWidth = 52.dp

/**
 * "Use my collections": one switch row under the libraries. One accessibility node: the switch role and its state,
 * with the label and the body merged. When the lineup is finalised and the switch was changed, the line under it says
 * the change waits for tomorrow (a polite live region, so a screen reader says it once it appears).
 */
@Composable
internal fun CollectionsRow(
    on: Boolean,
    tomorrow: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onToggle,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(LibrariesTags.COLLECTIONS)
                .semantics(mergeDescendants = true) {
                    role = Role.Switch
                    toggleableState = ToggleableState(on)
                },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurfaceVariant,
                focusedContainerColor = colors.surfaceVariant,
                focusedContentColor = colors.onSurfaceVariant,
                pressedContainerColor = colors.surfaceVariant,
                pressedContentColor = colors.onSurfaceVariant,
            ),
        border = FocusStyle.surfaceBorder(),
        scale = FocusStyle.surfaceScale(),
    ) {
        Row(
            modifier = Modifier.heightIn(min = RowHeight).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.libraries_collections_label),
                    style = MaterialTheme.typography.screenBody,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.libraries_collections_body),
                    style = MaterialTheme.typography.screenDetail,
                    color = colors.onSurfaceVariant,
                )
            }
            SwitchTrack(on)
        }
    }
    if (tomorrow) {
        Text(
            text = stringResource(R.string.libraries_collections_tomorrow),
            modifier = Modifier.testTag(LibrariesTags.COLLECTIONS_TOMORROW).politeLiveRegion(),
            style = MaterialTheme.typography.screenDetail,
            color = colors.onSurfaceVariant,
        )
    }
}

/** A track with a knob that sits at the end when on: position as well as colour, so the state is never colour alone. */
@Composable
private fun SwitchTrack(on: Boolean) {
    val colors = MaterialTheme.colorScheme
    Canvas(
        modifier =
            Modifier
                .size(width = TrackWidth, height = TrackSize)
                .background(if (on) colors.primary else Color.Transparent, RoundedCornerShape(TrackSize / 2))
                .border(3.dp, colors.onSurface, RoundedCornerShape(TrackSize / 2)),
    ) {
        val radius = size.height * 0.3f
        val x = if (on) size.width - size.height / 2 else size.height / 2
        drawCircle(if (on) colors.onPrimary else colors.onSurface, radius, Offset(x, size.height / 2))
    }
}
