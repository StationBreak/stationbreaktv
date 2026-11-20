// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.stationbreak.core.ui.KeyHintPair
import tv.stationbreak.core.ui.KeyHintRow
import tv.stationbreak.core.ui.LeftSheet
import tv.stationbreak.core.ui.LocalPanelColors
import tv.stationbreak.core.ui.SheetMeasures
import tv.stationbreak.core.ui.guideText
import tv.stationbreak.core.ui.panelFocus
import tv.stationbreak.feature.guide.GuideMenuEntry
import tv.stationbreak.feature.guide.GuideSnapshot
import tv.stationbreak.feature.guide.R
import tv.stationbreak.feature.guide.aboutRow
import tv.stationbreak.core.ui.R as UiR

/**
 * The menu sheet's measures (#756): a [LeftSheet] 172 dp wide from the safe edge (220 dp of the screen at Safe), full
 * height, with the shared sheet rows (36 dp tall) in the guide's 13 sp, and 8 dp from the text to its line.
 */
private val MenuSheetWidth = 172.dp
private val SheetEndPadding = 8.dp
private const val MENU_ROW_SP = 13

/**
 * The menu sheet over the guide, when it is open: not over the Settings sheet it opened ([settingsClosed] false),
 * which stands in its place. Whether it is open and what Back does in it are states of their own, so a move on the grid
 * recomposes nothing here; only the sheet opening or closing does.
 */
@Composable
internal fun GuideMenuSheetHost(
    shown: State<GuideSnapshot>,
    settingsClosed: Boolean,
) {
    val open by remember(shown) { derivedStateOf { shown.value.menu != null } }
    val backLeaves by remember(shown) { derivedStateOf { shown.value.menuByBack || shown.value.noLibraries } }
    val menuOf: () -> GuideMenuEntry? = remember(shown) { { shown.value.menu } }
    val aboutOf: () -> String? = remember(shown) { { shown.value.aboutRow()?.name } }
    if (open && settingsClosed) GuideMenuSheet(menuOf, backLeaves, aboutOf)
}

/**
 * The menu sheet (#756): Channels, Settings and Exit as rows of a [LeftSheet], over the guide dimmed as the channel
 * properties sheet dims its list, with the same surface and the same drawn focus. The guide's focus is its own
 * (model-driven), so the rows take no Compose focus. [menuOf] says which row the guide's focus is on, and [backLeaves]
 * whether Back leaves live TV from the sheet or closes it; the sheet is only composed while the row is not null. Each
 * row is told to a screen reader as selected only while the focus is on it. The hint row at the bottom has key caps.
 */
@Composable
internal fun GuideMenuSheet(
    menuOf: () -> GuideMenuEntry?,
    backLeaves: Boolean,
    aboutOf: () -> String? = { null },
    modifier: Modifier = Modifier,
) {
    val about by remember(aboutOf) { derivedStateOf(aboutOf) }
    val back = stringResource(UiR.string.hint_key_back)
    val what = stringResource(if (backLeaves) R.string.guide_hint_exit else R.string.guide_hint_close_menu)
    LeftSheet(
        MenuSheetWidth,
        modifier.testTag(GuideTags.MENU_SCRIM),
        sheetModifier = Modifier.testTag(GuideTags.MENU_SHEET),
        innerPadding = SheetEndPadding,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetMeasures.RowGap)) {
            // The focused channel's own properties, first: there with a grid only.
            about?.let { name ->
                MenuRow(stringResource(R.string.guide_menu_about, name), GuideTags.ABOUT_BUTTON) {
                    menuOf() == GuideMenuEntry.About
                }
            }
            MenuRow(stringResource(R.string.guide_channels_button), GuideTags.CHANNELS_BUTTON) {
                menuOf() == GuideMenuEntry.Channels
            }
            MenuRow(stringResource(R.string.guide_settings_button), GuideTags.SETTINGS_BUTTON) {
                menuOf() == GuideMenuEntry.Settings
            }
            MenuRow(stringResource(R.string.guide_exit_button), GuideTags.EXIT_BUTTON) {
                menuOf() == GuideMenuEntry.Exit
            }
        }
        Spacer(Modifier.weight(1f))
        KeyHintRow(
            listOf(KeyHintPair(back, what)),
            Modifier.padding(start = SheetMeasures.RowTextPadding).testTag(GuideTags.MENU_HINT),
        )
    }
}

@Composable
private fun MenuRow(
    text: String,
    tag: String,
    focusedOf: () -> Boolean,
) {
    val focused by remember(focusedOf) { derivedStateOf(focusedOf) }
    val colors = LocalPanelColors.current
    Text(
        text = text,
        modifier =
            Modifier
                .testTag(tag)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    selected = focused
                }.fillMaxWidth()
                .height(SheetMeasures.RowMinHeight)
                .panelFocus(focused)
                .padding(horizontal = SheetMeasures.RowTextPadding)
                .wrapContentHeight(Alignment.CenterVertically),
        style = guideText(MENU_ROW_SP.sp, FontWeight.Medium),
        color = colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
