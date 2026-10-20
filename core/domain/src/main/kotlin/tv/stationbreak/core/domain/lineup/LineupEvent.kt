// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

/**
 * What a lineup refresh reports beside its result, for diagnostics. A closed set: no ID, name or file content travels
 * with it.
 */
enum class LineupEvent {
    /** The stored lineup could not be read; it was set aside and a new one was chosen and saved. */
    SetAsideUnreadable,

    /** The stored lineup could not be read and could not be set aside either, so it was left as it is (not saved). */
    UnreadableLeftAlone,

    /** The stored lineup is from a newer app. It is left as it is; this session plans its own and saves nothing. */
    NewerVersionLeftAlone,

    /** The stored lineup exists but could not be read just now. Nothing is saved over it this time. */
    ReadFailedLeftAlone,

    /** The planned lineup could not be saved. It is kept in memory and saved by a later refresh. */
    SaveFailed,

    /** The account's server or user ID is not an ID at all, so it has no lineup. */
    NonCanonicalAccount,

    /** A refresh stopped part-way (for example the planner refused its input). Nothing is lost; the next retries. */
    RefreshFailed,
}
