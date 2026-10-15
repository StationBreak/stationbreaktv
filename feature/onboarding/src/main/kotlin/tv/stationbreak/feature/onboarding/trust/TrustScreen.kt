// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.trust

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.RequestInitialDialogFocus
import tv.stationbreak.feature.onboarding.server.BodySize
import tv.stationbreak.feature.onboarding.server.DetailsSize
import tv.stationbreak.feature.onboarding.server.Gap
import tv.stationbreak.feature.onboarding.server.ProblemIcon
import tv.stationbreak.feature.onboarding.server.ServerButton
import tv.stationbreak.feature.onboarding.server.TightGap
import tv.stationbreak.feature.onboarding.server.politeLiveRegion
import java.text.DateFormat
import java.util.Date

private val TitleSize = 36.sp
private val FingerprintSize = 28.sp
private val ContentWidth = 760.dp
private const val OVERSCAN = 0.05f
private const val TOP_OFFSET = 0.04f

/**
 * The trust screen (spec 05, Unit 3, Angua (Security)): the warning first, then the fingerprint, then the buttons.
 * **Don't trust** has default focus and Back means don't trust, so doing nothing is the safe answer. The fingerprint is
 * the SPKI SHA-256 that is stored if the user agrees, the full 64 digits in groups of four (decision 4), labelled as
 * what it is because a browser shows a different hash. It is a dialog so that Back is the platform's own dismiss.
 */
@Composable
internal fun TrustScreen(
    prompt: TrustPrompt,
    serverAddress: String,
    onTrust: () -> Unit,
    onDontTrust: () -> Unit,
) {
    val dontTrustFocus = remember { FocusRequester() }
    // The window's name for TalkBack: the screen is announced as this when it opens.
    val title =
        stringResource(if (prompt.reason == TrustReason.Changed) R.string.trust_title_changed else R.string.trust_title)
    Dialog(
        onDismissRequest = onDontTrust,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        RequestInitialDialogFocus(dontTrustFocus)
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .semantics { paneTitle = title }
                    .testTag(TrustTags.SCREEN),
        ) {
            // A 5% safe area on every side, inside the scroll area so a focused, scaled button isn't clipped.
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = maxWidth * OVERSCAN,
                            end = maxWidth * OVERSCAN,
                            top = maxHeight * TOP_OFFSET,
                            bottom = maxHeight * OVERSCAN,
                        ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = ContentWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Gap),
                ) {
                    WarningBlock(prompt, serverAddress)
                    FingerprintBlock(prompt)
                    Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                        ServerButton(
                            label = R.string.trust_dont_trust,
                            onClick = onDontTrust,
                            // The safe answer carries the weight; Trust never gets the primary fill.
                            emphasis = true,
                            modifier = Modifier.focusRequester(dontTrustFocus).testTag(TrustTags.DONT_TRUST),
                        )
                        ServerButton(
                            label =
                                if (prompt.reason == TrustReason.Changed) {
                                    R.string.trust_trust_changed
                                } else {
                                    R.string.trust_trust
                                },
                            onClick = onTrust,
                            modifier = Modifier.testTag(TrustTags.TRUST),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningBlock(
    prompt: TrustPrompt,
    serverAddress: String,
) {
    val certificate = prompt.certificate
    val notAfter = formatDate(certificate.notAfterMillis)
    val notBefore = formatDate(certificate.notBeforeMillis)
    val changed = prompt.reason == TrustReason.Changed
    Column(
        modifier = Modifier.testTag(TrustTags.WARNING).politeLiveRegion(),
        verticalArrangement = Arrangement.spacedBy(TightGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TightGap), verticalAlignment = Alignment.CenterVertically) {
            ProblemIcon()
            Text(
                text = stringResource(if (changed) R.string.trust_title_changed else R.string.trust_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = TitleSize),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // Which server this is about, so a wrong address is noticed before anything is trusted.
        Text(
            text = stringResource(R.string.trust_server_address, serverAddress),
            fontSize = BodySize,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(TrustTags.SERVER_ADDRESS),
        )
        Text(
            text =
                when (prompt.reason) {
                    TrustReason.Untrusted -> stringResource(R.string.trust_warning_untrusted)
                    TrustReason.HostnameMismatch -> stringResource(R.string.trust_warning_hostname)
                    TrustReason.Expired -> stringResource(R.string.trust_warning_expired, notAfter)
                    TrustReason.NotYetValid -> stringResource(R.string.trust_warning_not_yet_valid, notBefore)
                    TrustReason.Changed -> stringResource(R.string.trust_warning_changed)
                },
            fontSize = BodySize,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FingerprintBlock(prompt: TrustPrompt) {
    val certificate = prompt.certificate
    val lines = Fingerprint.lines(certificate.spki)
    Column(verticalArrangement = Arrangement.spacedBy(TightGap)) {
        Text(
            text = stringResource(R.string.trust_fingerprint_label),
            fontSize = BodySize,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(TrustTags.FINGERPRINT_LABEL),
        )
        Column(
            modifier =
                Modifier.testTag(TrustTags.FINGERPRINT).clearAndSetSemantics {
                    // One character at a time, so TalkBack doesn't read a group such as "1118" as a number. The
                    // children's own text is cleared, or TalkBack would read the lines as well.
                    contentDescription = Fingerprint.spoken(certificate.spki)
                },
        ) {
            // One line per row of groups, so the full 64 digits fit at the size a 10-foot screen needs.
            lines.forEach { line ->
                Text(
                    text = line,
                    fontSize = FingerprintSize,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            text =
                stringResource(
                    R.string.trust_validity,
                    formatDate(certificate.notBeforeMillis),
                    formatDate(certificate.notAfterMillis),
                ),
            fontSize = BodySize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TrustTags.VALIDITY),
        )
        Text(
            text = stringResource(R.string.trust_fingerprint_note),
            fontSize = DetailsSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TrustTags.FINGERPRINT_NOTE),
        )
    }
}

// The device's own short date, in its language and zone.
private fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
