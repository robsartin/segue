# The rating deck

Design for issue #101, 2026-08-28.

## The problem

The `affinity` table holds zero rows against 307,037 assertions. Every part of segue that depends on
taste is therefore unexercised: `Recommendations.regardFor` returns `EQUAL_REGARD` when the map is
empty, so `recommend` currently answers "what is structurally adjacent to your list", not "what
would you like". The weighting wired up in issue #85 has never met a real rating.

Two things stand between the owner and a populated table. Rating through conversation does not scale
to 815 entities. Rating through a file means typing, which is expensive for this owner and is the
constraint the whole design answers.

## Why low ratings are the point

`regardFor` centres on the middle of the scale — 5 counts for 5/3, 1 for 1/3, and an unrated entity
for 1.0. The spread between a 1 and a 5 is therefore **5×**; between a 4 and a 5 it is **1.25×**.

The known-list is 815 entities the owner already likes, seeded from a concert history. Rating them
all 4 or 5 moves almost nothing. The deck earns its keep only if it makes a 1 or a 2 as cheap to
give as a 5, which is why the gesture is one keystroke and why "skip" exists as a separate,
non-recording action: an entity the owner does not recognise must not become a low rating by
default.

## Placement: a sixth dev-side tool

segue has five dev-side Gradle tools — `resolveNames`, `exportGraph`, `listRatings`, `recommend`,
`retractEntity`. They exist because ADR 39 and ADR 43 reserve bulk taste-layer operations to the
owner's own machine and off the MCP surface, which ADR 26 pins at six tools.

The deck is the same kind of thing and goes in the same place. `./gradlew rate`, new package
`com.robsartin.segue.rate`.

Rejected: **a controller in the running Spring app**. The app already serves HTTP on
`127.0.0.1:8080` for the Streamable HTTP MCP transport, so the machinery is there. But that would
put a taste-layer *writer* on the MCP server's unauthenticated port — the boundary this project has
guarded most carefully — and ADR 32 confines Spring to the `app` and `mcp` packages, so it would
need a third package admitted to that rule. Neither cost buys anything the dev-tool placement does
not already have.

Rejected: **a terminal deck**. Viable, since the gesture is a single keypress, and it would need no
HTTP at all. Rejected because recognition is the hard part of the task: class names and routes read
substantially better as a rendered card than as wrapped terminal text, and a card the owner cannot
read quickly becomes a skip.

## Components

Four classes, each independently testable. The split exists so that the part with the judgement in
it — the ordering — has no HTTP or database in the way.

### `RateCli`

Argument parsing and nothing else, mirroring `RecommendCli`.

| flag | required | default |
|---|---|---|
| `--known` | yes | — |
| `--db` | no | `SEGUE_DB`, else `${user.home}/.segue/segue.db` |
| `--port` | no | 8090 |

`--known` is the same file `recommend` takes, read by `QidList.read`. It is required for the same
reason it is required there: the deck is a statement about entities the owner has, and a tool that
picks that list for you is a tool that has guessed.

`--port` defaults to 8090 rather than 8080 so that the deck and the MCP server can run at once and
so that nothing addressed to one can arrive at the other.

### `RateRun`

Orchestration. Replays the assertion log through `GraphProjector` into a `TinkerGraphStore`, exactly
as `RecommendRun` does, so that the routes shown on a card are the routes `find_paths` would return.
Reads existing ratings once via `AffinityStore.readRatings()`. Builds the deck, starts the server,
and blocks until interrupted.

Notes go to a `Consumer<String>` rather than a logger of its own, as `RatingsRun` does, so ordering
is observable from a test.

### `Deck`

A pure function, and the only component with a decision in it:

```
known qids + degree(qid) + labels + classes + existing ratings + candidates  →  ordered List<Card>
```

Rules:

1. **Exclude anything already rated.** Read from `readRatings()` at startup.
2. **Order the known set by in-graph degree, descending.** A rating moves candidate scores in
   proportion to how many candidates that entity touches, so this buys the most score movement per
   keystroke — the owner should be able to feel the recommender change within a session.
