// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.ui.ChannelChangeStyle
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.core.ui.remoteKeyOf
import tv.stationbreak.core.ui.remoteKeyToAct
import tv.stationbreak.feature.guide.R

/** What a Settings row is: a value to change, a way to another screen, or something to do. */
internal enum class RowKind {
    /** Its value shows on the right; Left and Right change it, and OK moves it on to the next one. */
    Choice,

    /** Opens another screen over the guide; drawn with a chevron. */
    Link,

    /** Does something at once (or asks first); drawn with an OK key cap. */
    Action,
}

/** The rows of the guide's Settings sheet, top to bottom, with what each [kind] is and its [label]. */
internal enum class SettingsRow(
    val kind: RowKind,
    @param:StringRes val label: Int,
) {
    /** Dark or Light, the guide family's look. */
    Appearance(RowKind.Choice, R.string.guide_settings_appearance),

    /** Safe or Edge: how far in from the screen's edge text keeps. */
    ScreenFit(RowKind.Choice, R.string.guide_settings_screen_fit),

    /** What live TV shows while the channel changes: Stan's stand-by card (the default) or static. */
    ChannelChange(RowKind.Choice, R.string.guide_settings_channel_change),

    /** Off or On (volume levelling, #498). */
    VolumeLevelling(RowKind.Choice, R.string.guide_settings_levelling),

    /** Shows Settings, Libraries (spec 26) over the guide. */
    Libraries(RowKind.Link, R.string.guide_settings_libraries),

    /** Updates the library now (spec 26, Part 5); the line under it says how that stands. */
    SyncNow(RowKind.Action, R.string.guide_settings_sync_now),

    /** Asks whether to sign out of this TV, over the sheet (#336); the safe answer has the focus. */
    SignOut(RowKind.Action, R.string.guide_settings_sign_out),

    /** Off or On: whether a crash may be reported to the owner's endpoint (#96). Shown only in a build that has one. */
    CrashReports(RowKind.Choice, R.string.guide_settings_crash_reports),
    ;

    /** Whether the sheet shows this row for [values]: all but crash reports, which need a build with an endpoint. */
    fun isShown(values: SettingsValues): Boolean = this != CrashReports || values.crashReportsAvailable
}

/** The sheet's sections, top to bottom: each a teletype [flag] over its [rows], which run in [SettingsRow]'s order. */
internal enum class SettingsGroup(
    @param:StringRes val flag: Int,
    val rows: List<SettingsRow>,
) {
    Picture(R.string.guide_settings_group_picture, listOf(SettingsRow.Appearance, SettingsRow.ScreenFit)),
    Playback(R.string.guide_settings_group_playback, listOf(SettingsRow.ChannelChange, SettingsRow.VolumeLevelling)),
    Library(R.string.guide_settings_group_library, listOf(SettingsRow.Libraries, SettingsRow.SyncNow)),
    Account(R.string.guide_settings_group_account, listOf(SettingsRow.SignOut)),
    Privacy(R.string.guide_settings_group_privacy, listOf(SettingsRow.CrashReports)),
}

/** The values the choice rows show: the viewer's own, as their stores hold them. */
internal data class SettingsValues(
    val theme: GuideTheme = GuideTheme.Dark,
    val fit: ScreenFit = ScreenFit.Safe,
    val channelChange: ChannelChangeStyle = ChannelChangeStyle.Stan,
    val levelling: Boolean = true,
    val crashReports: Boolean = false,
    val crashReportsAvailable: Boolean = false,
)

/**
 * The Settings sheet while it is open: the [row] under the D-pad, and what it shows over the guide, the sheet staying
 * open under it on its row: Settings, Libraries ([librariesShown]) or the sign-out question ([signOutShown]).
 */
internal data class SettingsPanel(
    val row: SettingsRow = SettingsRow.Appearance,
    val librariesShown: Boolean = false,
    val signOutShown: Boolean = false,
) {
    /** A screen over the guide has the keys. */
    val covered: Boolean get() = librariesShown || signOutShown

    /** The Libraries screen has finished: back to the sheet, on Libraries. */
    fun librariesDone(): SettingsPanel = copy(librariesShown = false)

    /** The viewer stayed signed in: back to the sheet, on Sign out. */
    fun signOutDone(): SettingsPanel = copy(signOutShown = false)
}

/**
 * What a key did to the sheet: the [panel] after it (null when it closed), the value it chose on a choice row, if any
 * (with the path that chose it, for the stores' logs), and whether it asks for a [syncNow].
 */
internal data class SettingsStep(
    val panel: SettingsPanel?,
    val theme: GuideTheme? = null,
    val syncNow: Boolean = false,
    val fit: ScreenFit? = null,
    val levelling: Boolean? = null,
    val levellingFrom: String? = null,
    val channelChange: ChannelChangeStyle? = null,
    val channelChangeFrom: String? = null,
    val crashReports: Boolean? = null,
    val crashReportsFrom: String? = null,
)

