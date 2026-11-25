// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.annotation.StringRes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import tv.stationbreak.core.domain.SyncFailure
import tv.stationbreak.core.domain.SyncNowState
import tv.stationbreak.core.domain.hours.HoursSchedule
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.store.ProOffer
import tv.stationbreak.core.ui.ChannelChangeStyle
import tv.stationbreak.core.ui.RemoteKey
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.core.ui.remoteKeyOf
import tv.stationbreak.core.ui.remoteKeyToAct
import tv.stationbreak.feature.guide.PinSubmit
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

    /**
     * Kids Mode (#789): on shows PIN entry to turn it off; off asks for a first PIN, or with one set turns it on at
     * once. Its value is On or Off with its cap; Left and Right move the cap (#993).
     */
    KidsMode(RowKind.Action, R.string.guide_settings_kids_mode),

    /** Changes the PIN: the old one, a new one, the new one again. Shown only with a PIN set and Kids Mode off. */
    ChangePin(RowKind.Action, R.string.guide_settings_change_pin),

    /**
     * Hours of operation (#789, Pro): opens its screen over the sheet. Shown only with Pro ([isShown]); with Pro and
     * no PIN its screen asks for one first, and the Pro notice there is for a Pro that goes while the screen is open;
     * Kids Mode switches the row off, as it does the rows that could sidestep it.
     */
    Hours(RowKind.Link, R.string.guide_settings_hours),

    /** Shows Settings, Libraries (spec 26) over the guide. */
    Libraries(RowKind.Link, R.string.guide_settings_libraries),

    /** Updates the library now (spec 26, Part 5); the line under it says how that stands. */
    SyncNow(RowKind.Action, R.string.guide_settings_sync_now),

    /**
     * StationBreak Pro (#99): opens its screen over the sheet, which is the purchase screen in a build that sells Pro
     * and the Support StationBreak page in one that does not ([SettingsValues.offer]; the row's words follow it).
     * Shown only with something to open ([isShown]: a Support page, or a store that is available), and the only
     * mention of Pro on the sheet; Kids Mode switches it off like the rows that could spend
     * or sidestep.
     */
    Pro(RowKind.Link, R.string.guide_settings_pro),

    /** Asks whether to sign out of this TV, over the sheet (#336); the safe answer has the focus. */
    SignOut(RowKind.Action, R.string.guide_settings_sign_out),

    /** Off or On: whether a crash may be reported to the owner's endpoint (#96). Shown only in a build that has one. */
    CrashReports(RowKind.Choice, R.string.guide_settings_crash_reports),

    /**
     * Opens the QR screen for the public user guide (#1105), over the sheet; the last row, in the Help section. The
     * screen is a picture and a line of text: no browser, no link out. Kids Mode switches the row off (the guide
     * describes PIN recovery and settings, and a child should not be one scan from it).
     */
    UserGuide(RowKind.Link, R.string.guide_settings_guide),
    ;

    /**
     * Whether the sheet shows this row for [values]: all but crash reports, which need a build with an endpoint,
     * Change PIN, which needs a PIN to change and Kids Mode off, Hours of operation, which is there only with Pro, and
     * the Pro row, which needs a store to buy from, a Support page, or Pro owned.
     * With Pro the sheet is one row taller than the free one; where that is more than the screen holds, it scrolls, as
     * the sheet already does at a large font.
     */
    fun isShown(values: SettingsValues): Boolean =
        when (this) {
            CrashReports -> values.crashReportsAvailable
            ChangePin -> values.kidsPinSet && !values.kidsOn
            Hours -> values.pro
            Pro -> values.offer is ProOffer.Support || values.storeAvailable || values.pro
            else -> true
        }

    /**
     * Whether Kids Mode has switched this row off for [values]: the rows that could undo or sidestep it (Libraries,
     * Update library, Sign out) do nothing while it is on. The look, the fit and the volume stay the viewer's.
     */
    fun isInert(values: SettingsValues): Boolean = values.kidsOn && this in KIDS_LOCKED_ROWS

    /** The row's words for [values]: the Pro row says Support StationBreak in a build with no store to buy from. */
    @StringRes
    fun labelFor(values: SettingsValues): Int =
        if (this == Pro && values.offer is ProOffer.Support) R.string.guide_settings_support else label
}

