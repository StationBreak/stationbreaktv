// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import tv.stationbreak.feature.settings.LibrariesSettingsState
import tv.stationbreak.feature.settings.LibraryRow
import tv.stationbreak.feature.settings.R
import tv.stationbreak.feature.settings.RowStatus
import tv.stationbreak.feature.settings.SignOutStep

@Composable
internal fun libraryName(row: LibraryRow): String = row.name ?: stringResource(R.string.settings_libraries_unnamed)

// A press not stored yet shows as saving or removing; a stored add still being read, as adding: its words and its tag.
@Composable
internal fun statusOf(row: LibraryRow): Pair<String, String>? =
    when (row.status) {
        RowStatus.Saving -> stringResource(R.string.settings_libraries_saving) to SettingsTags.saving(row.id)
        RowStatus.Removing -> stringResource(R.string.settings_libraries_removing) to SettingsTags.removing(row.id)
        RowStatus.Adding -> stringResource(R.string.settings_libraries_adding) to SettingsTags.adding(row.id)
        null -> null
    }

@Composable
internal fun tickStateOf(row: LibraryRow): String =
    stringResource(if (row.ticked) R.string.settings_libraries_ticked else R.string.settings_libraries_unticked)

/** What a screen reader says for [row]: its name, then its line (saving, removing, adding) when it has one. */
@Composable
internal fun spokenOf(row: LibraryRow): String {
    val status = statusOf(row)?.first
    val name = libraryName(row)
    return if (status == null) name else stringResource(R.string.settings_libraries_spoken, name, status)
}

/**
 * Makes the screen's root say the focused row (its words and its tick) as a polite live region. The screen keeps one
 * Compose focus stop and moves its own focus (model-driven), so a screen reader would never follow it otherwise.
 */
@Composable
internal fun Modifier.announcingFocus(state: LibrariesSettingsState): Modifier =
    if (state.signOutFocused) announcingSignOut(state) else announcingRow(state)

// The sign-out row (#336): its words ("Sign out", or "Select again to sign out" once armed).
@Composable
private fun Modifier.announcingSignOut(state: LibrariesSettingsState): Modifier {
    val spoken = signOutLabel(state.signOut)
    return semantics {
        contentDescription = spoken
        liveRegion = LiveRegionMode.Polite
    }
}

@Composable
private fun Modifier.announcingRow(state: LibrariesSettingsState): Modifier {
    val row = state.rows.getOrNull(state.focus) ?: return this
    val spoken = spokenOf(row)
    val tick = tickStateOf(row)
    return semantics {
        contentDescription = spoken
        stateDescription = tick
        liveRegion = LiveRegionMode.Polite
    }
}

/** The sign-out row's words for its [step] (#336). */
@Composable
internal fun signOutLabel(step: SignOutStep?): String =
    stringResource(
        when (step) {
            SignOutStep.Armed -> R.string.settings_sign_out_armed
            SignOutStep.Busy -> R.string.settings_signing_out
            SignOutStep.NotRevoked -> R.string.settings_signed_out_not_revoked
            SignOutStep.Ready, null -> R.string.settings_sign_out
        },
    )
