# One default scorer, read by both tools — design (#244)

Issue #244. The rating deck holds a second copy of the recommender's default scorer. This is the
code catching up to [ADR 48](../../adr/0048-a-high-rating-counts-as-something-you-have.md) and
[ADR 50](../../adr/0050-suppress-a-candidate-you-have-rejected.md)'s "the two tools cannot apply
different answers"; [ADR 45](../../adr/0045-recommend-by-normalised-lift-with-routes.md)'s decision
stands and no default moves. **No ADR, and no amendment: nothing decided changes.**

## What the code actually says

Checked against the tree at `da8efa9`; the issue's account is accurate, and these are the exact
sites.

- `RateRun.buildDeck` passes the literal `Scorer.LIFT` to `CandidateSweep.over` (one call, one
  literal, and it is the only use of `Scorer` in that file).
- `RecommendCli.parse` initialises its `scorer` local to the literal `Scorer.LIFT`.
- `RecommendCli.USAGE` spells the same fact a third time, as the English word inside
  `">, default lift]"`.
- `RecommendCliTest.theTwoPathsAreAllItNeeds` pins the parse default with `isEqualTo(Scorer.LIFT)` —
  a fourth copy, and the one that would have made a moved default look deliberate.
- Nothing pairs any of them. `Scorer.LIFT` also appears in several recommender tests as a fixture
  choice; those are not copies of the *default* and are out of scope.

There is no fifth copy. `evaluate` sweeps `Scorer.values()` (`Setting.GRID`) rather than defaulting,
so the harness has no stake here.

The precedent to follow is one file away. `RateCli`'s `minDegree` defaults to
`Recommendations.MIN_CANDIDATE_DEGREE`, and its javadoc gives the rule this issue applies: **by
reference, never by a second copy of the number (issue #119), so re-measuring the constant moves
both tools' defaults at once.**

## The shape

1. **One constant, in the domain.** `Recommendations.DEFAULT_SCORER = Scorer.LIFT`, beside
   `MIN_CANDIDATE_DEGREE`, which is where the floor's argument already lives and which both `rate`
   and `recommend` already depend on. Its javadoc says what ADR 45 measured (by citation, never by
   restating the figures), and says why it is shared: the deck deals candidates from the
   recommender's own sweep, so a default that moves in one tool and not the other makes the deck
   deal one tool's answer while the report gives another's.
2. **Both tools read it.** `RateRun`'s sweep call and `RecommendCli.parse`'s default become
   `Recommendations.DEFAULT_SCORER`. `RateRun` then imports `Scorer` for nothing and the import
   goes.
3. **The usage message derives the word.** `Scorer.spelling()` exists and is already what
   `Scorer.names()` is built from, so `USAGE` reads
   `">, default " + Recommendations.DEFAULT_SCORER.spelling() + "]"` and the English word cannot
   drift from the enum again.
4. **The pins move onto the constant**, in `RecommendCliTest` and in the new deck guard.

## How this is tested, and where the red comes from

**The two copies agree today, so there is no failing test to be had by writing one.** A guard over a
duplication is red only when the duplicates differ. Two routes were available:

- **Plant the divergence** — write the guard, change one literal, watch the assertion fire, remove
  the plant.
- **Structure the guard so the shared constant is what makes it pass** — write the guard against
  `Recommendations.DEFAULT_SCORER` before that constant exists.

**The plant is chosen, and the second route is rejected because it cannot produce a red at all:** a
test naming a constant that does not exist fails to *compile*, and a compile error is not a red. It
would also make the first observation of the guard happen after the fix, which is the ordering this
project treats as test-after. The plant, by contrast, is the positive control the standing rules
demand anyway — it is the only way to learn that the check can fail — so it does double duty: it is
the red, and it is the proof the guard bites.

**The guard is behavioural, not a constant compared with itself.** It builds a graph on which the
scorers disagree — one candidate reached by three of the owner's entities sitting at the degree
floor, one reached by six sitting at twelve times that degree — and asserts that the deck deals the
candidate the recommender's own sweep ranks first under the shared default, and not the one counting
would rank first. Both sides of the assertion are computed by running the shipped
`CandidateSweep`/`Recommendations.rank` pair, so the guard reads what the deck's sweep *did* rather
than what a field says it would do. A seam on `RateRun` — a package-private accessor naming the
scorer, or a scorer parameter threaded in from `RateCli` the way the floor is — was considered and
rejected: an accessor can be left behind by an edit that hardcodes the `.over(...)` argument, which
is the exact defect being fenced, and a parameter with one caller and no flag behind it is
speculative structure.

**The guard validates its own fixture.** It first asserts that counting and the default rank
*different* candidates on this graph. Without that line, a later fixture change that made the two
scorers agree would leave the guard vacuously green, and it would report clean forever.

## One place the design departs from the issue's wording

The issue asks for "a test asserting the deck's sweep scorer equals `RecommendCli.parse`'s default".
That assertion cannot be written in one test: `RecommendCli.parse` is package-private, `rate` may
depend on `recommend` but never the reverse, and widening `parse` to public for a test's sake would
trade a duplication for a hole in the surface. The property is therefore held by two tests against
one constant — the deck guard in `RateRunTest`, the parse pin in `RecommendCliTest` — which is
precisely how `MIN_CANDIDATE_DEGREE` is already held, and it fails in the same way if either side
reintroduces a literal.

## What is not done

- **No default changes.** `LIFT` in, `LIFT` out. Any move belongs to a reading, not to this issue.
- **No ADR and no amendment.** Nothing decided changes; the issue says so.
- **No `--scorer` flag on `rate`.** The deck's whole argument is that it deals what `recommend`
  would; a dial that let it deal something else would undo this issue on the day it shipped.
- **The developer guide gets one clause**, in the sentence at "`RateCli`'s `--min-degree` defaults to
  the same `Recommendations.MIN_CANDIDATE_DEGREE`…", which is the one place the guide explains why
  the deck's candidates and `./gradlew recommend`'s agree. Nothing else in the guide names the deck's
  scorer.
