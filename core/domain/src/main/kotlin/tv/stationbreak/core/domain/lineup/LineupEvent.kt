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

    /** The planned lineup did not decode back to a lineup of this account, so it was not saved. Kept in memory. */
    SaveRejected,

    /** The account's server or user ID is not an ID at all, so it has no lineup. */
    NonCanonicalAccount,

    /** A refresh stopped part-way (for example the planner refused its input). Nothing is lost; the next retries. */
    RefreshFailed,

    /** A reset found no library to choose from (the choice or the library could not be read). Nothing was changed. */
    ResetLibrariesUnavailable,

    /** An edit would have built a lineup that breaks the lineup's rules. Nothing was changed. */
    EditInvalid,

    /** An edit failed for a reason other than the lineup's rules (a store that threw, say). Nothing was changed. */
    EditFailed,

    /** Whether the lineup may be changed could not be told (a store that threw, say); it is shown read-only. */
    AccessFailed,

    /** A reset failed for a reason other than the library (the plan, say). Nothing was changed. */
    ResetFailed,

    /**
     * The copy of an older lineup file, kept before the first save of the newer version, could not be written. The
     * lineup is saved all the same; only a downgrade would have wanted the copy.
     */
    BackupFailed,

    /** A channel build (create, save, reshuffle, delete) failed, not for the lineup's rules. Nothing was changed. */
    BuildFailed,

    /**
     * A leftovers channel's day was not its curated starters' day (never with the shipped timings): it claimed from
     * theirs, and the day was built all the same.
     */
    LeftoversClaimedFromAnotherDay,

    /**
     * The stored lineup's repair record (spec 26) could not be read. The lineup was loaded without it, and the days
     * are built as they would be with no repair; the next repair records afresh.
     */
    RepairsUnreadable,

    /**
     * A selection change's save did not commit, and its repair (spec 26) could not be taken back out of the record.
     * It is held back (its seq is above the saved one), so nothing on air changes; the next lost-block check drops it.
     */
    RepairUndoFailed,
}
