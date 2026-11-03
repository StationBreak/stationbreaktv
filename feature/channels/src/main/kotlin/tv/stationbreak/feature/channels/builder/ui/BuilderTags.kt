// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import tv.stationbreak.feature.channels.builder.BuilderStep
import tv.stationbreak.feature.channels.builder.NameRowKind
import tv.stationbreak.feature.channels.builder.NarrowRowKind
import tv.stationbreak.feature.channels.builder.PlayRowKind
import tv.stationbreak.feature.channels.builder.SheetRow
import tv.stationbreak.feature.channels.builder.SourceKindTab

/** Test tags for the channel builder. */
internal object BuilderTags {
    const val SCREEN = "builder:screen"
    const val TITLE = "builder:title"

    /** The sheet's sub-line, in place of the step strip. */
    const val EDIT = "builder:edit"

    /** The line that says why something did not happen. */
    const val MESSAGE = "builder:message"

    /** The line that says why the draft cannot be saved yet. */
    const val PROBLEM = "builder:problem"
    const val LIST = "builder:list"
    const val SEARCH = "builder:search"
    const val SEARCH_INPUT = "builder:search:input"
    const val NARROW = "builder:narrow"
    const val NEXT = "builder:next"
    const val NOTE = "builder:note"

    /** The count of titles that fit, under the list. */
    const val BAR = "builder:bar"

    /** The line of what the channel would air next. */
    const val PREVIEW = "builder:preview"
    const val SKIPPED = "builder:skipped"
    const val NAME_INPUT = "builder:name:input"
    const val NUMBER_HELD = "builder:number:held"
    const val SUMMARY = "builder:summary"
    const val CONFIRM = "builder:confirm"
    const val CONFIRM_TEXT = "builder:confirm:text"
    const val CONFIRM_YES = "builder:confirm:yes"
    const val CONFIRM_NO = "builder:confirm:no"

    fun step(step: BuilderStep) = "builder:step:${step.name}"

    fun tab(tab: SourceKindTab) = "builder:tab:${tab.name}"

    fun entry(key: String) = "builder:entry:$key"

    fun narrowRow(row: NarrowRowKind) = "builder:narrow:${row.name}"

    fun playRow(row: PlayRowKind) = "builder:play:${row.name}"

    /** The hint under the Order row. */
    const val ORDER_HINT = "builder:play:Order:hint"

    fun kept(index: Int) = "builder:kept:$index"

    fun sheetRow(row: SheetRow) = "builder:sheet:${row.name}"

    /** The \"Kept from\" line over the Kept row. */
    const val SHEET_KEPT_FROM = "builder:sheet:keptFrom"

    fun nameRow(row: NameRowKind) = "builder:name:${row.name}"
}
