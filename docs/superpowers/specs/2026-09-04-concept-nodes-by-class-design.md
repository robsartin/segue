# Counting CONCEPT nodes by the class they state

Issue [#248](https://github.com/robsartin/segue/issues/248). Written 2026-09-04, against `main` at
`da8efa9`.

## The question nobody can answer today

17,099 nodes in the owner's graph are `CONCEPT`. `KindMapper.fromInstanceOf` returns `CONCEPT`
whenever none of an entity's stated classes is in its whitelist, so an unknown share of those 17,099
are people, groups, works or places wearing a class the mapper has never met — the same defect issue
#52 measured once by hand, where 1,058 of 1,416 eligible `CONCEPT` intermediates turned out to be
works, 667 of them "musical work/composition" alone.

That measurement was taken with a throwaway probe and cannot be taken again. The census
([ADR 63](../../adr/0063-a-read-only-census-of-the-graph.md)) counts nodes by kind and stops there.
The class is already on the node — [ADR 42](../../adr/0042-store-p31-and-rederive-kind-at-projection.md)
stores `P31` beside the derived kind precisely so a projection can re-derive offline — so the count
is a fold away.

## What is built

A seventh census section, `concept classes`, printing:

```
concept classes
  stating no class                        N
  distinct classes                        M
  class Q…… unmapped                      n
  …
```

- **`stating no class`** — `CONCEPT` nodes whose `instanceOf` is empty. A different gap from an
  unknown class: the source classified without stating a class, so there is nothing for a whitelist
  entry to catch, and no mapper change would move them. Separating the two is what stops the section
  overstating the mapper's gap.
- **`distinct classes`** — how many classes `CONCEPT` nodes state in all, so the reader knows how
  much the rows below leave out.
- **Ten rows at most**, one per class, ordered by count descending and by class qid ascending on a
  tie. Each is "how many `CONCEPT` nodes state this class": a node stating a class twice is one
  node, and a node stating three classes appears on three rows. That is the number a mapper rule
  would move, which is what the section is for.

No `KindMapper` change. Which classes deserve a rule is the follow-up issue, and it can only be
opened once the owner has run this and pasted the section.

### Why ten, and why the cut is a privacy argument rather than a brevity one

Ten keeps the section the same order of size as its siblings — `claims` is ten lines — so the block
still fits in an issue comment. But the load-bearing reason is different: **a class stated by one
node is the row that comes closest to naming an entity**, and ordering by count descending is
exactly what pushes it out. The rows that survive the cut are the commonest classes in the graph,
which are the ones a mapper rule is worth writing for and the ones that say least about any
individual node. `distinct classes` reports the size of what was cut without printing it.

The residual is honest and stated in the ADR amendment: on a graph small enough that ten rows is the
whole distribution, a count of one can reach the output. Nothing hides it, and ADR 63's own answer
applies — `--db` is typed per invocation because whether to publish is the owner's decision, taken
each time.

### The `mapped` / `unmapped` marker, and where the issue's premise does not match the code

The issue and the dispatch both describe the marker as separating "mapped classes that still landed
as `CONCEPT` via precedence" from unmapped ones. **That case cannot occur.** `KindMapper`:

- `fromInstanceOf` maps each stated class through `BY_CLASS` and `filter(Objects::nonNull)` — a
  class the whitelist does not know is *skipped*, never ranked;
- `PRECEDENCE` then takes the `min` over the kinds that survived, and `CONCEPT` is only the
  `orElse` when nothing survived;
- every one of the 53 `BY_CLASS` entries maps to a kind other than `CONCEPT`.

So a node that states any class the whitelist knows is never `CONCEPT`, and precedence cannot
produce one. `LogProjection` re-derives on both node paths that carry classes
(`Equivalences.standIns(logged, KindMapper::rederive)` and the `NodeAssertion` case); the third,
`LocalEntity`, does not re-derive but `LocalEntity` carries no classes at all. **Through the
production fold, every row this section prints reads `unmapped`.**

The marker is kept anyway, on two grounds it can carry honestly:

1. **It is the section's invariant, printed.** The section's headline claim is "these are classes
   the mapper has no rule for". Without the marker that is an assumption; with it, a row reading
   `mapped` is the fold and the mapper having come apart — a whitelist entry that maps to `CONCEPT`,
   or a node reaching the projection without re-derivation, which the `LocalEntity` branch already
   does and which would start producing one the moment a local entity carried a class.
2. **It is `isMapped`'s first production caller.** [ADR 21](../../adr/0021-six-kind-ontology.md)'s
   issue-#87 amendment rejected a conflict flag partly because *"`isMapped`, the existing 'report
   what we could not map' seam, has no production caller to report through"*, and said the surface
   could be added later against unchanged data. This is that surface, and the data is unchanged.

**Rejected: drop the marker and print class qid and count alone.** One boolean and one call
cheaper, and the column is a constant today. It loses because the section would then assert its own
premise with nothing checking it, and because a reader cannot tell a whitelist that has quietly
grown a `CONCEPT` entry from one that has not.

**Rejected: one summary line — "stated by a class the mapper knows: 0" — instead of a per-row
word.** Shorter, and it carries the same invariant. It loses on the truncation: a summary count says
some mapped class is down there without saying which, and the rows are already sorted so that the
one worth seeing is at the top.

**Rejected: `ClassLabels` for a human-readable name beside the qid.** It is a hand-curated display
table whose fallback prints the bare qid, so on exactly the classes this section exists to surface —
the ones nobody has met — it would print the qid anyway, plus a column of blanks. It would also put
a curated English string into an output whose whole guarantee is that it interpolates nothing but
integers and one identifier.

## Printing a class qid, and the guard that has to be narrowed for it

ADR 63's decision includes the clause *"nothing matching `\bQ\d+\b` anywhere"*, enforced end to end
by `CensusIsSafeToPasteTest`. A section that prints class qids changes that decision, so ADR 63
gains a dated amendment; the ruling is the owner's:

**A Wikidata class identifier is vocabulary, not the owner's data.** ADR 63 already admits two
kinds of raw text off the log for exactly this reason — edge type codes in `of type …` and source
ids in `backed by …` — on the ground that they are vocabulary rather than entities. A class id is
the same kind of thing: it is Wikidata's shared name for a category, stated by a source about an
entity, and it identifies no entity in the owner's graph. What the row discloses is a count, which
is an aggregate, and [ADR 51](../../adr/0051-what-an-adr-may-quote.md)'s line — an aggregate over
the owner's data is publishable, an entity presented as the owner's is not — is where it lands.
ADR 51 itself is untouched: it remains the rule for prose, held by review.

**The carve-out is one prefix wide, and that is enforced.** The guard is not relaxed to "a qid is
allowed somewhere"; it is narrowed to "a qid is allowed only as the head of a line this section
owns". `CensusReport` gives every counted line a two-space indent and this section's rows the
literal `class ` in front of the id, so `^  class Q\d+` is a prefix no other line in the report can
produce. The test strips exactly that prefix, once, and applies the unchanged `\bQ\d+\b` to what is
left — so a second qid on a class row fires, a qid on any other line fires, and a line that merely
starts with the word `class` at column zero fires.

Three planted lines are asserted to fire, and each is proved able to fail by a matching plant in the
guard itself:

| Planted line | What it stands for | Plant that makes it pass wrongly |
| --- | --- | --- |
| `  ratings` … `Q0900901` | an entity id on another section's line | widen the prefix to `^  \w+\s+Q\d+` |
| `  class Q0900302 unmapped  Q0900901` | a second id smuggled onto an allowed row | short-circuit `line.startsWith("  class")` to allowed |
| `class Q0900302 unmapped  1` | the section's own words without its indent | drop the `^  ` anchor |

The `\bQ\d+\b` clause in `EvaluationIsSafeToPasteTest` is a separate guard over a separate report
and is **not** narrowed.

## Where it goes, and why last

`Census` gains a seventh component, `ConceptClassCensus`, and `CensusReport` a seventh section,
after `bridge`. Two reasons, and the first is the one that would hold on its own: the section drills
into a number the `nodes` section already reports, and a drill-down reads after the count it drills
into. The second is mechanical — issue #247 is adding a by-kind breakdown to the `degree` section on
this same base, and a section appended after the last one renumbers none of its lines.

The longest label this section produces is `  class ` plus an eleven-digit qid plus ` unmapped`, 29
characters, well under the report's existing widest label (`  merges superseded but edge-referenced`,
38). Its counts are no wider than the existing widest. So neither derived column width moves, and
the change to `CensusReportTest`'s pinned block is three appended lines rather than a reflow.

