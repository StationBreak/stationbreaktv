# Kids episodes follow their series' rating

> Superseded in part: shipped as kid-hold-5 / grown-up-hold-4 on top of ADR-0026; see the ADR-0019 addendum 2026-11-13.

- **Date:** 2026-11-13
- **Size:** small change
- **Decided by:** Phil Critchfield

## Intent

Kids series such as Fraggle Rock and VeggieTales are rated TV-Y, but some of their episodes carry their own TV-PG or
unrated rating. Today the kid hold judges each episode on its own rating as well as its series', so those episodes fail
every kids ceiling. They also fail the grown-up hold's "made for kids and passes the kid table" test, so they fall into
the grown-up Odds & Ends. That rule was ADR-0019 decision 6, and the owner has reversed it. **If the series qualifies
for a kids channel, its episodes qualify.**

## Goals

- An episode of a known series that has a rating (present: not absent, as ADR-0019's "One definition of absent" defines
  it) is judged on the **series rating only**.
  The episode's own rating is ignored.
- Those episodes leave Odds & Ends, because the grown-up hold calls the same `passesKidCeiling`.

## Non-goals and anti-goals

- No change for movies, episodes with no series ID, or episodes whose series the snapshot lacks (that last case still
  fails closed), except for the one edge in ADR-0019's "One definition of absent" paragraph: an entry's own rating
  that is only a tab or another non-U+0020 space no longer counts as absent for the animated-family signal.
- **Anti-goal:** a grown-up series must not reach a kids channel through its studio or genre tags alone. So a known
  series with **no** rating keeps today's rule: each episode is judged on its own rating. (Example: The Jim Henson
  Company is on the kid-studio list and also made *Farscape*. If *Farscape* had no series rating, "the series qualifies"
  would otherwise put it on a kids channel.)
- No change to the genre or studio lists, the animated-family genre test or its rating ladder, or the ceiling choices.
  `isMadeForKids` is not edited, but it widens: the animated-family signal now takes the series rating (the decided
  precedence change), so a TV-14 episode of a TV-G Animation+Family series is made for kids, and an NR series' PG
  episodes count as made for kids (they still fail every ceiling).

## Approaches

1. **The series rating decides when present; otherwise the episode's own rating decides (picked).** It fixes Fraggle
   Rock and VeggieTales and keeps the Farscape guard.
2. The series qualifies on any signal, so all its episodes qualify. Rejected because of the Farscape case above.
3. Keep the rule and let a user override it per series. Rejected: the owner wants the default changed.

## Decided how

- `KidHold.passesKidCeiling` (`core/engine/.../KidHold.kt`), episode branch with a known series: if
  `series.officialRating` is present (not absent: null, or only U+0020 spaces; `EpisodeRating.isAbsent`), return
  `within(series.officialRating, ceiling)`. Otherwise return
  `within(entry.officialRating, ceiling)`.
- `AnimatedFamilySignal` reads the rating "as `passesKidCeiling` reads an episode". Make it use the same precedence, so
  the two can't drift.
- New version names: `kid-hold-5` and `grown-up-hold-4` (owner, 2026-11-13: ADR-0026 owns `kid-hold-4` /
  `grown-up-hold-3`; `kid-hold-5` is studio fold 2 plus this rule, and merges only after fold 2's code lands), since
  both holds are frozen on this rule.
- No backstop (owner, 2026-11-13): episodes rated above TV-PG in a kid-rated series still follow the series; the
  mis-rated-series risk is accepted (ADR-0019 addendum).
- Shared vector for ports: `docs/rules-format/vectors/kid-hold-5.json`. If
  the frozen starter manifest depends on it, bump `StarterCatalog.VERSION` too.
- Write an ADR-0019 addendum (decision 6 superseded, giving the reason and the Farscape guard) and an ADR-0020 note.
  Update the KDoc on `passesKidCeiling` and `isAnimatedFamily`.
- Vectors and tests that pin the old episode rule move on purpose. Add cases for: a TV-Y series with a TV-PG episode
  (passes), a TV-Y series with an unrated episode (passes), a series with no rating and a TV-PG episode (fails), and a
  TV-PG series with a TV-Y episode (fails).

## Left to the builder

Test layout, and whether `AnimatedFamilySignal` shares a helper with `KidHold` or calls it.

## Review

The change hits `crew:architecture` (an interface other modules depend on, plus frozen version names), so it is
mandatory at code review.