/** The line under Update library: the string [text], with the [time] it updated at for the one that has it. */
internal data class SyncNowLine(
    @param:StringRes val text: Int,
    val time: String? = null,
)

/**
 * What the line under Update library says for [state]: nothing before a press (or after one with nothing to run),
 * updating, when it updated (the time as [time] writes it: the device's own format on screen), or why it failed, with
 * "Nothing changed." only when it failed before writing anything.
 */
internal fun syncNowLine(
    state: SyncNowState,
    time: (Long) -> String,
): SyncNowLine? =
    when (state) {
        SyncNowState.Idle -> null
        SyncNowState.Running -> SyncNowLine(R.string.guide_sync_running)
        is SyncNowState.Done -> SyncNowLine(R.string.guide_sync_done, time(state.atMs))
        is SyncNowState.Failed -> SyncNowLine(failureLine(state.failure, state.changedNothing))
    }

// Why it failed; "Nothing changed." only when nothing was written.
@StringRes
private fun failureLine(
    failure: SyncFailure,
    changedNothing: Boolean,
): Int = if (changedNothing) nothingChangedLine(failure) else reasonLine(failure)

@StringRes
private fun nothingChangedLine(failure: SyncFailure): Int =
    when (failure) {
        SyncFailure.Network -> R.string.guide_sync_failed_network
        SyncFailure.SessionExpired -> R.string.guide_sync_failed_session
        SyncFailure.Server, SyncFailure.CacheRace -> R.string.guide_sync_failed_server
        SyncFailure.Suspect -> R.string.guide_sync_failed_suspect
        SyncFailure.LibrariesGone -> R.string.guide_sync_failed_libraries_gone
    }

@StringRes
private fun reasonLine(failure: SyncFailure): Int =
    when (failure) {
        SyncFailure.Network -> R.string.guide_sync_reason_network
        SyncFailure.SessionExpired -> R.string.guide_sync_reason_session
        SyncFailure.Server, SyncFailure.CacheRace -> R.string.guide_sync_reason_server
        SyncFailure.Suspect -> R.string.guide_sync_reason_suspect
        SyncFailure.LibrariesGone -> R.string.guide_sync_reason_libraries_gone
    }

/** How a choice row's value was moved, and the word the store's log gets for it. */
internal enum class Change(
    val from: String,
) {
    Previous("settingsLeft"),
    Next("settingsRight"),
    Cycle("settingsOk"),
}

/**
 * A key while the Settings sheet is open, read from [values] as they stand, or null when the key is not the sheet's
 * (the media keys, Menu). The D-pad, OK and Back are all kept, so the grid never moves under the sheet. The shared
 * remote policy (#823) says when a key acts.
 *
 * Up and Down move between the rows and stop at the ends. On a choice row Left and Right step its value back and on,
 * stopping at the ends, and OK moves it on to the next value, round to the first after the last: a choice never closes
 * the sheet. OK on Libraries shows the Libraries screen, on Update library asks for an update and keeps the sheet open
 * (the line under the row says how it goes), and on Sign out shows the sign-out question. Back closes the sheet, as the
 * guide's own Back does. While a screen shows over it, every key is kept and does nothing: that screen has the keys.
 * A step that leaves a value as it was does not choose it again.
 */
internal fun settingsStep(
    panel: SettingsPanel,
    values: SettingsValues,
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): SettingsStep? {
    remoteKeyOf(key) ?: return null
    val same = SettingsStep(panel)
    return if (panel.covered) {
        same
    } else {
        when (remoteKeyToAct(key, type, repeatCount)) {
            RemoteKey.Back -> SettingsStep(null)
            RemoteKey.Up -> SettingsStep(panel.moved(-1, values))
            RemoteKey.Down -> SettingsStep(panel.moved(1, values))
            RemoteKey.Left -> panel.changed(values, Change.Previous)
            RemoteKey.Right -> panel.changed(values, Change.Next)
            RemoteKey.Select -> panel.selected(values)
            null -> same
        }
    }
}

// OK on the sheet's row, once per press.
private fun SettingsPanel.selected(values: SettingsValues): SettingsStep =
    when (row.kind) {
        RowKind.Choice -> changed(values, Change.Cycle)
        RowKind.Link -> SettingsStep(copy(librariesShown = true))
        RowKind.Action -> if (row == SettingsRow.SignOut) SettingsStep(copy(signOutShown = true)) else syncStep
    }

private val SettingsPanel.syncStep: SettingsStep get() = SettingsStep(this, syncNow = true)

// Up and Down move between the rows the sheet shows and stop at the ends.
private fun SettingsPanel.moved(
    by: Int,
    values: SettingsValues,
): SettingsPanel {
    val rows = SettingsRow.entries.filter { it.isShown(values) }
    return copy(row = rows[(rows.indexOf(row) + by).coerceIn(0, rows.lastIndex)])
}
