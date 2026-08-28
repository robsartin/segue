---
status: Accepted
date: "2026-08-24"
topic: path-ranking-by-confidence
tags: [project, graph, trust]
supersedes: []
related: [quarantine-model-generated-assertions, graph-engine-gremlin, mcp-tool-surface, six-kind-ontology, award-received-as-the-first-non-collaboration-edge, store-p31-and-rederive-kind-at-projection]
---
# 31. Rank paths by weakest confidence, not by hop count

## Context

ADR 23 records this as a known open issue. In the slice 0 fixture the shortest route
between Nick Cave and Cormac McCarthy is an unverified model guess at confidence 0.30,
while the trustworthy route is three hops at 1.00. Ranking by length surfaces the guess
first.

`PathResult.weakestConfidence()` already exists and nothing calls it. Because
`find_paths` is one of the six tools, shipping it unranked would mean the first thing
seen in a conversation is a plausible wrong answer, which is the precise failure mode
the provenance design exists to prevent.

The obstacle is structural rather than arithmetic: `shortestPaths(from, to, maxHops,
limit)` truncates inside each adapter, so the best routes can be discarded before any
ranking code could see them. Ranking cannot be fixed where the results are already cut.

## Decision

- **`shortestPaths(from, to, maxHops, limit)` becomes `paths(from, to, maxHops)`.**
  Adapters return every route they found up to `maxHops`. The old name was a misnomer:
  the traversal already returned all routes, not only the shortest.
- **A shared `PathRanking` orders and limits once, above the port**, so both adapters
  get identical ordering and neither can drift.
- **The order is weakest confidence descending, then hop count ascending.** A path is
  only as trustworthy as its shakiest hop; length breaks ties.
- **An internal cap bounds the returned list**, so a dense neighbourhood cannot produce
  an unbounded result.

