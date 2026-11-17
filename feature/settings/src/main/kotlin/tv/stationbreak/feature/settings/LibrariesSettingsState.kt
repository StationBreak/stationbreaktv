// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.settings

import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.ItemId

/** Where the Libraries screen is: asking the server, showing its libraries, or showing the stored ones read-only. */
internal sealed interface LibrariesPhase {
    data object Loading : LibrariesPhase

    /** The server listed its libraries: the rows can be ticked and unticked. */
    data object Listed : LibrariesPhase

    /** The server could not be asked ([failure] says why; null when nobody is signed in): read-only. */
    data class Unavailable(
        val failure: MediaServerError?,
    ) : LibrariesPhase
}

/**
 * One library on the screen. [name] is null when no listing of this visit or an earlier one named it. [ticked] is
 * what the viewer last asked for, else the stored choice (chosen or being added); [pending] is a press not stored
 * yet; [adding] is a stored tick whose library is still being read.
 */
internal data class LibraryRow(
    val id: ItemId,
    val name: String?,
    val ticked: Boolean,
    val adding: Boolean,
    val pending: Boolean = false,
) {
    /** The line under the row, or null when it is stored and settled. */
    val status: RowStatus?
        get() =
            when {
                pending && ticked -> RowStatus.Saving
                pending -> RowStatus.Removing
                adding -> RowStatus.Adding
                else -> null
            }
}

/**
 * What a row says under its name: a tick not stored yet ([Saving]), an untick not stored yet ([Removing]), or a stored
 * add whose library is still being read ([Adding]).
 */
internal enum class RowStatus { Saving, Removing, Adding }

/** The line under the title after a change, or after an add was given up. */
internal enum class Notice { LastLibrary, Saved, Failed, NotListed }

/**
 * The sign-out row under the libraries (#336): [Ready], [Armed] by a first Select (a second signs out; a move or Back
 * disarms it), [Busy] while the sign-out runs, or [NotRevoked] once it ran but the server could not be told (Select or
 * Back then leaves).
 */
internal enum class SignOutStep { Ready, Armed, Busy, NotRevoked }

/**
 * What the Libraries screen shows; [focus] is the index of the focused row, and [rows]'s size when the sign-out row
 * is focused. [serverName] and [signOut] are null when nobody is signed in.
 */
internal data class LibrariesSettingsState(
    val phase: LibrariesPhase,
    val rows: List<LibraryRow>,
    val focus: Int,
    val notice: Notice?,
    val serverName: String? = null,
    val signOut: SignOutStep? = null,
) {
    /** The sign-out row has the focus. */
    val signOutFocused: Boolean get() = signOut != null && focus == rows.size

    companion object {
        val Empty = LibrariesSettingsState(LibrariesPhase.Loading, emptyList(), focus = 0, notice = null)
    }
}