private val KIDS_LOCKED_ROWS =
    setOf(
        SettingsRow.Hours,
        SettingsRow.Libraries,
        SettingsRow.SyncNow,
        SettingsRow.Pro,
        SettingsRow.SignOut,
        SettingsRow.UserGuide,
    )

/** The sheet's sections, top to bottom: each a teletype [flag] over its [rows], which run in [SettingsRow]'s order. */
internal enum class SettingsGroup(
    @param:StringRes val flag: Int,
    val rows: List<SettingsRow>,
) {
    Picture(R.string.guide_settings_group_picture, listOf(SettingsRow.Appearance, SettingsRow.ScreenFit)),
    Playback(R.string.guide_settings_group_playback, listOf(SettingsRow.ChannelChange, SettingsRow.VolumeLevelling)),
    Kids(
        R.string.guide_settings_group_kids,
        listOf(SettingsRow.KidsMode, SettingsRow.ChangePin, SettingsRow.Hours),
    ),
    Library(R.string.guide_settings_group_library, listOf(SettingsRow.Libraries, SettingsRow.SyncNow)),
    Account(R.string.guide_settings_group_account, listOf(SettingsRow.Pro, SettingsRow.SignOut)),
    Privacy(R.string.guide_settings_group_privacy, listOf(SettingsRow.CrashReports)),
    Help(R.string.guide_settings_group_help, listOf(SettingsRow.UserGuide)),
}

/** The values the choice rows show: the viewer's own, as their stores hold them. */
internal data class SettingsValues(
    val theme: GuideTheme = GuideTheme.Dark,
    val fit: ScreenFit = ScreenFit.Safe,
    val channelChange: ChannelChangeStyle = ChannelChangeStyle.Stan,
    val levelling: Boolean = true,
    val crashReports: Boolean = false,
    val crashReportsAvailable: Boolean = false,
    /** Kids Mode is on (#789). */
    val kidsOn: Boolean = false,
    /** A Kids Mode PIN is set. */
    val kidsPinSet: Boolean = false,
    /** How far up the channel numbers Kids Mode reaches (#993): shown on the Kids Mode row, Left and Right move it. */
    val kidsCap: KidsCap = KidsCap.DEFAULT,
    /** The stored lock has been read; until then [kidsOn] is true (closed) and only the row's words say unknown. */
    val kidsKnown: Boolean = true,
    /** Pro is unlocked (#789): without it the hours screen is a notice. */
    val pro: Boolean = false,
    /** The stored hours schedule, for the hours screen (what Pro would apply, whatever Pro says now). */
    val hours: HoursSchedule = HoursSchedule.EMPTY,
    /** What this build offers for Pro (#99): a purchase screen, or a Support link. Fixed for the build. */
    val offer: ProOffer = ProOffer.Store,
    /** The store can be bought from (#99): without it a Store offer has no Pro row at all. */
    val storeAvailable: Boolean = false,
)

/**
 * The Settings sheet while it is open: the [row] under the D-pad, and what it shows over the guide, the sheet staying
 * open under it on its row: Settings, Libraries ([librariesShown]), the sign-out question ([signOutShown]) or the Kids
 * Mode PIN pad ([pin]), the PIN's reset ([reset]) or the hours screen ([hours]), all drawn by the guide itself, which
 * keeps their keys.
 */
internal data class SettingsPanel(
    val row: SettingsRow = SettingsRow.Appearance,
    val librariesShown: Boolean = false,
    val signOutShown: Boolean = false,
    val pin: PinEntry? = null,
    val reset: PinReset? = null,
    val hours: HoursScreen? = null,
    /** The Pro screen (purchase, or Support) shows over the sheet (#99). */
    val proShown: Boolean = false,
    /** The user guide's QR screen shows over the sheet (#1105); the guide draws it and its keys are [guideKey]'s. */
    val guideShown: Boolean = false,
) {
    /** The hours screen or the user guide's QR screen, which the guide draws itself, has the keys. */
    val ownsKeys: Boolean get() = hours != null || guideShown

    /** A screen over the guide has the keys. */
    val covered: Boolean get() = librariesShown || signOutShown || proShown

    /** The Libraries screen has finished: back to the sheet, on Libraries. */
    fun librariesDone(): SettingsPanel = copy(librariesShown = false)

    /** The viewer stayed signed in: back to the sheet, on Sign out. */
    fun signOutDone(): SettingsPanel = copy(signOutShown = false)

    /** The Pro screen has finished (Back): back to the sheet, on its row. */
    fun proDone(): SettingsPanel = copy(proShown = false)
}

