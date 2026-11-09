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
 * the stored choice (chosen or being added); [adding] is a tick whose library is still being read.
 */
internal data class LibraryRow(
    val id: ItemId,
    val name: String?,
    val ticked: Boolean,
    val adding: Boolean,
)

/** The line under the title after a change, or after an add was given up. */
internal enum class Notice { LastLibrary, Saved, Failed, NotListed }

/** What the Libraries screen shows; [focus] is the index of the focused row. */
internal data class LibrariesSettingsState(
    val phase: LibrariesPhase,
    val rows: List<LibraryRow>,
    val focus: Int,
    val notice: Notice?,
) {
    companion object {
        val Empty = LibrariesSettingsState(LibrariesPhase.Loading, emptyList(), focus = 0, notice = null)
    }
}
