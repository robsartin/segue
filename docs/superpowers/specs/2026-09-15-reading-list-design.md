# A reading list — the `book` kind in the seed tool, and the chapter that follows — design

Issue #333. Written 2026-09-15 against the code on `main` after #331 (the fourteenth reading).
Coverage sub-project 2(b), in the owner's order of 2026-09-14: after the non-touring rows (2(a),
#328, run on 2026-09-15) and before a film list (2(c)).

## What the owner chose

- **The list does not exist yet**; its shape is ours to pick, and the cheapest shape for the owner
  to produce wins.
- **Rows are both authors and books**, in one list, the kind column telling them apart.
- **A row is not known until rated**, as in 2(a): added and expanded, dealt on the deck with the
  list's mapping as the deck's own `--known`, and on the known list only once rated at or above
  `KnownList.PROMOTION_RATING` (ADR 48).
- **A book resolves by title, kind and class, nothing else**: a `book` kind with a class filter in
  the seed tool, ambiguous titles to the review file the owner already reads.

## What exists, and the one gap

The seed tool (ADR 40) resolves a `name,kind,status` list to a mapping; it knows the `author` kind
(a `PERSON` carrying a writing occupation) and has no kind for a book. `expandPromotions --known
<mapping> --add` (#328) adds what the graph lacks and expands it; the deck deals the mapping's
unrated entities with that file as its own `--known`; the promotion rule carries what was rated
highly onto the known list. The census and the harness read the touring file as before.

The gap is resolving a title. "Kind is `WORK`" is too loose — albums, films and episodes are
works — and a title is often ambiguous across editions, translations and adaptations. Everything
downstream of the mapping already handles a work: the expander fetches and expands it (recording
its `AUTHORED` edge to the author whether or not the author is on the list); the deck's card
carries the node's kind and the deck does not filter on it; the sweep starts from every known id,
reaches the author one hop out and the author's neighbours two hops out, and only people and
groups become candidates (`CandidateSweep.couldBeExplored`), so a known book recommends people and
never other books. None of that changes.

## The `book` kind: one more signal, the same shape

`seed.Expectation` is two sets today: the node kinds a row may resolve to and the occupations a
person must carry; `Adjudicator` accepts when name, kind and occupation agree and the best
candidate clears the sitelink margin. The `book` kind adds a **third set of the same shape, the
classes a work must be an instance of**, checked as occupation is:

- `Expectation` gains `classes` (a set of class ids) with `checksClass()` and
  `acceptsClass(Collection<String> p31)`, mirroring `checksOccupation` / `acceptsOccupation`. A kind
  with an empty class set is unchanged.
- `Expectations` gains `put("book", EnumSet.of(NodeKind.WORK), Set.of(), WRITTEN)` where `WRITTEN`
  is the written-work classes the kind mapper already maps to `WORK` — book, literary work and
  written work, as `KindMapper` spells them. **An edition or translation is not in the set** (the
  kind mapper's "version, edition or translation" class stays out), so a title that matches only an
  edition fails the class check and goes to review rather than resolving to the wrong thing. No
  class is added to `KindMapper`: a class the mapper does not map would not be a `WORK` and could
  not pass the kind check anyway, and changing the mapper changes every projection.
- `WikidataFacts` already fetches `P31` for every candidate and folds it to a kind; `CandidateFacts`
  keeps the raw class list beside the kind so the new check has something to read. Nothing else in
  the pass changes.
- `Adjudicator`: a candidate that fails the class check is refused the way one failing the
  occupation check is, and the reason names the class check. The review file gains no new column.

`P31` here is a **resolver filter and not an edge**, the rule ADR 40 already states for `P106`.

## The list, and the run after it

- **Shape**: the seed tool's three columns as they are, `name,kind,status`, with `author` or
  `book` in the kind column and the status empty — a hand list carries no tour status; the tool
  carries the column untouched, and the plan confirms an empty field reads. The list lives outside
  the working tree like every other list (ADR 33).
- **Run**: `resolveNames --list <list>`; the owner reads the review file (names, never pasted);
  then the 2(a) chapter from its census step on, with the mapping as `--known`: census, dry run and
  run with `--add`, census again, a deck session with the mapping as the deck's own `--known`. The
  derive step is the one #332 rewrites; for this list it is "write it, then resolve it".
- **Then the fifteenth reading** on the normal rule, with its own note.

## Records

- **ADR 40**, a dated amendment: the `book` kind; the class set as a third signal beside kind and
  occupation; why an edition is outside the set; the alternatives rejected below.
- **The developer guide**: the bulk-seeding chapter names the new kind where it says what the kind
  column may hold; the 2(a) chapter, as #332 leaves it, is the runbook for this list too.

## Testing

Pure TDD, red observed before green, invented ids only, planted positive controls, no wall-clock
assertion, no test on the network.

- `ExpectationTest` (new, beside the existing `ExpectationsTest`): a class in the set accepted; one outside refused; a kind with no class set
  accepting any class; `checksClass` true only when the set is non-empty.
- `AdjudicatorTest`, a `book` row against candidates built from invented facts: a work carrying a
  written class accepted; a film with the same title refused on the class check, the reason naming
  it (the planted control: the same candidate with a written class accepted); an edition losing to
  the work; two written works within the sitelink margin sent to review.
- `WikidataFactsTest` against `StubWikidataServer`: the raw class list kept beside the kind.
- `ExpectationsTest`: `forKind("book")` expects `WORK` and the written classes; every existing kind
  unchanged (the existing tests are the control).
- The production class set names real Wikidata class ids, as `KindMapper`'s table does; no test or
  fixture does (`StandInQidsDenoteNothingTest`).

## Alternatives rejected

- **A fourth column naming the author, and a `P50` tie-break.** Fewer review lines, at the price
  of a four-column list, a `P50` read in the facts pass, and a second pass after the authors
  resolve. The review file already exists for the residue; this is the upgrade if the residue turns
  out large on the owner's list.
- **Authors only, books picked from their expansions.** Sidesteps title matching but hands the
  owner a picking step over lists of titles: books surfaced from author expansions would arrive as
  candidates, and the deck offers only people and groups as candidates
  (`CandidateSweep.couldBeExplored`), while a book on the list is dealt as a known card.
- **Accept any `WORK` for a `book` row.** Albums and films are works; the kind alone resolves a
  title to the wrong thing whenever the film is better known than the book.
- **Books known outright.** The owner chose rate-first; the promotion rule already turns a high
  rating into membership, so no second membership rule.
- **A `book` class added to `KindMapper`.** The set is drawn from what the mapper already maps to
  `WORK`; widening the mapper changes every projection and is a different decision (ADR 42).

## Out of scope

The film list (2(c)). The runbook's derive step (#332). Any change to the recommender's kind rule,
the deck, or the harness. The fifteenth reading's note.

## Notes added while planning (2026-09-15)

Two factual corrections, from reading the code this design names. Nothing above is withdrawn; both
are places the body describes the tree as it is not.

- **The guide has no sentence saying what the `kind` column may hold.** "Records" above asks the
  bulk-seeding chapter to name the new kind "where it says what the kind column may hold". The
  chapter says only "The list is three columns — `name,kind,status`" and never enumerates the
  column's values. So the plan **adds** a sentence that names `book` and cites `seed.Expectations`
  as the authority for the rest, rather than editing a sentence that does not exist. Naming every
  kind in the guide would be a second copy of that table.
- **Production reaches the expectation through `forKinds`, never `forKind`.**
  `NameGroup.expectation()` calls `Expectations.forKinds(kinds())` for every group, including a
  group of one row. "Testing" above asks only that `forKind("book")` be proved. That test alone
  would be green over a dead path: `forKinds` builds a fresh `Expectation` from the kinds it
  unions, so unless the union carries the class set too, no `book` row is ever class-checked. The
  plan proves `forKinds` as well, and pins the union's permissive rule — a name listed as both a
  `book` and an `author` checks no class, exactly as the occupation union already behaves — with a
  planted control.
