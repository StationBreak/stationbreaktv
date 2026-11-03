// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.drawnFocus
import tv.stationbreak.feature.channels.ui.ChannelsPalette
import tv.stationbreak.feature.channels.ui.keepVisible

private val LineGap = 2.dp

/**
 * A list that draws only what is on screen and keeps line [focused] in view. [lines] are drawn by [content], which is
 * told whether its line has the focus; the focus is a value from the ViewModel, not Compose's.
 */
@Composable
internal fun <T> FocusedLines(
    lines: List<T>,
    focused: Int,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    content: @Composable (T, Boolean) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(focused, lines) { if (focused >= 0) state.keepVisible(focused) }
    LazyColumn(modifier, state = state, verticalArrangement = Arrangement.spacedBy(LineGap)) {
        items(lines.size, key = { key(lines[it]) }) { i -> content(lines[i], i == focused) }
    }
}

/** One focusable line: the shared focus treatment, a tag, and the focus as its selection. */
@Composable
@Suppress("LongParameterList") // The line's modifier, state and role, with its content last.
internal fun FocusLine(
    focused: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    ticked: Boolean? = null,
    role: Role? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .drawnFocus(focused)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag(tag)
            .semantics(mergeDescendants = true) {
                selected = focused
                if (ticked != null) toggleableState = ToggleableState(ticked)
                val announced = role ?: Role.Checkbox.takeIf { ticked != null }
                if (announced != null) this.role = announced
            },
    ) {
        content()
    }
}

@Composable
internal fun TextLine(
    text: String,
    focused: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    role: Role? = null,
) {
    FocusLine(focused, tag, modifier, role = role) {
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The editable text, shown only while the viewer types; Done on the keyboard ends it ([onDone]). */
@Composable
internal fun TypedField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    inputTag: String,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The cursor goes to the end only when the ViewModel's value changed (not on every recomposition), so a
    // ViewModel that later normalises the text does not drop the keyboard's composition while the viewer types.
    LaunchedEffect(value) { if (value != text.text) text = TextFieldValue(value, TextRange(value.length)) }
    val inner = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        inner.requestFocus()
        keyboard?.show()
    }
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it.text)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = ChannelsPalette.text),
        cursorBrush = SolidColor(ChannelsPalette.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier =
            modifier
                .fillMaxWidth()
                .focusRequester(inner)
                .onFocusChanged {
                    if (it.isFocused) {
                        focusedOnce = true
                    } else if (focusedOnce) {
                        onDone()
                    }
                }.testTag(inputTag),
    )
}