/**
 * What a key did to the sheet: the [panel] after it (null when it closed), the value it chose on a choice row, if any
 * (with the path that chose it, for the stores' logs), and whether it asks for a [syncNow].
 */
internal data class SettingsStep(
    val panel: SettingsPanel?,
    val theme: GuideTheme? = null,
    val syncNow: Boolean = false,
    /** Select on Kids Mode with a PIN set and the lock off: turn it on. */
    val kidsEnable: Boolean = false,
    /** Left or Right on Kids Mode while it is off: the cap to store, with no PIN (#993). While on, the PIN pad asks. */
    val kidsCap: KidsCap? = null,
    /** What the PIN pad has finished and asks the lock for. */
    val pinSubmit: PinSubmit? = null,
    /** The PIN's reset has opened, or starts again: a new request for an administrator's approval. */
    val resetStart: Boolean = false,
    /** The hours screen asks for this schedule to be saved (`HoursOfOperation.edit`). */
    val hoursSave: HoursSchedule? = null,
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
 * (the line under the row says how it goes), and on Sign out shows the sign-out question. OK on Kids Mode and Change
 * PIN opens the PIN pad (Kids Mode on with a PIN set and off turns it on at once); Left and Right on Kids Mode move
 * its cap, Kids, Family, Teen (no wrap), at once while it is off and through the PIN pad while it is on, and do
 * nothing until the lock has been read; with Kids Mode on, OK on Libraries,
 * Update library and Sign out does nothing. Back closes the sheet, as the guide's own Back does. While a screen shows
 * over it, every key is kept and does nothing: that screen has the keys; so do the PIN pad ([pinKey]) and the PIN's
 * reset ([resetKey]) and the hours screen ([hoursKey]).
 * A step that leaves a value as it was does not choose it again.
 */
internal fun settingsStep(
    panel: SettingsPanel,
    values: SettingsValues,
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): SettingsStep? {
    val pin = panel.pin
    val same = SettingsStep(panel)
    return when {
        pin != null -> {
            panel.pinKey(pin, key, type, repeatCount)
        }

        panel.reset != null -> {
            panel.resetKey(panel.reset, key, type, repeatCount)
        }

        panel.ownsKeys -> {
            panel.screenKey(values, key, type, repeatCount)
        }

        remoteKeyOf(key) == null -> {
            null
        }

        panel.covered -> {
            same
        }

        else -> {
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
}

// OK on the sheet's row, once per press.
private fun SettingsPanel.selected(values: SettingsValues): SettingsStep =
    when {
        // A row Kids Mode has switched off, or one that is not shown, does nothing.
        row.isInert(values) || !row.isShown(values) -> SettingsStep(this)

        row == SettingsRow.KidsMode -> kidsModeStep(values)

        row == SettingsRow.ChangePin -> SettingsStep(copy(pin = PinEntry.start(PinPurpose.Change)))

        row == SettingsRow.Hours -> SettingsStep(copy(hours = HoursScreen()))

        row == SettingsRow.Pro -> SettingsStep(copy(proShown = true))

        row == SettingsRow.UserGuide -> SettingsStep(copy(guideShown = true))

        else -> selectedRow(values)
    }

private fun SettingsPanel.selectedRow(values: SettingsValues): SettingsStep =
    when (row.kind) {
        RowKind.Choice -> changed(values, Change.Cycle)
        RowKind.Link -> SettingsStep(copy(librariesShown = true))
        RowKind.Action -> if (row == SettingsRow.SignOut) SettingsStep(copy(signOutShown = true)) else syncStep
    }

// OK on Kids Mode: on asks for the PIN to turn it off, off with a PIN turns it on at once, off without one asks for it.
private fun SettingsPanel.kidsModeStep(values: SettingsValues): SettingsStep =
    when {
        values.kidsOn -> SettingsStep(copy(pin = PinEntry.start(PinPurpose.TurnOff)))
        values.kidsPinSet -> SettingsStep(this, kidsEnable = true)
        else -> SettingsStep(copy(pin = PinEntry.start(PinPurpose.Set)))
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
