# Two stale spots in the developer guide: the supervised-run bullet and the hop digits — design

Issue #341. Written 2026-09-18 against the code on `main` after #339. Docs only, one file:
`docs/developer-guide.md`. **No behaviour changes, and no production code changes.**

## What is wrong

Two sentences in the guide were noticed during the reviews of #315 and #338 and left for a docs
issue. One is false; the other restates two constants as digits.

### Spot 1: the supervised first run "has never been run"

The last bullet under `### What to file from what you saw`, in the chapter `## A supervised first
run`, says the chapter was written against the code and checked against the parsers, and that it
has never been run. It was run: issue #249 was the owner's supervised first run of `ownClaim` and
`retractEntity` against the real database, and issue #259 carried what that run found back into the
chapter. The bullet was true the day the chapter was written and is not true now.

### Spot 2: the `find_paths` paragraph carries two digits

In the chapter `### Three card shapes, because they answer different questions`, the sentence
beginning "The route *set* differs too" says what `Recommendations.MAX_HOPS` is and what
`find_paths` defaults to, as two digits. Each digit mirrors a constant — `Recommendations.MAX_HOPS`
in `domain`, and `DEFAULT_MAX_HOPS` in `mcp.GraphTools`, the bound `find_paths` applies when a
caller omits `maxHops` — so the sentence goes stale silently the day either constant moves.
`Recommendations.MAX_HOPS` already documents why it lives where it does (issue #311); the guide has
no reason to hold a copy of its value.

## The sweep, derived before editing

The issue asked that the set be derived rather than assumed. Run on 2026-09-18 over
`docs/developer-guide.md`, `README.md` and every file under `docs/`:

- `MAX_HOPS` followed within a few words by a digit: the one sentence in spot 2.
- `defaults to <digit>`: the same sentence, its second half.
- `never been run` and `first run is what`: the one bullet in spot 1.
- Hop counts spelled out (`two hops`, `four hops`, `within two`, `within four`): one hit, in the
  recommender chapter's "The two hops of one route can …", which describes a route's shape and not
  the constant's value, and is not a hit.

So the set is exactly the two spots. The plan's grep steps repeat the sweep so a reviewer can see
it fail on a planted digit and pass after the edit.

## What changes

### Spot 1

Replace the bullet with one that says: the chapter was written against the code, run once by the
owner under issue #249, and corrected under issue #259 from what that run found; a later run that
disagrees with it is a finding to file, not a reason to distrust the chapter. Cite the two issue
numbers and nothing else — no date, no count of what the run touched, no restatement of what #259
changed.

### Spot 2

Rewrite the sentence so it names both constants and carries no digit: the deck walks to
`Recommendations.MAX_HOPS`, where `find_paths` falls back to `GraphTools.DEFAULT_MAX_HOPS` when a
caller omits `maxHops`, and the two are set independently; `bestFor` keeps only the top-ranked route
per reaching entity, as before. The surrounding paragraph — the two shared steps, the third that is
not shared, the note-field sentence — is untouched.

## What does not change

- No code, no test, no constant. `GraphTools.DEFAULT_MAX_HOPS` stays private; the guide may name it
  as it names other members.
- No other sentence in either chapter. The supervised-run chapter's examples are parsed by
  `DeveloperGuideSupervisedRunExamplesTest` and the bullet carries no example, so that test
  neither reds nor needs a change.

## Verification

Prose, so the honest exception to TDD applies and is said out loud: the change is verified by the
derived greps above (each seen to hit before the edit and miss after; the digit grep seen to hit
again on a planted digit that is then removed) and by the full gate, `SEGUE_REQUIRE_BROWSER=true
SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`, run blocking, with its exit code read
before any claim is written. `docs/` is a declared test input, so `DocumentationLinksTest` and the
guide-example tests rerun on the change.
