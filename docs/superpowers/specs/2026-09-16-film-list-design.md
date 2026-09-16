# A film list — the `film` kind, and `tv-show` tightened, in the seed tool — design

Issue #338. Written 2026-09-16 against the code on `main` after #337. Coverage sub-project 2(c), in the
owner's order of 2026-09-14: after the non-touring rows (2(a), #328) and the reading list (2(b),
#333). The mechanism is #333's, applied twice.

## What the owner chose

- **The list does not exist yet**; its shape is ours, and it is the reading list's shape.
- **Rows are both people and titles**: `actor`, `director`, `film` or `tv-show` in the kind column.
- **Television is in, as its own kind**: `tv-show`, tightened from "any work" to the series-shaped
  classes, so a title typed as a show is checked against being one and a title typed as a film
  against being one.
- **A row is not known until rated**, as in 2(a) and 2(b): added and expanded, dealt on the deck
  with the list's mapping as the deck's own `--known`, and on the known list only once rated at or
  above `KnownList.PROMOTION_RATING` (ADR 48).
- **Two kinds, two named class sets in the kind mapper**, over a `series` kind beside a loose
  `tv-show` or one `screen` kind spanning both.

## What exists, and the gap

`seed.Expectation` carries three sets since #333 — kinds, occupations and classes — and the
adjudicator refuses a candidate whose stated classes are outside the set, inside the same filter as
the kind check, so a better-known adaptation never reaches the sitelink margin. `KindMapper` maps
film, animated film, short film, television film, animated short film, television series,
miniseries, television program, television special, television series episode and audiovisual work
to `WORK`, all as anonymous literals. The seed tool knows `actor` and `director` (people with the
acting and directing occupations) and `tv-show`, registered before #333 as a `WORK` with no class
set, so it accepts any work at all: a film for a show, an episode for a series, an album with the
same name. There is no `film` kind.

Everything downstream of the mapping already handles a work (#333's spec, verified against the
code there): the expander with `--add` fetches and expands it, the deck deals it as a card of its
kind, a known work seeds the sweep, and only people and groups become candidates
(`CandidateSweep.couldBeExplored`), so a known film recommends its director, writers and cast and
their neighbours, never other films.

## The two kinds

- **`KindMapper`** gains nine named constants beside `BOOK`, `LITERARY_WORK` and `WRITTEN_WORK`, one
  per class named above except the episode and the audiovisual work, which stay anonymous; the
  table's `put` lines cite the constants. One home for the ids, as #333 established.
- **`Expectations`** gains two class sets built from those constants, `FILM` (film, animated film,
  short film, television film, animated short film) and `TELEVISION` (television series,
  miniseries, television program, television special), and:
  - `put("film", EnumSet.of(NodeKind.WORK), Set.of(), FILM)` — new;
  - `put("tv-show", EnumSet.of(NodeKind.WORK), Set.of(), TELEVISION)` — the empty class set
    replaced.
- **Deliberately in neither set**: the episode class (an episode is not a show, and a title
  matching only an episode goes to review) and the generic audiovisual-work class (the reason an
  edition is not a book).
- Nothing else in the seed tool changes: the class check, the union rule, the review line and the
  facts pass are #333's as they are.

**The one behaviour change.** A `tv-show` row that used to resolve to any work now resolves only to
a series-shaped one; a title matching only a film, an episode or an unclassified work goes to
review with the reason naming the class check. That is the check the owner asked for, and the
review file is where it lands. It is recorded as a correction of the pre-#333 registration, not a
new decision.

## The list, and the run after it

The reading list's shape, unchanged: `name,kind,status`, the status empty with the trailing comma
kept. Then the 2(a) chapter as #334 left it: `resolveNames`, read the review file (names, never
pasted), census over the mapping, dry run and run with `--add`, census again, a deck session with
the mapping as the deck's own `--known`. The reading that follows is numbered on its day by whichever
list ran first; its note says which.

## Records

- **ADR 40**, a dated amendment: the `film` kind; `tv-show` tightened and why that is a correction;
  the classes in each set and the two deliberately out; the alternatives rejected below.
- **The developer guide**: the bulk-seeding chapter's kinds sentence already cites
  `seed.Expectations` for the list, so it needs no change; the plan verifies that rather than
  assuming it.

## Testing

Pure TDD, red observed before green, invented ids only (including invented class ids in fixtures;
the production sets are referenced through the constants), planted positive controls, no
wall-clock assertion, no test on the network.

- `ExpectationsTest`: `forKind("film")` expects `WORK` and the `FILM` classes; `forKind("tv-show")`
  expects `WORK` and the `TELEVISION` classes; every other kind unchanged (the existing cases are
  the control).
- `AdjudicatorTest`, through `Expectations.forKinds` as #333's seam test does: a film-classed work
  accepted for `film` and refused for `tv-show`; a series-classed work the reverse; an
  episode-classed work refused for both, the reason naming the class check; and **the tightening's
  red**: a `tv-show` row against a film-classed work, which resolved before and goes to review now
  — observed red before the class set is registered, green after.
- `KindMapperTest`: unchanged; the constants equal the table entries, proven the way #333 proved
  the first three.
- `StandInQidsDenoteNothingTest` unchanged: no test names a real id.

## Alternatives rejected

- **A `series` kind beside a loose `tv-show`.** Two names for one thing, one exact and one loose,
  and the loose one keeps accepting a film for a show.
- **One `screen` kind spanning films and television.** Less typing, but a title typed as a film
  is never checked against being one, which is the check the owner asked for.
- **Television under `film`.** The same objection with one name.
- **Leaving `tv-show` as it was.** The class signal is what it was missing; leaving it loose after
  #333 would make the seed tool's own rule inconsistent across its work kinds.
- **The episode class in `TELEVISION`.** An episode is not a show; a list row naming an episode is
  a mistake the review file should show.

## Out of scope

The reading list's run and the film list's run themselves, and their readings. Any change to the
recommender, the deck, the expander or the harness. A class set for `book` beyond #333's.
