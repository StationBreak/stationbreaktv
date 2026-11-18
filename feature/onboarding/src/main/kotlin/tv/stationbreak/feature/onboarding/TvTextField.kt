// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.FocusStyle
import tv.stationbreak.core.ui.HINT_ALPHA
import tv.stationbreak.core.ui.screenBody

private val FieldHeight = 56.dp
private const val MASK_CHARACTER = '•'

/**
 * The keyboard each onboarding field asks for. A remote app's phone keyboard (#810) is an IME like any other, so these
 * decide what it offers and which action key it shows: Go on the address runs Test, Next on the username moves to the
 * password, Done on the password runs Sign in.
 */
internal object FieldKeyboard {
    val Address =
        KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        )
    val Username =
        KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
        )
    val Password =
        KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        )
}

/**
 * A text field for a remote (spec 05, the 10-foot rules). It is focusable like a button; Select starts editing and
 * opens the system keyboard, which never opens by itself. The IME action (from [keyboardOptions], run through
 * [onImeAction]) ends editing, then the caller moves on (Next on a username) or acts (Go on an address, Done on a
 * password).
 *
 * [label] is the field's name for a screen reader ("Username"); the hint is only a hint and goes when text is typed.
 * An unmasked field also says its value as its state. With [masked] the text is shown as dots while it is edited and
 * while it rests, and the field is marked as a password so the system keyboard treats it as one. [fieldTag] is on the
 * focusable box and [inputTag] on the editable text. A non-null [error] is set as the field's semantic error, so a
 * screen reader says it with the field's name.
 */
@Suppress("LongParameterList") // One parameter each for value, hint, tags, keyboard and action.
@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String,
    fieldTag: String,
    inputTag: String,
    keyboardOptions: KeyboardOptions,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    error: String? = null,
) {
    var editing by remember { mutableStateOf(false) }
    val inner = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing) {
            inner.requestFocus()
            keyboard?.show()
        }
    }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { editing = true },
        modifier =
            modifier.fillMaxWidth().testTag(fieldTag).semantics {
                contentDescription = label
                // Focus returns here after a bad entry, so the field itself says what is wrong.
                error?.let { error(it) }
                // A masked value is never spoken.
                if (!masked && value.isNotEmpty()) stateDescription = value
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
        Column(
            modifier = Modifier.heightIn(min = FieldHeight).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (editing) {
                TextInput(
                    value = value,
                    onValueChange = onValueChange,
                    inner = inner,
                    inputTag = inputTag,
                    keyboardOptions = keyboardOptions,
                    masked = masked,
                    onImeAction = {
                        editing = false
                        onImeAction()
                    },
                    onLeave = { editing = false },
                )
            } else if (value.isEmpty()) {
                FieldText(hint, colors.onSurfaceVariant.copy(alpha = HINT_ALPHA))
            } else {
                val shown = if (masked) MASK_CHARACTER.toString().repeat(value.length) else value
                FieldText(shown, colors.onSurface)
            }
        }
    }
}

/** The field's text when not editing: the hint, or the value (masked, if it is a secret). */
@Composable
private fun FieldText(
    text: String,
    color: Color,
) = Text(text = text, style = MaterialTheme.typography.screenBody, color = color)

/** The editable text, shown only while editing. */
@Suppress("LongParameterList") // As [TvTextField].
@Composable
private fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    inner: FocusRequester,
    inputTag: String,
    keyboardOptions: KeyboardOptions,
    masked: Boolean,
    onImeAction: () -> Unit,
    onLeave: () -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The field keeps its own cursor, so a change that did not come from typing (a cleared password) is taken in here.
    if (value != text.text) text = TextFieldValue(value, TextRange(value.length))
    var focusedOnce by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    var base = Modifier.fillMaxWidth()
    if (masked) base = base.semantics { password() }
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onValueChange(it.text)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.screenBody.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.onSurface),
        visualTransformation = if (masked) PasswordVisualTransformation(MASK_CHARACTER) else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        keyboardActions =
            KeyboardActions(onDone = { onImeAction() }, onNext = { onImeAction() }, onGo = { onImeAction() }),
        modifier =
            base
                .focusRequester(inner)
                .onFocusChanged {
                    if (it.isFocused) {
                        focusedOnce = true
                    } else if (focusedOnce) {
                        onLeave()
                    }
                }.testTag(inputTag),
    )
}

/** The tip under a step's help line: type with the phone's keyboard through the TV's remote app (#810). Plain text. */
@Composable
internal fun RemoteAppTip(tag: String) =
    Text(
        text = stringResource(R.string.remote_app_tip),
        style = MaterialTheme.typography.screenBody,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(tag),
    )
