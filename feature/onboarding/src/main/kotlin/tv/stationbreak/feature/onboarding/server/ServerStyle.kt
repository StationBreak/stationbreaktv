// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.ui.FocusStyle
import tv.stationbreak.feature.onboarding.R

// 10-foot sizes (spec 05, Adorabelle's defaults): body text is 18 sp or more and every target is 48 dp or more. The
// buttons' padding makes them about 56 dp tall.
internal val BodySize = 20.sp
internal val DetailsSize = 18.sp
internal val ButtonPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
internal val ProblemIconSize = 32.dp
internal val Gap = 20.dp
internal val TightGap = 12.dp

/**
 * A message that appears on its own (an error, a status): a screen reader announces it when it shows, without cutting
 * in on what it is already saying.
 */
internal fun Modifier.politeLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/**
 * One button: the shared focus treatment, a target of about 56 dp and body-size text. The main action of a screen
 * passes [emphasis] for a stronger fill; its focused look is the same as any button's. [description] is what a screen
 * reader says when the visible [label] alone is not enough ("Show" beside a password field).
 */
@Suppress("LongParameterList") // The label and click, plus the optional look and spoken name.
@Composable
internal fun ServerButton(
    @StringRes label: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: Boolean = false,
    description: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = if (description == null) modifier else modifier.semantics { contentDescription = description },
        enabled = enabled,
        colors =
            if (emphasis) {
                ButtonDefaults.colors(containerColor = colors.primary, contentColor = colors.onPrimary)
            } else {
                ButtonDefaults.colors()
            },
        border = FocusStyle.buttonBorder(),
        scale = FocusStyle.buttonScale(),
        contentPadding = ButtonPadding,
    ) {
        Text(stringResource(label), fontSize = BodySize)
    }
}

/** The icon beside every problem, so an error is never shown by colour alone. */
@Composable
internal fun ProblemIcon() {
    Icon(
        modifier = Modifier.size(ProblemIconSize),
        painter = painterResource(R.drawable.ic_problem),
        contentDescription = stringResource(R.string.server_problem_icon),
        tint = MaterialTheme.colorScheme.error,
    )
}