3. **Deal a recommender candidate as every fifth card.** Candidates come from `recommend`'s existing
   sweep, which already produces routes per candidate (issue #83).
4. **No persisted position.** The deck is "everything unrated", recomputed each run. Resuming is
   automatic; quitting mid-session costs nothing; there is no state file to corrupt or to leak.

### `RateServer`

`jdk.httpserver`, bound to `InetAddress.getLoopbackAddress()`. Three routes:

| route | does |
|---|---|
| `GET /` | the page, from a classpath resource |
| `GET /api/card?i=N` | the Nth card as JSON |
| `POST /api/rate` | `{qid, rating}` → `AffinityStore.put` |

**Origin and Host are checked against an allowlist**, as `SegueConfiguration` already does for the
MCP endpoint under ADR 28. Loopback binding alone does not stop a hostile page in the owner's own
browser from posting here, and this endpoint writes the one table in segue that cannot be
regenerated.

## The card

Two shapes, because "why is this here" only has an answer for one of them.

**A known entity** shows its name, its kind, its class names from `ClassLabels`, and *its in-graph
degree* — "connects 214 things". That is the argument for why rating this one matters, and it is
**the same number the ordering is built on**, so the owner can see the tool's reasoning rather than
take it on trust, and a card near the top of the deck visibly says why it is there.

The number is deliberately the degree the ordering uses and not the more natural-sounding "47 of
these are on your list". Those are different quantities, and showing one while sorting by the other
would make the deck look arbitrary at exactly the moment it is trying to explain itself.

**A candidate** shows its name, kind and class names, and up to three routes tying it to entities
the owner knows.

Nothing else on either card.

## The gesture

Five `<button>` elements labelled 1 to 5, bound to the `1`–`5` keys. `b` goes back one card. `s` or
space skips.

- **Rating auto-advances.** One input per card, no confirm step.
- **Back re-rates, it does not un-rate.** `AffinityStore` has `put`, `find`, `readAll` and
  `readRatings` — no delete. Going back and pressing a different number is a second `put`, which
  needs no new port method and raises no question about deleting personal data. A first-ever rating
  cannot be withdrawn, only changed; this is stated in the page.
- **Skip records nothing.** An unrecognised name must not become a low rating.

Progress reads "142 of 815 rated".

Accessibility, per this project's standing rule: real semantic buttons rather than clickable divs,
visible focus, an `aria-live` region announcing each new card so the change is not visual-only, and
contrast at WCAG AA. No external assets of any kind — no CDN, no web font — so the page works
offline and cannot phone anywhere.

## What it writes, and the fences

**Ratings only. There is no note field.** Issue #85 split the taste layer so that a score is
ordinary data and a note is protected; the deck holds that boundary by construction rather than by
rule. A note would also mean typing, which is the cost this tool exists to avoid.

Three ArchUnit rules, matching those already guarding the sibling tools:

- `rate` may call `AffinityStore.put`, and never `AssertionLog.append`, `GraphStore.record` or
  `GraphStore.upsertNode`
- `rate` never calls `AffinityRecord.note()`
- no rating value reaches a log line (ADR 33); logs carry counts and paths, as `RatingsRun`'s do

## Testing

- `Deck` — unit tests for degree ordering, the every-fifth-card interleave, and exclusion of
  already-rated entities. No HTTP, no database.
- `RateServer` — started on port 0 and driven with `HttpClient`: a `GET` returns the expected card,
  a `POST` writes to a scratch `AffinityStore`, and a request carrying a foreign `Origin` is
  refused.
- A test asserting the page resource contains no external URL.
- A test asserting no card JSON contains a note, in the spirit of
  `NoteNeverLeavesThroughAToolTest`.
- The three ArchUnit rules, each verified to bite by temporarily introducing a violation.

## Out of scope

No note entry. No un-rate. No authentication beyond loopback and the Origin allowlist. No browse or
search — that is what conversation is for. No saved session position.

## Known gap, accepted deliberately

Rating a candidate writes a row to `affinity`, but `regardFor` reads only known-list QIDs. **A
candidate's rating therefore changes no score today.** The owner chose the mixed stream knowing
this, because rating a candidate is still the natural moment to record a reaction.

Closing it means deriving the known-list from the taste layer, which reopens ADR 40 (the seeding
list lives outside the repository and nothing on the MCP surface can see it) and ADR 43. That is its
own argument and its own issue, not a line to be added here.

## ADR

One ADR records the sixth dev-side tool, its port, and the Origin allowlist as a second use of ADR
28's reasoning.