**Amendment (2026-08-26, issue #52): a second dimension, and it goes first.**

Confidence is the right axis and nothing above is withdrawn. It is not the only axis, and
dogfooding a real 25,815-node graph showed what it misses. The best-connected route between
Anjelica Huston and Bea Arthur was

```
Anjelica Huston -[RECEIVED_AWARD]- star on Hollywood Walk of Fame
                -[RECEIVED_AWARD]- Billy Crystal
                -[RECEIVED_AWARD]- Disney Legends
                -[RECEIVED_AWARD]- Bea Arthur
```

Four hops, every one a referenced Wikidata statement at 1.00, ranked top by the rule above, and
it explains nothing — a Walk of Fame star says "this person was famous", which is true of
everyone in the graph and is therefore a relationship between nobody. Of the 25,525 nodes shared
by two or more seeds, only 26 were shared by ten or more, and they were overwhelmingly
career-recognition awards. ADR 38 left this open as its question 3; this is the answer.

- **Ranking gains a specificity dimension: the in-graph degree of a `CONCEPT` intermediate.**
  A route through a busy `CONCEPT` is demoted. `PathRanking.HUB_DEGREE` is 10.
- **Kind is half the rule and the half that makes it work.** Degree alone is the wrong signal,
  because the busiest nodes in the graph are the expanded seeds themselves — The Beatles at 200
  edges, David Bowie at 200 — and those are legitimate connectors. Every hub measured was a
  `CONCEPT`; every busy legitimate node was a `PERSON`, a `GROUP` or a `WORK`.
- **Endpoints are not intermediates.** "What connects me to the Rock and Roll Hall of Fame" is a
  fair question, and its answer must not be demoted for ending where it was asked to end.
- **Specificity is a veto, not a score.** Two classes — hub-bearing or not — rather than ordering
  continuously by degree. Fifteen of 25,815 nodes clear the threshold, so confidence still
  decides all but a handful of comparisons; ordering by raw degree would make confidence
  vestigial, since degrees rarely tie.
- **The full order is: no model guess, then fewest hub intermediates, then weakest confidence
  descending, then hop count ascending.**

### How the two compose, and which wins

**Specificity wins.** A route through a low-degree intermediate beats a route through a hub of
the same confidence — and of *higher* confidence, which is the part that had to be true for this
to be worth doing. The award routes are all 1.00 and the informative ones are 0.80, so a
specificity tiebreak *within* equal confidence would have changed nothing at all; the empty
routes filled the entire top band and no tiebreak could reach them. The two dimensions answer
different questions, and the order says which question is asked first: **confidence asks whether
a route is true, specificity asks whether it means anything, and a route that means nothing is
not made better by being certain.**

**One exception, and it is this ADR's own decision.** Specificity never promotes a path that
rests on a model guess (ADR 23's `llm:` tier) above one a real source stands behind. This ADR
exists so a plausible wrong answer cannot be the first thing a conversation sees, and letting
specificity invert that would have been a regression dressed as an improvement — a hub route at
1.00 losing to a 0.30 guess. Inside each of the two quarantine tiers, specificity leads as
above. The guard changes no ordering that exists today (a hypothesis hop is 0.30 by convention,
so weakest-confidence already sorts it last); it exists so that the day model-generated edges do
arrive, they cannot be promoted by a dimension that says nothing about whether they are true.

### The architecture this is allowed to touch

`PathRanking` lives in `domain`, which carries zero third-party dependencies and no graph access
(ADR 18, ArchUnit-enforced) — and specificity is a fact about the graph's shape. **The degree
lookup is passed in as a `java.util.function.ToIntFunction<String>` over a qid**, which is
`java.*` and leaves the domain pure; `SegueService` supplies it from the port it already holds,
memoised for the duration of one call. A no-degree overload keeps the original order for callers
that cannot supply one — notably the contract tests, which compare two engines rather than judge
a route.

### Consequences of the amendment

- Huston ↔ Arthur now leads with a route through two specific acting awards bridged by a person
  who won both, verified against the real graph. Both known-good routes survive: Gottfried ↔
  Seyfried through CSI, Mulaney ↔ Martin through the Saturday Night Live 50th Anniversary
  Special.
- **It depends on `KindMapper` being right, and `KindMapper` was not.** "High-degree `CONCEPT`"
  only means "hub" while `CONCEPT` means "we could not place this". Measured over the 1,416
  `CONCEPT` nodes that could be an intermediate at all, 1,058 were works wearing a class the
  whitelist did not know — including the SNL 50th Anniversary Special, the single best connector
  in the graph, which this rule would have vetoed. Those classes were added in the same change.
  **Adding an award class to `KindMapper` would silently switch this rule off**; a test in
  `KindMapperTest` pins awards to `CONCEPT` for that reason.
- **The threshold is an absolute degree on a personal-scale graph, so it will drift.** It names
  the tail of a measured distribution — 9,495 `CONCEPT` nodes of degree 1, 1,329 of degree 2-4,
  fifteen at ten or above — rather than expressing a preference. Re-measure before changing it.
- **This is a ranking change, so it needs no re-projection**, unlike the `KindMapper` half: node
  kind is recorded on the assertion, and the log is append-only (ADR 19), so replay reproduces
  the kinds as they were fetched. Corrected kinds arrive when an entity is next added or
  expanded.
- **Two things it deliberately does not do.** It does not stop hub edges being ingested — ADR 38's
  award vocabulary is untouched, and a hub route is still returned, merely last. And it says
  nothing about a busy `GROUP`: the American Academy of Arts and Sciences connects 21 seeds
  through `MEMBER_OF` and is career recognition by another name. ~~Left open on purpose, because
  the measurement that would settle it is membership, not awards.~~ **Answered, 2026-08-27, issue
  #66 — see the second amendment below.**

**Amendment (2026-08-27, issue #66): a `GROUP` can be a hub too, and its own stated class says so.**

Nothing above is withdrawn. `HUB_DEGREE` is unchanged, it still applies to `CONCEPT` intermediates
only, and the composition with confidence — specificity first, the model-guess line above both —
is exactly as the amendment above set it. What this adds is a *second* way for an intermediate to
be a hub, sitting beside the degree test rather than replacing it.

**The measurement, because the last paragraph of the amendment above says which one would settle
it.** Every `GROUP` shared by five or more of the 815 seeds on the real list, on a 54,448-node graph
re-seeded with `P31` on 99.97% of nodes — 80 of them. Sorted by seeds, the top of the list and the
institutions further down:

| seeds | degree | of them by `MEMBER_OF` | node | stated `P31` |
|---|---|---|---|---|
| 33 | 33 | **33** | American Academy of Arts and Sciences | learned society, academic publisher, nonprofit organization |
| 24 | 495 | 2 | The Beatles | musical group |
| 19 | 19 | 0 | Guns N' Roses | musical group |
| 16 | 16 | 0 | Kiss | musical group |
| 15 | 15 | 0 | The Clash | musical group, rock band |
| 11 | 11 | 0 | Mötley Crüe | heavy metal band |
| 11 | 11 | **11** | Writers Guild of America West | **labor union**, nonprofit organization |
| 10 | 10 | **10** | Writers Guild of America, East | **labor union** |
| 8 | 8 | **8** | American Academy of Arts and Letters | **academy of sciences**, award |
| 7 | 7 | 5 | Monty Python | theatre comedy group, comedy troupe |
| 6 | 6 | **6** | SAG-AFTRA | **labor union**, nonprofit organization |
| 5 | 18 | 5 | Traveling Wilburys | musical group |
| 5 | 90 | 5 | Eagles | musical group |

Five of the 80 are institutions. The other 75 are bands, plus Monty Python.

- **Degree cannot do it, and this is not close.** The institutions run from 6 to 33 edges and the
  bands that must keep working run from 11 to 19 — *interleaved*, not separated. The Writers Guild
  of America West and Mötley Crüe both carry exactly 11. Any threshold that catches SAG-AFTRA at 6
  also catches all three bands the issue names, and any threshold that spares them loses four of
  the five institutions. A `GROUP`-specific `HUB_DEGREE` was the fallback if the classes had not
  separated; the numbers refuse it outright.
- **The edge type cannot do it either**, though it looked like the obvious signal — every seed
  reaches all five institutions by `MEMBER_OF` and reaches most bands by something else. It fails
  on the exceptions: Monty Python (5 of 7), the Traveling Wilburys (5 of 5) and the Eagles (5 of 5)
  are all reached by `MEMBER_OF` and all three are collaborations. A rule reading the edge would
  demote a supergroup for being a group.
- **The class does it cleanly.** Three classes — `Q955824` learned society, `Q414147` academy of
  sciences, `Q178790` labor union — cover all five institutions and match no band anywhere in the
  graph. The band classes (`musical group`, `rock band`, `heavy metal band`, `band`, `solo musical
  project`) and Monty Python's (`theatre comedy group`, `comedy troupe`) are disjoint from them.

**The decision.** An intermediate that states one of those classes is a hub, exactly as a
high-degree `CONCEPT` is, and is demoted the same way. Three things follow from it being a *class*
rather than a threshold:

- **No degree test.** "High-degree `CONCEPT`" means "we could not place this, and half the graph
  touches it" — degree is standing in for a meaning the node never stated. A stated class is the
  meaning. Election to the Royal Society is recognition of a career at four members or four
  hundred, so the rule fires at any size — which it has to, since four of the five institutions are
  below `HUB_DEGREE` and one is below half of it.
- **No kind test.** Nothing checks for `GROUP`, though every measured institution is one. A learned
  society that `KindMapper`'s whitelist has not learned falls through to `CONCEPT` and needs the
  same treatment; making the rule depend on the whitelist would reintroduce the coupling the
  amendment above already had to pay for once.
- **Any stated class counts, and position means nothing.** Real institutions wear several and the
  recognition class is not reliably in front — the American Academy of Arts and Sciences states
  learned society, academic publisher, nonprofit organization, in that order. *(Amended 2026-08-28,
  issue #87: this bullet used to read "not the first", contrasting with a `KindMapper` that took
  the first class it recognised. It no longer does — ADR 21's amendment ranks the kinds — so the
  two rules now agree that P31 order is noise, and differ only in what they ask of the classes:
  this one asks a yes-or-no question of each, the mapping ranks the kinds they imply.)*

**What was deliberately left out, and it is the trap.** All five institutions also state a broad
organization class — `Q163740` nonprofit organization or `Q43229` organization — and a table built
from what they have in common would have included it. It would have been wrong: **ABBA states
`Q43229`** at 498 edges, and the Vienna Philharmonic states `Q163740`. Only classes that say what
the body IS are listed.

### The architecture, and it is ADR 31's own precedent applied twice

`PathRanking` is in `domain`, so it may not hold Wikidata's vocabulary any more than it may hold a
graph. **The class test arrives as a `java.util.function.Predicate<String>` over a class qid**,
beside the `ToIntFunction<String>` the amendment above introduced, and the table it comes from —
`RecognitionInstitutions` — lives in `wikidata` beside `KindMapper`, which is where deciding what a
class MEANS already belongs (ADR 42). The values themselves need no lookup at all: ADR 42 put the
raw `P31` on `NodeRecord`, so the ranking reads the classes off the node it is already holding.

**There is deliberately no degrees-only overload.** A caller supplying half the specificity rule
would silently rank an academy above the film two people actually made, which is the bug this
amendment fixes. Either both signals are supplied or neither is — `rank(paths)` still exists and
still means "no view of the graph at all", for the contract tests that compare two engines rather
than judge a route.

### Consequences of the second amendment

- **Measured on the real graph, before and after.** Tom Hanks ↔ Conan O'Brien led with *Writers
  Guild of America West* (`MEMBER_OF`, 1.00) and now leads with *The Great Buck Howard*, the film
  they were both in (`ACTED_IN`, 0.80). Charles Darwin ↔ Kurt Vonnegut led with the *American
  Academy of Arts and Sciences* and now leads with *Henry David Thoreau*. Both are the amendment
  above's argument repeating itself: the institution routes are all 1.00 and the informative ones
  0.80, so confidence alone could never have reached them.
- **The bands are untouched, checked rather than assumed.** Cheap Trick ↔ Scorpions still routes
  through Mötley Crüe, Red Hot Chili Peppers ↔ Nine Inch Nails through The Clash — over the Rock
  and Roll Hall of Fame, which the degree rule demotes — and The Rolling Stones ↔ Counting Crows
  through Guns N' Roses.
- **This one will not drift the way `HUB_DEGREE` does.** The threshold beside it names the tail of
  a distribution on a personal-scale graph and has to be re-measured as the graph grows. A class is
  a property of the node, stated by the source; adding seeds does not change what a labor union is.
  The table grows the way `KindMapper`'s does — from measurement, with the label and description
  confirmed, never guessed.
- **It does not touch ingest**, exactly as the amendment above did not. Guild and academy edges are
  still recorded, still returned, and merely returned last.
- **It leaves a smaller neighbour visible and unfixed.** Three award nodes in the graph are
  classified `GROUP` rather than `CONCEPT`, because they state a nonprofit class the whitelist knows
  and an award class it does not — the Canadian Songwriters Hall of Fame (3 seeds) and the National
  Inventors Hall of Fame (1) among them. They are career recognition wearing the wrong kind, so the
  degree rule cannot see them either. At 3 seeds and below they change no ranking today, and adding
  `award` to this table is a different decision from the one measured here — recorded so the next
  person finds it rather than rediscovers it.

**Amendment (2026-08-27, issue #67): a third proposed demotion, measured and refused. Ranking
cannot reach an edition node, and does not need to.**

Nothing above is withdrawn and nothing below changes any ordering. This records a rule that was
proposed, measured against the real graph, and not built — because the measurement showed the
nodes it targeted are structurally incapable of being ranked at all.

`version, edition or translation` (`Q3331189`) is the largest single `CONCEPT` class in the graph,
visible only since ADR 42 stored `P31`. Issue #67 asked whether such a node duplicates the original
it is an edition of — in which case it states one fact twice — or carries genuinely different
personnel, in which case removing it would lose data. `P629` ("edition or translation of") links
the two, so the question is answerable rather than a matter of taste.

**Measured on the 54,448-node graph: 1,715 nodes state the class, and `P629` resolved for 1,246 of
them against WDQS. Of the 1,216 whose original is also in the graph:**

| | count | of 1,216 |
|---|---|---|
| every edge duplicates the original's, edge type included | 1,209 | **99.4%** |
| some edges shared, some not | 1 | 0.1% |
| no edge in common | 6 | 0.5% |
| **reach a person the original does not already reach** | **0** | **0.0%** |

**The last row is the answer, and it is exact rather than approximate.** Not one of the 1,216
introduces a neighbour the original lacks. The seven apparent exceptions are all the *same person*
under a different edge type: Wagner is `AUTHORED` on a libretto edition and `COMPOSED_FOR` on the
opera (Parsifal, Das Rheingold, Die Walküre, Tristan und Isolde, Tannhäuser), and Kate Bush is
`PERFORMED` on *The Sensual World* and `COMPOSED_FOR` on *Flower Of The Mountain*. There is no
remaster engineer, no liner-note author and no bonus-track guest anywhere in the sample. The
signal hypothesis is not weakened, it is refuted.

**The issue's premise was wrong in a way worth recording, because it changes where to look.** These
are not album re-releases. Of the 1,717 edges touching them, **1,663 are `AUTHORED`** — they are
book editions and translations arriving from the reverse-`P50` pass on a novelist: 97 separate
`'Salem's Lot` nodes under Stephen King, 15 `1984`s under Orwell, 174 editions under Darwin. Only
54 edges are musical at all.

### Why this ADR's rule cannot be the answer

**An edition node is a pendant leaf. 1,714 of the 1,715 have degree exactly 1; one has degree 2.**
A path intermediate needs degree ≥ 2 by definition, so **exactly one of 1,715 can ever appear
inside a route** — `Q121923041` *Labyrint*, a Czech anthology joining Ray Bradbury and Robert A.
Heinlein, who already share six other intermediates. Every remaining edition can only ever be an
endpoint, and the amendment above already exempts endpoints on purpose.

So a demotion rule here — whether a `HUB_DEGREE` variant, a class entry beside
`RecognitionInstitutions`, or anything else — would be **a no-op against 1,714 of 1,715 nodes**.
That is the whole finding: the specificity dimension judges intermediates, and these are not
intermediates. Degree, which the first amendment introduced as the signal for a hub, identifies
these as the exact opposite of one.

### The other three options, and why none was taken

- **Filter at ingest.** Refused, and the measurement is what refuses it: ADR 36's `ORDER BY
  DESC(?sitelinks)` **already prices them last**. In Poe's neighbourhood — the worst case, 173 of
  334 — editions carry a median of **1** sitelink against **9** for everything else, and at
  `maxNewEdges` of 15, 50 or 100 **zero** editions survive the bound. They appear only at the
  default 200, where they take 30% of his slots. The lever that admits them is therefore the bound,
  not the vocabulary, and the bound is issue #71's open question. Filtering is also irreversible
  without a re-seed, which now costs about 36 minutes; spending that to hard-code a workaround for
  a number someone is actively re-deciding is the wrong order to do the work in.
- **Collapse onto the original via `P629`.** Refused on reach and on cost. It reaches **1,216 of
  1,715 = 70.9%**: 469 state no `P629` at all (105 of them under Darwin) and 30 more point at an
  original the graph does not hold. Paying for a new property, a merge rule and a decision about
  which of two provenances survives, to correct seven tenths of a leaf population, is ADR 36's own
  rejected alternative — suppressing inverse pairs at ingest — arriving again in a new costume and
  deserving the same answer.
- **Leave, and let the exporter filter for presentation.** The clutter it would address is real and
  measured (Darwin 63% of 275 neighbours, Poe 52% of 334), and it is still not built, for the
  reason the ingest filter is not: the size of a neighbourhood is what `maxNewEdges` controls. If
  #71 lowers the bound, the exporter filter would be dead code at the next re-seed.

**The decision is therefore to keep them and change nothing** — not in `PathRanking`, not in
`EdgeTypes`, not in the exporter. They are 1,715 of 54,448 nodes (3.1%) and 1,717 of ~61,630 edges
(2.8%), every one of them in the tail of an ordering that already sorts them there.

### Consequences of the third amendment

- **`find_paths` is unaffected by all of them, and this is structural rather than lucky.** One
  anthology node is the entire exposure, and it connects two authors who are already connected six
  other ways, so no route in the graph depends on an edition node.
- **The visible cost is admitted, not fixed.** A neighbourhood view of Darwin or Poe is about half
  editions at `maxNewEdges=200`. That is a real annoyance with a known cause and an owner (#71),
  and recording it here is what stops it being rediscovered as a vocabulary problem a third time.
- **A cheap test would not have pinned anything.** There is no code change, and the finding is a
  property of Wikidata's data rather than of this codebase — an offline fixture asserting that
  editions are leaves would only restate the fixture. The evidence lives here instead, which is
  what ADR 1 is for.
- **What would reopen it.** An edition node reaching degree ≥ 2 in numbers — anthologies and
  omnibus editions are the shape that does it, and one exists already. If the count of edition
  nodes that could be intermediates grows past a handful, the population stops being leaves and
  this amendment's central fact expires. Re-measure the degree distribution before reasoning from
  this again.

**Amendment (2026-08-28, issue #88): one measure for both kinds of hub was looked for on the real
graph, measured in both directions, and refused. The two rules stay; what grew was the class
table.**

Nothing above is withdrawn and no ordering changes. This records a generalisation that was
proposed as the prerequisite for issue #78 — a general interest vocabulary, P921 "main subject"
and P131 "located in" — attempted, and not built, because the general measure is worse than the
two special cases on their own acceptance cases.

The premise was reasonable and it is worth stating before the refutation. The two amendments above
are one idea in two costumes: **an intermediate that connects too many things explains nothing.**
The first approximates that with degree-plus-kind, the second with a class whitelist, and neither
was written for a vocabulary of aboutness and location. The recommender learned the same lesson
independently and got further (ADR 45): discounting a busy intermediate was not enough there, and
normalising by the *candidate's own degree* was the step that produced a list worth reading.

### The measurement

A copy of the live graph — 307,037 assertions, **123,752 nodes and 152,547 edges** — replayed
offline through the real `GraphProjector` into a `TinkerGraphStore`, with a gold set of 38
intermediates read out of that graph rather than imagined: **17 that must be demoted** (the awards
of issue #52, the institutions of issue #66, and the busy institutions found alongside them) and
**21 that must keep working** (The Clash, Guns N' Roses, Mötley Crüe, The Beatles, Kiss, Van Halen,
the Eagles, the Traveling Wilburys, Monty Python, the Bee Gees, The Who, six musicians and authors,
and the three works the earlier acceptance cases route through — CSI, the *Saturday Night Live 50th
Anniversary Special*, *The Great Buck Howard*).

A measure subsumes both rules only if some threshold on it puts all 17 above all 21. **Every
candidate overlaps**, and most of them wildly:

| measure | lowest must-demote | highest must-keep |
|---|---|---|
| degree, and every monotone function of it — inverse frequency, information content | 22 (Disney Legends) | **503** (David Bowie) |
| degree percentile within its own kind | 0.949 (SAG-AFTRA) | **1.000** (SNL 50th Anniversary Special) |
| dominant edge-type share — "it only ever does one thing" | 0.984 (Writers Guild of America, East) | **1.000** (The Great Buck Howard); Chopin 0.990 |
| local clustering coefficient — "its neighbours never meet elsewhere" | Disney Legends is the **most** clustered node in the gold set, at 0.0216 | CSI and *The Great Buck Howard* are at 0.0000 |
| share of neighbours that are a PERSON or a GROUP | 0.984 (SAG-AFTRA) | **1.000** (CSI, SNL 50th, *The Great Buck Howard*) |
| neighbour-kind concentration | 0.500 (Rock and Roll Hall of Fame) | **1.000** (SNL 50th) |
| degree over median neighbour degree | 0.14 (Disney Legends) | **503** (David Bowie) |

**The reason is one sentence, and it is why no eighth measure would have worked either. A film and
an award are the same shape.** *The Great Buck Howard* has five edges, all of one type, every
neighbour a person, no triangles. Disney Legends has twenty-two edges, all of one type, every
neighbour a person, no triangles. One is the film two people made together and the other is a list
of people who were famous, and **nothing in the graph's shape distinguishes them** — only what the
node IS, which is the kind in the first amendment and the stated class in the second. Hub-ness is
not a structural property that the vocabulary happens to express; it is a semantic property that
degree stands in for where the source said nothing we understood.

### The recommender's normalisation cannot be borrowed, and that is arithmetic rather than taste

Dividing by the candidate's own degree is what worked in ADR 45, and it has no analogue here.
Ranking compares routes **between one fixed pair**, so the degrees of both endpoints are the same
constant in every comparison it makes: any score normalised by them orders routes identically to
the unnormalised one. It is not a weak signal, it is provably inert.

That is the real difference between the two problems, and it is worth naming rather than
regretting. **Recommendation ranks candidates and can ask "is this one specific to me"** — the
candidate is the free variable, so its degree is information. **Routing ranks explanations and can
only ask "does this route mean anything"** — the only free variable is the intermediate, and
normalising the intermediate by itself says nothing.

### The strongest candidate, and both directions of what it cost

The measure that came closest was "a busy node that is not itself a collaboration and whose
neighbourhood is nothing but people": `kind != WORK`, degree ≥ `HUB_DEGREE`, and at least 90% of
neighbours a PERSON or a GROUP. It separates the gold set exactly — the exemption for WORK is what
buys it, since a film's cast is the colliding case above. Swept over every node in the real graph
against the two rules as they stood when the question was asked — three classes in the table:
**117 nodes both agree on, 125 flagged only by the new measure, 23 flagged only by the two rules.**
Re-swept against the table this amendment leaves behind, the gap only widens: 123, 119 and 43.

**What it wrongly demotes.** Metallica, at 14 edges, because the graph holds its members and not
its records. Immanuel Kant at 19, Aristotle, Joseph Conrad, James Joyce, Louis Pasteur, Niels Bohr,
Max Planck — people whose entire presence is `INFLUENCED_BY`, which is the one relation in the
vocabulary that states an artistic or intellectual debt and the one ADR 45 weights highest. And
some thirty bands in the same position as Metallica. Verified as routes, not as flags: *Weezer ↔
Judas Priest* leads today with **Metallica** and under the candidate with *Sum 41*.

**What it wrongly keeps.** Every institution below `HUB_DEGREE` that the class rule catches at any
size — the Académie Française at 3 edges, the Royal Society of Edinburgh at 4, the British Academy
at 2. Of the 94 nodes stating one of the three original classes, **63 carry fewer than ten edges**
and 21 of those carry between two and nine, which is to say they can be intermediates and this
measure would let all 21 through. It is the common case rather than the tail, and it grew with the
table: 167 nodes state a recognition class now, 126 of them below ten edges and 40 in the two-to-
nine band. Again as routes: *Henri Bergson ↔ the Comte de Buffon* leads today with "both influenced
by **Charles Darwin**" and under the candidate with "both members of the **Académie Française**";
*David Hume ↔ Georg Cantor* leads today with **Bertrand Russell** and under the candidate with the
**Royal Society of Edinburgh**. That is precisely the failure the second amendment exists to
prevent, arriving again.

A degree-only variant of the same idea — "a list of a hundred people is a hub, whatever it calls
itself" — was measured too, because it needs no class table at all. Every all-people node of 46
edges or more in the real graph is an institution or an award, so it works today; the nearest
false positive is at **41** edges and the next two at 37 and 33 are bands. A 12% margin between two
populations that both grow with the graph is not a threshold, it is a coincidence with a date on
it.

### The decision

**Keep both rules. `PathRanking` is unchanged by this amendment.** A general measure is only worth
having if it is at least as good on the cases the special cases were built for, and this one loses
21 institutions and demotes Metallica and Kant to gain about a dozen busy ones — most of which four
lines of table cover instead, at no cost to anything else.

### What the measurement found instead: the table had gone stale, and it always will

The same sweep showed the class table has already been outgrown once. Between issue #66 and this
amendment the graph went from 54,448 nodes to 123,752, and four classes arrived carrying
institutions that **neither** rule could see — including a hall of fame at 500 edges that today's
ranking actively prefers, because every competing route through an academy is marked as a hub and
it is not. Four entries added to `RecognitionInstitutions`, each measured here and confirmed
against Wikidata by label AND description:

| class | means | worn by, in edges |
|---|---|---|
| `Q1046088` | hall of fame | National Inventors Hall of Fame 500, Grammy Hall of Fame 38 |
| `Q829080` | professional association | Polish Writers' Union 408, American Psychological Association 181 |
| `Q748019` | scientific society | American Astronomical Society 179, Zoological Society of London 152 |
| `Q12057459` | writers union | PEN America 76, Authors Guild 30 |

`Q1046088` is the loose end the second amendment recorded and declined to pull: "three award nodes
in the graph are classified `GROUP` rather than `CONCEPT`… adding `award` to this table is a
different decision". It still is — **`Q618779` award and `Q11448906` science award stay out**,
because ADR 38 registered `P166` precisely so a single-authored novel could route through the prize
it won, and those two classes are worn by the Hugo, the Darwin Medal and the Balzan Prize. A hall
of fame is a list of the notable; a Hugo is a fact about one book. `Q12057459` has no English
description at all, so it was confirmed instead by its aliases ("writers' guild", "writers'
association") and by its being a subclass of `Q829080`, which is in the table above it.

**The near-miss is the part worth keeping.** `Q45400320` looked like the missing society class:
every node in the real graph that states it is an academy — the Royal Society, the Romanian
Academy, the Polish Academy of Sciences. It means **open-access publisher**, and the academies wear
it because they publish. A table fitted to its population rather than to its meaning would have
taken it and would then have demoted a route through anything else that publishes. That is the trap
the second amendment named for `Q43229` and `Q163740`, in a costume that fools a sweep instead of a
guess. `RecognitionInstitutionsTest` now fences the three publisher classes and the two award
classes by name.

**And the table will never be complete, which is measured rather than conceded.** The American
Association for the Advancement of Science and the Polish Academy of Learning both carry 500 edges
and state nothing but publisher and organization classes; the Royal Society of Arts states
`Q163740` and nothing else. No safe entry reaches them and no measure above separates them.
**Hub demotion is partial by construction** — worth knowing before anything is built on the
assumption that it is total.

### What this means for issue #78, which asked the question

- **Aboutness is already covered, and by the rule that exists.** A P921 subject node is a `CONCEPT`
  and is busy by construction, which is the exact shape issue #52 measured on awards. The rule
  judges the intermediate rather than the relation, so registering the property changes nothing
  about it. Demonstrated on a synthetic hub-rich vocabulary in `PathRankingTest` rather than waited
  for.
- **Location is not covered by anything.** "Both are in New York" routes through a `PLACE`: the
  degree rule is `CONCEPT`-only on purpose and a city states no class meaning "elected to". #78
  needs a third rule and should stop expecting a general one.
- **That third rule belongs with the property that creates the need, not here.** The graph holds
  exactly one `PLACE` — New York City, at a single edge — and six `EVENT`s that could be an
  intermediate at all, because nothing in the vocabulary relates anything to a place. Widening the
  degree rule today would be a no-op against everything that exists, which is the argument the
  third amendment used to refuse a rule for edition nodes. The gap is pinned by a test instead, so
  the day P131 is registered the failure is loud.

### Consequences of the fourth amendment

- **`find_paths` ordering is unchanged except where the four new classes bite**, and there it moves
  the way the second amendment's did: an institution route stops leading. Nothing that was demoted
  is promoted, and every acceptance case from both earlier amendments was re-run on the replayed
  copy and is untouched — Gottfried ↔ Seyfried through CSI, Hanks ↔ O'Brien through *The Great Buck
  Howard*, Darwin ↔ Vonnegut through Thoreau, Cheap Trick ↔ Scorpions through Mötley Crüe, Red Hot
  Chili Peppers ↔ Nine Inch Nails through The Clash, the Rolling Stones ↔ Counting Crows through
  Guns N' Roses. Huston ↔ Arthur, the pair the first amendment was written for, still leads at four
  hops with two specific acting awards bridged by a person who won both, and the Walk of Fame route
  is still below it.
- **Measured on the eight institutions the new classes catch: of 785 contested pairs among their
  neighbours, 285 lead with a different route and 74 of those now lead with a hub-free one** —
  Darwin ↔ Haeckel leaves the Zoological Society of London for a shared influence, Updike ↔ Auster
  leaves PEN America for Jonathan Lethem. The other 211 swap one institution for another, because
  in those neighbourhoods every route is an institution route; ranking has nothing better to offer
  and says so by returning them anyway.
- **The maintenance cost is now explicit.** The class table is the mechanism, so it has to be fed
  from measurement as the graph grows, and the growth path is `KindMapper`'s. Re-measure both this
  and `HUB_DEGREE` together — they drift for the same reason.
- **The negative result is the deliverable.** Nothing in `domain` changed, so nothing pins it in
  code except the gold-set numbers above; that is what this ADR is for. Do not re-open it with a
  measure that was not run against the 21 must-keep nodes as well as the 17 must-demote ones — the
  candidate above passes any test built only from the hubs.
- **What would reopen it.** A source that states *why* a node exists rather than what class it is —
  or a Wikidata property that separates "was elected to" from "worked with", which `P463` does not.
  Either would give the semantic signal the graph's shape is standing in for, and this whole
  amendment is an argument that only a semantic signal can do the job.

## Alternatives considered

- **Rank inside each adapter** — no port change, and it duplicates the comparator in two
  implementations that must then be kept identical, which the contract tests would have
  to police forever.
- **Keep `limit` in the port and sort what comes back** — smallest diff, and it ranks a
  set the adapter has already truncated, which is the bug rather than a fix for it.
- **A `rank` parameter on `find_paths`** — flexible, and it pushes a question with a right
  answer onto the model at call time and widens the tool surface ADR 26 keeps narrow.
- **A combined score mixing length and confidence** — plausible, and it invents a weighting
  nobody can justify, where the lexicographic rule states the intent exactly.

## Consequences

- The most trustworthy explanation is presented first, which is what makes the payoff
  feature honest.
- The comparator lives in one place and both engines are held to it by the contract tests.
- Returning all routes before ranking costs more memory than truncating early. Bounded by
  `maxHops` and the internal cap, and acceptable at personal scale.
- A long, fully sourced route now outranks a short guess. That is the intent, and it will
  occasionally surprise, so `find_paths` results show per-hop citations that explain the order.

---

*Correction, 2026-08-24: the Context section originally named John Hillcoat as the
endpoint of the low-confidence shortcut. The fixture's model-generated shortcut is
Cave→McCarthy; all Cave→Hillcoat routes are wikidata-sourced at confidence 1.00.
The decision is unaffected.*
