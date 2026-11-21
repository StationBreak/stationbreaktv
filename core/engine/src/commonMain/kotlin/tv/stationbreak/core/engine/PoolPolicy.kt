// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

/**
 * What the app narrows a pool by, after the rules' own exclusions (ADR-0019, ADR-0020). It is the app's narrowing, not
 * rules-format behaviour: rules format v1 knows nothing of it.
 */
sealed interface PoolPolicy {
    /** No narrowing: exactly the evaluation docs/rules-format.md describes (the reference the pool vectors hold). */
    data object Open : PoolPolicy

    /**
     * A custom channel outside the Kids band: the rules-format evaluation, except that under a ceiling `Approved` is
     * unrated and fails it (`kid-hold-6`, ADR-0028).
     */
    data object Custom : PoolPolicy

    /** A kid channel: only the kids tier ([KidHold.admits]) under the kid ceiling. */
    data object KidsOnly : PoolPolicy

    /**
     * A grown-up channel: nothing a kid channel could take ([GrownUpHold.dropsFromGrownUp]: the kids tier); the ladder
     * applies, with `Approved` unrated as under [Custom]. Made-for-kids items no kid channel can take stay eligible.
     */
    data object GrownUp : PoolPolicy
}