`Census`'s own javadoc — *"no qid, label or note reaches this type"* — stops being true and is
corrected in the same commit, with the carve-out and a pointer to the amendment. That sentence is
the reason the whole-output assertion is possible, so leaving it stale would be the drift ADR 6
exists to prevent.

## What holds it

- `ConceptClassCensusTest` — the counting: once per class per node, `CONCEPT` nodes only, the
  no-class line, `distinct classes` counting past the cut, the order (count descending, qid
  ascending), the cut at ten, and the marker reading `mapped`. Every case but the last folds a small
  invented log of its own, the precedent `EdgeCensusTest` set for a case the shared fixture cannot
  reach. The last builds a `LogProjection` by hand and says in its javadoc that it must, because the
  fold cannot produce a `CONCEPT` node stating a mapped class — the finding above, pinned as a test.
- `CensusReportTest` — the three new lines, in place, with the alignment applied by hand.
- `CensusIsSafeToPasteTest` — the narrowed guard, its anchor assertion (`anyMatch` on
  `  class Q`, so the carve-out is never vacuously satisfied by a run that printed no class row) and
  the three controls above.
- `StandInQidsDenoteNothingTest` — the `Q5` allowance gains one site, because the marker test needs
  a class the whitelist really knows. `Q5`'s existing entry already reads *"class id — mapped by
  ClassLabels and KindMapper"* and lists nine sites; this is a tenth.
- The developer guide's census chapter, and the ADR 63 amendment. Neither has unit-testable
  behaviour; both are verified by the full `check` gate, which runs `AdrIndexTest`, the doc-link
  test and `DeveloperGuideEnumerationsTest` over them. No new package import edge is introduced —
  `census --> wikidata` is already in the layering diagram, permitted by
  `theCensusOpensNothingElse`'s deliberate carve-out for `KindMapper`.

## Out of scope

- Any `KindMapper` entry. The point of the section is to decide which ones, from real data.
- A label beside the qid, a `--top` flag, a minimum count threshold, or a second section for the
  classes the whitelist does know. Each is a decision the first real reading should inform.
