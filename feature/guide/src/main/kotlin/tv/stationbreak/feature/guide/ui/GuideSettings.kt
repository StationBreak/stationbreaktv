// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.feature.guide.GuideDirection
import tv.stationbreak.feature.guide.R

/** The rows of the guide's Settings panel, top to bottom. */
internal enum class SettingsRow {
    /** Dark and Light: Left and Right pick one. */
    Appearance,

    /** Select shows Settings, Libraries (spec 26) over the guide. */
    Libraries,
}

/**
 * The Settings panel while it is open: the [row] under the D-pad, and whether Settings, Libraries is [librariesShown]
 * over the guide (the panel stays open under it, on its row).
 */
internal data class SettingsPanel(
    val row: SettingsRow = SettingsRow.Appearance,
    val librariesShown: Boolean = false,
) {
    /** The Libraries screen has finished: back to the panel, on Libraries. */
    fun librariesDone(): SettingsPanel = copy(librariesShown = false)
}

/** What a key did to the panel: the [panel] after it (null when it closed), and a [theme] it picked, if any. */
internal data class SettingsStep(
    val panel: SettingsPanel?,
    val theme: GuideTheme? = null,
)

/**
 * A key while the Settings panel is open, or null when the key is not the panel's (the media keys). The D-pad,
 * Select and Back are all kept, so the grid never moves under the panel.
 *
 * Up and Down move between the rows (on every press, and stop at the ends). On Appearance, Left and Right pick Dark
 * and Light. Select, on its first press: on Appearance it closes the panel, on Libraries it shows the Libraries screen.
 * Back closes the panel at its release, as the guide's own Back does. While the Libraries screen shows, every key is
 * kept and does nothing: that screen has the keys, as channel management has them over the guide.
 */
internal fun settingsStep(
    panel: SettingsPanel,
    key: Key,
    type: KeyEventType,
    repeatCount: Int,
): SettingsStep? {
    val direction = directionOf(key)
    val select = key in SELECT_KEYS
    if (key != Key.Back && direction == null && !select) return null
    val same = SettingsStep(panel)
    return when {
        panel.librariesShown -> same
        key == Key.Back -> if (type == KeyEventType.KeyUp) SettingsStep(null) else same
        type != KeyEventType.KeyDown -> same
        direction != null -> SettingsStep(panel.moved(direction), panel.themeFor(direction))
        repeatCount > 0 -> same
        panel.row == SettingsRow.Appearance -> SettingsStep(null)
        else -> SettingsStep(panel.copy(librariesShown = true))
    }
}

// Up and Down move between the rows and stop at the ends; Left and Right leave the row where it is.
private fun SettingsPanel.moved(direction: GuideDirection): SettingsPanel {
    val rows = SettingsRow.entries
    val to =
        when (direction) {
            GuideDirection.Up -> rows.getOrElse(row.ordinal - 1) { row }
            GuideDirection.Down -> rows.getOrElse(row.ordinal + 1) { row }
            GuideDirection.Left, GuideDirection.Right -> row
        }
    return copy(row = to)
}

// On Appearance, Left picks Dark and Right picks Light; nothing else picks a theme.
private fun SettingsPanel.themeFor(direction: GuideDirection): GuideTheme? =
    when {
        row != SettingsRow.Appearance -> null
        direction == GuideDirection.Left -> GuideTheme.Dark
        direction == GuideDirection.Right -> GuideTheme.Light
        else -> null
    }

/**
 * The Settings panel, in the details band while the Settings entry is open: Appearance (Dark and Light) and Libraries,
 * drawn as the menu row's text tabs are ([tabLook]). [row] is the one under the D-pad and [choice] the theme chosen.
 * The focused row's label is in the primary colour; on it the chosen theme has the 2 dp accent underline (the D-pad
 * focus), and off it the 1 dp quiet one. Libraries has the accent underline while focused. Nothing scales or draws a
 * ring.
 */
@Composable
internal fun GuideSettingsPanel(
    choice: GuideTheme,
    row: SettingsRow,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGuideColors.current
    val onAppearance = row == SettingsRow.Appearance
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.guide_settings_button),
            style = guideText(28.sp, FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.guide_settings_appearance),
            style = guideText(18.sp, FontWeight.Normal),
            color = if (onAppearance) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val dark = choice == GuideTheme.Dark
            val light = choice == GuideTheme.Light
            PanelTab(R.string.guide_theme_dark, GuideTags.THEME_DARK, tabLook(dark && onAppearance, dark, colors), dark)
            PanelTab(
                R.string.guide_theme_light,
                GuideTags.THEME_LIGHT,
                tabLook(light && onAppearance, light, colors),
                light,
            )
        }
        val onLibraries = row == SettingsRow.Libraries
        PanelTab(
            R.string.guide_settings_libraries,
            GuideTags.SETTINGS_LIBRARIES,
            tabLook(focused = onLibraries, selected = false, colors = colors),
            onLibraries,
        )
    }
}

// One text tab of the panel. [selected] is what a screen reader is told: the chosen theme, or the focused row.
@Composable
private fun PanelTab(
    label: Int,
    tag: String,
    look: TabLook,
    selected: Boolean,
) {
    Text(
        text = stringResource(label),
        modifier =
            Modifier
                .underline(look.underline, look.thickness)
                .testTag(tag)
                .semantics {
                    role = Role.Button
                    this.selected = selected
                },
        style = guideText(13.sp, FontWeight.Medium),
        color = look.text,
        maxLines = 1,
    )
}
