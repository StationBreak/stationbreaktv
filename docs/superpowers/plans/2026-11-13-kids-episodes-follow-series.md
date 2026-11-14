# Kids Episodes Follow Their Series' Rating: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> Superseded in part: shipped as kid-hold-5 / grown-up-hold-4 on top of ADR-0026; see the ADR-0019 addendum 2026-11-13.

**Goal:** An episode of a known series that has a rating is judged on that series rating alone, so a TV-Y series's
TV-PG or unrated episodes reach kids channels and leave Odds & Ends.

**Architecture:** One precedence rule, "series rating when present, otherwise the episode's own", applied in
`KidHold.passesKidCeiling` and in `AnimatedFamilySignal.holds`. The grown-up hold follows automatically, because it
calls `passesKidCeiling`. Both hold version names bump, so `PersistedDays` (whose files are keyed by these names)
remakes held days once.

**Tech Stack:** Kotlin Multiplatform (`core:engine` commonMain/commonTest), Gradle.

**Spec:** `docs/superpowers/specs/2026-11-13-kids-episodes-follow-series-design.md`

## Global Constraints

- When the series is known and its rating is present, the series rating decides and the episode's own rating is
  ignored.
- When the series is known and has no rating, the episode is judged on its own rating, as today (the Farscape guard).
- Unchanged: movies, episodes with no series ID, and episodes whose series the snapshot lacks (still fail closed).
- One shared helper decides which rating counts: `EpisodeRating.decisive(entry: RuleItem, series:
  RuleSeries): String?` in `internal object EpisodeRating` (with `isAbsent`; moved out of `KidHold` in the fix round
  after the fold-2 rebase), which returns `series.officialRating` when it is present (not null,
  and not only U+0020 spaces; see ADR-0019), otherwise `entry.officialRating`. Both `passesKidCeiling` and
  `AnimatedFamilySignal.holds` call it, so a series rating of spaces counts as absent in both.
- No `StarterCatalog.VERSION` bump: nothing in the catalog refers to the hold names (only `PersistedDays.HOLDS` does).
- The `passesKidCeiling` KDoc must say it deliberately departs from `RatingEligibility` (rules-format rule 4) for a
  known rated series. That rule keeps the per-episode check for open and grown-up channels, so nobody should "fix" the
  two back into step.
- Held until ADR-0026's fold 2 lands (`kid-hold-5` = fold 2 plus this rule); see `docs/adr/0019-kid-hold.md`.
- `KidHold.VERSION_NAME` becomes `kid-hold-5` and `GrownUpHold.VERSION_NAME` becomes `grown-up-hold-4`.
- No change to `isMadeForKids`, `KIDS_GENRES`, `KID_STUDIOS`, `CHILDRENS_RATINGS` or `CEILING_CHOICES`.

## Review Focus

1. A series rated TV-PG with a TV-Y episode must still **fail**, because the series decides in both directions.
2. A series rated `NR` or `Not Rated` is present and unrated, so it fails. It must not be treated as "no rating" and
   fall back to the episode.
3. A series with a blank rating `""` and a TV-PG episode must still fail (the guard).
4. Grown-up side: a kids-genre TV-Y series's TV-PG episode is now **dropped** from grown-up pools, and its unrated
   episode too.
5. The animated-family signal: an Animation+Family series rated TV-Y with a PG episode now holds.

Each is pinned by a test in Task 1.

---

### Task 1: Series rating decides for episodes of a known, rated series

**Files:**
- Modify: `core/engine/src/commonMain/kotlin/tv/stationbreak/core/engine/KidHold.kt` (`passesKidCeiling` episode branch
  and its KDoc, `isAnimatedFamily` KDoc, `VERSION_NAME`)
- Modify: `core/engine/src/commonMain/kotlin/tv/stationbreak/core/engine/AnimatedFamilySignal.kt` (`holds`, KDoc)
- Modify: `core/engine/src/commonMain/kotlin/tv/stationbreak/core/engine/GrownUpHold.kt` (`VERSION_NAME`)
- Test: `core/engine/src/commonTest/kotlin/tv/stationbreak/core/engine/KidHoldTest.kt`
- Test (literal version names): `KidHoldTest.kt` lines ~287–429,
  `core/engine/src/commonTest/kotlin/tv/stationbreak/core/engine/PoolEvaluatorGrownUpHoldTest.kt`,
  `core/engine/src/commonTest/kotlin/tv/stationbreak/core/engine/lineup/LineupPlannerMigrationTest.kt:305`, and the
  `core/domain` tests that assert the names (`LineupDaysKidHoldTest`, `LineupDaysGrownUpHoldTest`, `KidAuditTest`)

**Interfaces:**
- Produces: no new signatures. `KidHold.passesKidCeiling(entry, series, maxRating): Boolean` and
  `AnimatedFamilySignal.holds(entry, series): Boolean` keep their signatures, with new behaviour.

- [ ] **Step 1: Write the failing tests** in `KidHoldTest`, using the existing `episodeOf`, `show`, `film` helpers:

```kotlin
@Test
fun aKnownRatedSeriesDecidesForItsEpisodes() {
    assertTrue(KidHold.passesKidCeiling(episodeOf("TV-PG"), show("TV-Y"), "PG"))
    assertTrue(KidHold.passesKidCeiling(episodeOf("NR"), show("TV-Y"), "PG"))
    assertTrue(KidHold.passesKidCeiling(episodeOf("TV-14"), show("TV-Y7"), "TV-Y7"))
    assertFalse(KidHold.passesKidCeiling(episodeOf("TV-Y"), show("TV-PG"), "PG"))
    assertFalse(KidHold.passesKidCeiling(episodeOf("TV-Y"), show("NR"), "PG"))
    assertFalse(KidHold.passesKidCeiling(episodeOf("TV-Y"), show("Not Rated"), "PG"))
    assertFalse(KidHold.passesKidCeiling(episodeOf("G"), show("TV-Y7"), "G"))
}

@Test
fun aKnownSeriesWithNoRatingFallsBackToTheEpisode() {
    assertFalse(KidHold.passesKidCeiling(episodeOf("TV-PG"), show(""), "PG"))
    assertFalse(KidHold.passesKidCeiling(episodeOf("TV-PG"), show(null), "PG"))
    assertTrue(KidHold.passesKidCeiling(episodeOf("TV-Y"), show(null), "PG"))
}

@Test
fun theGrownUpHoldDropsOffRatedEpisodesOfAKidsSeries() {
    val kids = show("TV-Y", genres = listOf("Children"))
    assertTrue(GrownUpHold.dropsFromGrownUp(episodeOf("TV-PG"), kids))
    assertTrue(GrownUpHold.dropsFromGrownUp(episodeOf("Unrated"), kids))
    assertFalse(GrownUpHold.dropsFromGrownUp(episodeOf("TV-PG"), show(null, genres = listOf("Children"))))
}

@Test
fun theAnimatedFamilySignalTakesTheSeriesRatingWhenPresent() {
    val af = listOf("Animation", "Family")
    assertTrue(KidHold.isAnimatedFamily(episodeOf("PG", genres = af), show("TV-Y")))
    assertFalse(KidHold.isAnimatedFamily(episodeOf("TV-Y", genres = af), show("PG")))
    assertFalse(KidHold.isAnimatedFamily(episodeOf("PG", genres = af), show(" ")))
}
```

In the existing `aKnownSeriesWithARatingMustPassWhateverTheEpisodeSays`, every assertion still holds under the new
rule. Leave it as it is.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :core:engine:jvmTest --tests 'tv.stationbreak.core.engine.KidHoldTest'`
Expected: the four new tests fail, and the existing ones pass.

- [ ] **Step 3: Implement the rule**

`passesKidCeiling`, episode with a known series: `val rating = if (series.officialRating.isNullOrEmpty())
entry.officialRating else series.officialRating; within(rating, ceiling)`.
`AnimatedFamilySignal.holds`: `val decisive = if (seriesRating.isNullOrBlank()) own else seriesRating; return
ratingPasses(decisive)`. Rewrite both KDocs to state the precedence and the Farscape guard in one sentence each. Set
`KidHold.VERSION_NAME = "kid-hold-5"` and `GrownUpHold.VERSION_NAME = "grown-up-hold-4"`.

- [ ] **Step 4: Update the literal version names in the tests listed above** from `kid-hold-3` / `grown-up-hold-2` to
  `kid-hold-5` / `grown-up-hold-4`. Leave `PersistedDaysTest`'s `"kid-hold-0", "grown-up-hold-2"` fixture names alone:
  they are historic keys and not the current version. Do not regenerate `days-v5.bin`.

- [ ] **Step 5: Run the engine and domain tests and confirm they pass**

Run: `./gradlew :core:engine:jvmTest :core:domain:testDebugUnitTest`
Expected: PASS. If a pool or lineup vector fails, it is because a vector pins an off-rated episode of a rated kids
series. Update that vector's expected output and name the vector in the commit message. Don't change any other vector.

- [ ] **Step 6: Commit**

```bash
git add core/engine core/domain/src/test
git commit -m "feat(holds): episodes of a rated kids series follow the series rating (kid-hold-5)"
```

### Task 2: Record the decision

**Files:**
- Modify: `docs/adr/0019-kid-hold.md` (new addendum `2026-11-13: kid-hold-5`)
- Modify: `docs/adr/0020-grown-up-hold.md` (a dated note: `grown-up-hold-4` follows `kid-hold-5`)

- [ ] **Step 1: Write the ADR-0019 addendum.** Cover four things:
  - Decision 6's per-episode check is superseded for episodes of a known series that has a rating, which now follow the
    series rating. Owner decision 2026-11-13, with the Fraggle Rock and VeggieTales examples.
  - A series with no rating keeps the per-episode check. Give the reason, with *Farscape* and The Jim Henson Company as
    the example.
  - The rollout: `PersistedDays` remakes held days once, at once, as with `kid-hold-3`.
  - The consequence: off-rated episodes of kids series leave Odds & Ends.

  In the 0019 consequences, mark the line "Kids' shows the hold refuses (unrated, TV-PG) mostly reach…" as amended.
- [ ] **Step 2: Add the ADR-0020 note**, linking to the 0019 addendum.
- [ ] **Step 3: Commit**

```bash
git add docs/adr/0019-kid-hold.md docs/adr/0020-grown-up-hold.md
git commit -m "docs(adr): kid-hold-5, episodes follow a rated series (ADR-0019, ADR-0020)"
```

### Review gate

At code review, `crew:architecture` is mandatory: a frozen version name and an interface `core:domain` depends on. Its
reviewer gets the diff and the spec only.
