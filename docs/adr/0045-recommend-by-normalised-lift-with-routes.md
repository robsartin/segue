---
status: Accepted
date: "2026-08-28"
topic: recommend-by-normalised-lift-with-routes
tags: [project, domain, tooling, privacy, graph]
supersedes: []
related: [taste-layer-separation, path-ranking-by-confidence, mcp-tool-surface, affinity-capture-and-read, bulk-seeding-as-a-dev-tool, graph-exporter-views-and-formats, listing-your-own-ratings, retraction-as-a-new-claim, layering-and-archunit, privacy-and-data-handling]
---
# 45. Recommend by candidate-degree-normalised lift, explain with real routes, from a fifth dev-side tool

## Context

ADR 33's stated payoff — *"recommendations are derived by traversing the world graph and filtering
through affinity"* — was unbuilt. It is the feature the project exists for, and the goal is
specific: **things NOT already on the list**.

The ingredients were all present. A membership oracle (ADR 40's mapping file is the list of things
already known), a graph that knows what those things reach, and two hub rules — ADR 31's
`CONCEPT`-degree rule (issue #52) and `RecognitionInstitutions` (issue #66).

**The design is not a guess.** A scoring experiment was run against the live graph — 123,752 nodes,
152,547 merged edges, 815 known entities — before any of this was written, and everything below
either follows a measurement or says which one it is departing from.

| scorer | formula | what it surfaced |
|---|---|---|
| raw count | `Σ seedcount(z)` | McCartney, Lennon, Kiss — **fame** |
| Adamic-Adar | `Σ seedcount(z)/log(deg z)` | still McCartney; a band member appears |
| resource allocation | `Σ seedcount(z)/deg z` | band members with long discographies |
| **lift** | `AA / deg(candidate)`, degree ≥ 12 | the influence ancestors of the list |

Four findings came out of it, and they are the whole design:

**Raw connection count rediscovers fame.** It is a ranking of the graph, not of the person.

**Discounting the busy INTERMEDIATE is not enough.** A candidate connected to everything shares its
intermediates with everything, so Adamic-Adar still returned the most famous names in the graph.
Dividing by the **candidate's own degree** is what turns popularity into surprise: "connected to me
more than its size predicts".

**A degree floor is required under that normalisation.** Without one, the normalised score rewards
whatever is smallest, and cosine put a degree-2 node at the top.

**Hub intermediates must be excluded, not discounted.** The Rock and Roll Hall of Fame at degree 64
leaked into the results through pure discounting, producing `Rage Against the Machine
-RECEIVED_AWARD- Rock and Roll Hall of Fame -RECEIVED_AWARD- The Flamingos`. Meanwhile
`INFLUENCED_BY` produced the chains that actually said something: `The Beatles -INFLUENCED_BY-
Little Richard -INFLUENCED_BY- Sister Rosetta Tharpe`.

**Plain PageRank is the wrong tool** — it measures global importance, which is the signal to escape.
Personalised PageRank is the right family and handles multiple hops natively, and it stays
degree-biased without exactly this normalisation and, decisively, **it does not explain itself**. A
score is not a route.

## Decision

### A fifth dev-side tool, `./gradlew recommend`. Still six MCP tools.

Plain Java, a `main` behind a `JavaExec`, the shape ADR 40 gave `resolveNames`, ADR 41 gave
`exportGraph`, ADR 43 gave `listRatings` and ADR 44 gave `retractEntity`.

**This one deserved a real argument, because the precedent does not settle it.** Drawing a picture,
resolving nine hundred names, listing ratings and retracting an entity are all operator's jobs that
nobody would want to do conversationally. *"What should I explore next?"* is not like them: it is
exactly the kind of question one would want to ask in a conversation, and answering it in prose,
with the routes, is what a model is good at.

It is still not a tool, and the reason is what the question needs rather than what it sounds like:

- **The input is a file naming everything you already know.** That file is the personal data ADR 33
  governs and ADR 40 keeps out of this public repository. Handing a model a path to it — and,
  through the answer, its contents — is precisely what ADR 40 refused when it declined an
  `import_list` tool. Nothing about the question changes that.
- **The obvious alternative input is worse.** "Use the taste layer as the known-list" is the version
  that needs no file at all, and it needs `AffinityStore.readAll` — the bulk read ADR 39 declined on
  ADR 16's data minimisation and ADR 43 reserved to a tool that runs on the owner's own machine.
  Reading it one qid at a time is that same read spelled slowly.
- **The output is a file.** 25 candidates with three routes each is 150 cited hops; it is something
  read once and kept, next to the exporter's picture, not a conversational reply.

So both shapes of a seventh tool need something the surface has already turned down twice, and ADR
26 is unamended. **What this decision does add is a re-open condition**, because the case is
genuinely stronger than any of the four before it: if the taste layer fills up and a *bounded*
version of this question — "given these five things I have rated, what next?" — is wanted in
conversation, that is an argument on its own terms. It amends ADR 26 and re-argues ADR 39. It does
not arrive as a field on an existing tool.

### Two hops out from the known-list, and hub intermediates are excluded rather than discounted.

`CandidateSweep` walks one hop out from every known entity, refuses the intermediates that are hubs,
and walks one hop further. A candidate is a `PERSON` or a `GROUP`, absent from the known-list, not
itself a recognition institution, and at or above the degree floor.

**The hub judgement is borrowed, not rebuilt.** `PathRanking.isHub` is now public and both callers
use it: routing demotes a route through a hub, and recommending excludes one. Two readings of one
rule, one implementation — a second copy would let a hall of fame back into recommendations while
routing kept excluding it. **Excluded and not demoted**, because the two verbs are answering
different questions: "what connects me to the Rock and Roll Hall of Fame" is a question with an
answer, and "you should listen to this because you were both inducted" is not a recommendation at
all. On the real run, **116 intermediates were excluded**.

The candidate filter is the same rule from the other end. The raw query put the American Academy of
Arts and Sciences first — it connects 33 of the 815 known entities, all by `MEMBER_OF` — and a
recommender without that filter suggests joining a learned society.

### The scorer is a dial: raw → Adamic-Adar → resource allocation → lift, defaulting to lift.

One formula with two knobs (`Scorer`): how much to discount the intermediate, and whether to divide
by the candidate's own degree. `--scorer` picks the point.

**A dial rather than a constant because the failure at each end is real and domain-dependent**, and
because seeing them side by side in one run is the fastest way to understand what the normalisation
does. Re-measured on the real graph on the day this landed, with everything else held equal:

| `--scorer` | top of the list |
|---|---|
| `raw` | Kiss, Guns N' Roses, The Clash, The Who — fame, exactly as before |
| `resource-allocation` | McCartney, Lennon, Sinatra, then **Martin Gore, reached by ONE known entity through 102 shared intermediates** |
| `lift` (default) | The Stooges, MC5, New York Dolls, Marc Bolan, Black Flag — the list's own ancestry |

The resource-allocation row is worth reading twice: the report's own "N of yours through M shared
intermediates" column is what makes that failure visible, which is why the column is in the header
of every candidate.

### A degree floor, defaulting to 12, and `--min-degree` to move it.

Required, not optional: a normalised score divides by the candidate's degree, so without a floor the
answer is whatever is smallest. Twelve is the experiment's value and it survived re-measurement — at
a floor of 50 the list drifts back towards the famous names the normalisation exists to escape, and
below twelve it fills with entities whose entire presence in the graph is a list of influences.

**It is a default on this graph, not a constant.** It is an absolute degree on a personal-scale
graph and will drift as the graph grows, the same caveat `PathRanking.HUB_DEGREE` carries. Re-measure
before changing it, and prefer running two floors and reading both lists to arguing about one.

### Edge types are weighted, in three tiers, and the tiers are what is measured.

`RecommendationWeights`, in `domain` beside `EdgeTypes` because it keys on this vocabulary's own
codes:

| tier | types | weight |
|---|---|---|
| influence | `INFLUENCED_BY` | 1.0 |
| collaboration | `MEMBER_OF`, `PERFORMED`, `ACTED_IN`, `AUTHORED`, `COMPOSED_FOR`, `DIRECTED`, … | 0.5 |
| recognition | `RECEIVED_AWARD` | 0.2 |

Every type is legitimate for *routing*. They are not equal for *recommending*, and the measurements
behind the ordering are these:

- **`INFLUENCED_BY` is the only relation in the vocabulary that states an artistic debt**, and the
  only one stated *about* the pair rather than about a job or a prize. It is also where the degree
  arithmetic has the most work to do: over the first hop out of the 815 known entities, an influence
  intermediate has a **median degree of 51** against 1 to 5 for every other type, because what
  artists cite is a famous artist. After hub exclusion it already carries 60% of surviving route
  ends, and influence-to-influence is 53% of all two-hop routes.
- **Halving collaboration is what dissolved the co-membership artefact.** With every type equal, the
  top of the lift ranking was a band member reached through **28 separate songs by one group** — one
  fact about that group, counted 28 times. At half a unit per hop, and so a quarter per route, he
  leaves the top twenty entirely. That is why there is no second "reached by at least N of your
  things" filter: it was considered, and the weights made it unnecessary.
- **`RECEIVED_AWARD` at a fifth, and deliberately not zero.** A shared award says both parties were
  recognised by the same body, which is a fact about institutions. But ADR 38 admitted P166 exactly
  because a novel has one author and there is no collaboration to find, so zeroing it would blind
  the recommender to the half of the graph ADR 38 was written for. The weight and the hub rule both
  have work left after the other has run: hub exclusion removes **38% of the award hops out of the
  known-list** (1,006 of 2,664), and this weighs the specific awards that survive.

**The numbers are one significant figure and the ADR says so.** What is measured is the *order*;
1.0, 0.5 and 0.2 are the coarsest numbers that express it. Anything more precise would be a tuning
claim nothing here can evaluate, because there is no held-out set of recommendations anybody has
agreed with. A new relation type has to be weighed deliberately —
`RecommendationWeightsTest.everyRegisteredTypeIsNamed` fails the build if the table has not been
told about it.

### Every candidate is explained by its actual routes, from the real traversal.

`Routes` takes the known entities that contributed most to a candidate's score, asks
`GraphStore.paths` for the routes, ranks them with the shared `PathRanking` and renders them with
`PathResult.render()` — the same three things `find_paths` does, in the same order, so a
recommendation's receipts cannot drift from the project's one notion of a good route. Each route is
prefixed with which of your entities it starts from, because a rendered hop reads in whichever
direction the source stated it.

This is not decoration and it changed the output more than the arithmetic did. The issue that
started this asked whether `Timothy Davlin`, reached by 11 known entities, was a surprising
connector or a data defect. **The routes answer it**: at rank 85 of 1,114, that entity is reached
through 13 intermediates, every one of them a stand-up comedian and every edge an `INFLUENCED_BY`.
Neither guess was right, and no count could have said so.

Explanations are built only for the ranked and bounded list. Doing it for all 1,114 candidates would
be a thousand traversals thrown away.

### The affinity seam is present, obvious, and wired to nothing.

`Recommendations.EQUAL_REGARD` is a `ToDoubleFunction<String>` over a known entity's qid, returning
1.0; `CandidateSweep` multiplies every connection by it. A candidate reached by three things rated 5
outranking one reached by six rated 2 is a matter of supplying a different function.

**It is a function and not a store, and an ArchUnit rule keeps it that way.**
`theRecommenderNeverReadsTheTasteLayer` forbids this package from depending on `AffinityStore` — the
*type*, not the two methods — so the recommender cannot see a rating at all. That is stronger than
it needs to be today and exactly as strong as it needs to be tomorrow: `find` is available
everywhere else in the project, so a well-meaning change could give this tool one rating at a time
and call it the affinity weighting, which is the bulk read spelled slowly. Building the real
weighting changes that rule, ADR 39 and this ADR together. `affinity` currently holds zero rows, and
designing around data that does not exist is what this deliberately does not do.

### The output is a file, it names itself as personal data, and `--out` has no default.

A recommendation list is the known-list plus what the graph makes of it, so it is personal data
under ADR 33 and issue #37. ADR 30 makes SLF4J the only logging API and `nothingWritesToStandardOut`
forbids `System.out` project-wide, so the whole listing goes to the operator's chosen path and every
log line is a count or a path. `RecommendationsAreNeverLoggedTest` drives the real `main` with a
Logback appender attached and asserts that no line anywhere carries a label or a qid: **since no
line names an entity, no line can say what anybody listens to.** `*.txt` was already gitignored for
ADR 43, and the file's first line is the third lock, aimed at the copy that leaves the machine.

### `QidList` moves to `support`, because two tools now read the same file.

The exporter's `subgraph` view and this tool's known-list are the same file shape — the first
comma-separated field on a line that *is* a QID, so ADR 40's mapping file and a hand-typed list both
work — asking two different questions of it. The tools may not depend on each other (each carries
its own ArchUnit fence, and a dependency on a sibling would let one inherit the other's), so the
reader moves to a package neither of them owns rather than being copied. Behaviour is unchanged.

**Amendment (2026-08-27, issue #84): direction is read, on the candidate's own hop and nowhere
else.**

Nothing above is withdrawn, and the consequence below that called this "a data question rather than
a scoring one" was wrong: it is a scoring question, and the graph already held everything needed to
answer it. From the run this ADR shipped with, ranks 1 and 3:

```
1. SR-71        U2      <-[INFLUENCED_BY]-  SR-71        SR-71 claims U2
3. Marc Bolan   Pixies   -[INFLUENCED_BY]-> Marc Bolan   Pixies cite Bolan
```

**Every SR-71 arrow points outward and every Bolan arrow points inward, and an undirected walk
cannot tell them apart** — both "share intermediates with things you like". SR-71 then wins on lift,
because its own degree is smaller. Counted on the graph: SR-71 cites 10 entities and is cited by 1;
Marc Bolan cites 0 and is cited by 6.

**Being cited by something you like is a fact somebody else stated about the candidate. Citing
something you like is a fact the candidate stated about itself.** Both are true and only one is
evidence, so:

- **A hop the candidate is the subject of is worth a fifth of the same hop stated about it.**
  `RecommendationWeights.asEvidenceAbout`, and `SELF_STATED` is 0.2 — the same figure `RECOGNITION`
  carries, for a related reason: strip the direction out and what is left is somebody's paperwork.
- **Demoted, not excluded.** The hub rule excludes because "you were both inducted" is not a
  recommendation at all; this one does not, because "who came from the things you like" *is* a
  segue — it is simply the one that says least about whether to go and listen. A candidate whose
  every arrow points outward is still in the file, and still carries its routes.
- **Only the candidate's own hop is asked.** The hop out of one of your entities is left alone, and
  that is the load-bearing half. The entities that cite your list are the same entities that cite
  its ancestors — `Pixies -> Marc Bolan` is reached through `Pixies -> The Beatles`, an outward arrow
  from a band that is not being recommended — so discounting the first hop by direction would demote
  exactly the ancestors this exists to keep. `CandidateSweepTest.directionIsAskedOnlyOfTheCandidatesOwnHop`
  is the regression test, and the `Weighing` enum is the parameter that says which question is
  being asked of which hop.

### Which relations carry a direction of esteem, one at a time

Direction is a **separate dimension from the tier** and it lives in the same table row, because
neither is derivable from the other: `BASED_ON` and `MEMBER_OF` are both collaborations and only one
of them states a debt, while `INFLUENCED_BY` and `BASED_ON` are both debts in different tiers. It is
NOT a fact about the vocabulary and does not belong on `EdgeType`: the traversal stays undirected
everywhere else in segue, exactly as the alternative below says, and this is a *recommendation*
policy rather than a change to what the graph believes.

| relation | direction of esteem? | why |
|---|---|---|
| `INFLUENCED_BY` | **yes** | the one relation stating an artistic debt between two entities either of which could be a recommendation. The whole of this amendment |
| `BASED_ON` | **yes** | the same debt, work to work — the later work defers to the earlier one. It changes nothing today because a `WORK` is never a candidate; it is stated so the exception is deliberate rather than unnoticed |
| `MEMBER_OF`, `HAS_PART` | no | which end is the person and which the group is a fact about kinds. A band does not defer to its drummer |
| `PERFORMED`, `AUTHORED`, `DIRECTED`, `WROTE_SCREENPLAY_FOR`, `COMPOSED_FOR`, `ACTED_IN` | no | every one is inverted at ingest so it reads person-to-work (ADR 22). That direction is the convention, not regard; two people credited on one film are symmetric |
| `PART_OF` | no | containment. A song is not deferring to its album |
| `RECEIVED_AWARD` | no | the direction separates a person from a prize, which the hub rule has already dealt with. Nobody is flattered by being an award |
| `COLLABORATED_WITH`, `SIMILAR_TO` | no | the vocabulary declares both SYMMETRIC, so no direction could be read off them |

`RecommendationWeightsTest.theVocabularysDebtRelationsAreTheOnlyDirectionalOnes` pins that table, so
a new relation costs both decisions rather than inheriting the quiet one.

### The measurement, on the real graph, before and after

Same 123,752-node copy, same 815-entity known-list, same `lift` and same floor of 12; only the
arrows are read. Top ten:

| # | before | after |
|---|---|---|
| 1 | SR-71 — 1.2393 | Metallica — 1.0820 |
| 2 | Metallica — 1.1712 | Marc Bolan — 1.0149 |
| 3 | Marc Bolan — 1.0149 | MC5 — 0.9621 |
| 4 | Cartel — 0.9825 | New York Dolls — 0.7590 |
| 5 | MC5 — 0.9621 | Free — 0.5472 |
| 6 | Anarbor — 0.8197 | Redd Foxx — 0.5085 |
| 7 | Tonic — 0.7666 | The Stooges — 0.4918 |
| 8 | New York Dolls — 0.7590 | The Fugs — 0.4745 |
| 9 | The Stooges — 0.6627 | Lenny Bruce — 0.4714 |
| 10 | Third Eye Blind — 0.6162 | Dick Dale — 0.4657 |

**Of the top 25, the number that cite more entities than cite them went from 18 to 2.** The thin
items fell out of the page and kept their receipts: SR-71 1 → 24, Cartel 4 → 76, Anarbor 6 → 88, The
Witty Featherstones 12 → 141, La Ludwig Band 15 → 191, and four more left the top 200 entirely.
Five candidates in the old top 25 cite nobody at all — Marc Bolan, MC5, New York Dolls, Free and The
Fugs, with Mott the Hoople just below it at 34 — and every one of the six scores **identically**
before and after. That is the clearest possible statement of what the change touches: their arrows
were already all inbound, so nothing about them moved except everything that had been above them.

**One named ancestor did fall, and it is the honest cost of the rule.** Black Flag went 22 → 65, and
the reason is in the counts rather than in the arithmetic: in this graph it cites 19 entities and is
cited by 9, so two thirds of its presence is its own influence list, and its own routes into the
known-list are the outward ones. The Stooges is the same shape (cites 21, cited by 19) and survives
at 7 because its inbound half reaches further. **No multiplier separates them from SR-71 by rank
alone** — at 0.5 SR-71 returns to rank 2, and at 0.1 both Black Flag and SR-71 leave together — so
0.2 is where this sits, and Black Flag falling out of the first page is recorded rather than tuned
away.

## Alternatives considered

- **A seventh MCP tool** — argued above at length, and the strongest case any of the five dev-side
  tools has had. Refused on what the question needs: a file of everything you already know, or the
  bulk taste-layer read ADR 39 declined. A re-open condition is stated rather than the door being
  shut.
- **Personalised PageRank from the known-list** — the right family, multi-hop for free, and a
  standard answer to this exact problem. Refused for two reasons and the second is decisive: it
  stays degree-biased without the same normalisation applied on top, and it does not explain itself.
  Segue's premise is that "you like this because" is citable; a stationary distribution is not a
  route. If it is ever wanted, it has to arrive with a way to produce the routes.
- **Discounting hub intermediates instead of excluding them** — one mechanism instead of two, and it
  is what the experiment did. Measured: the hall of fame came back anyway, because being connected
  to everything survives a logarithm.
- **A "reached by at least N of your things" filter** — the obvious fix for the co-membership
  artefact, and it was measured: at the default weights the top twenty is unchanged by any value of
  N from 1 to 5, because the collaboration weight already handles it. Speculative structure ahead of
  a need.
- **One hop rather than two** — what the issue's first query did, and it works: it is the ranking
  that produced Guns N' Roses and The Clash. It cannot produce an ancestor, though, which is the
  interesting half — `The Beatles → Little Richard → Sister Rosetta Tharpe` is two hops by
  construction.
- **Directed influence, so "cites" and "is cited by" are different edges** — the top of the list
  mixes the list's ancestors (The Stooges, MC5, New York Dolls) with its descendants (small modern
  bands whose Wikidata item cites your acts), and direction is what separates them. Refused for now
  because the traversal is undirected everywhere else in segue — `Hop.traversedBackwards` records
  the direction rather than forbidding it — and because both are real answers: "who did the things I
  like come from" and "who came from them" are both segues. If it is built it is a new dimension for
  the whole path layer, not a special case here. **Half taken (2026-08-27, issue #84): the traversal
  is still undirected and no edge was split in two, but the SCORE now reads the arrow on the hop
  that touches the candidate. See the issue-#84 amendment above — "both are real answers" is why it
  demotes rather than excludes.**
- **Storing recommendations in the graph or the log** — they are derived, they change every time the
  graph does, and ADR 19 keeps the log for what sources said. A snapshot file, like the exporter's,
  is the honest artefact.
- **Ranking works rather than people** — a `WORK` is what a connection is *made of* here; being
  pointed at an album by a band you already know is not a recommendation. `PERSON` and `GROUP` are
  the kinds you can go and explore.

## Consequences

- **The list is not a fame ranking, and it is not uniformly good either.** From the real run: the
  genuinely useful half is the list's own ancestry — The Stooges, MC5, New York Dolls, Marc Bolan,
  Black Flag, Mott the Hoople, Free, The Fugs. Beside them sit thin, recently-created Wikidata items
  whose whole presence in the graph is an influence list naming acts on the known-list. They are not
  errors — the routes are real and cited — but they are the least interesting true answer, and they
  are what the degree floor is trading against. Raising `--min-degree` to 25 removes most of them and
  costs Marc Bolan and MC5. Both lists are in the tool; neither is hidden.
- **Sorting that out is the next question, and it is a data question rather than a scoring one.**
  A thin item citing twelve famous bands and a genuine ancestor cited by twelve famous bands look
  identical to an undirected two-hop walk. The direction alternative above is the honest fix.
  **~~A data question~~ — wrong, and corrected by the issue-#84 amendment above (2026-08-27). The
  graph already stored the arrow and the receipts already printed it; nothing but the score was
  ignoring it. Of the top 25, the items citing more than they are cited went from 18 to 2, and the
  ancestors that cite nobody did not move at all.**
- **The floor and the hub degree both drift as the graph grows**, in opposite directions, and
  nothing re-measures them automatically. A threshold nobody re-measures is a blocklist with extra
  steps.
- **A run is a snapshot**, deliberately, like the exporter's and the ratings tool's: 16 seconds
  against the real graph, including the 307,037-assertion replay. Re-run it to see a change.
- **Five dev-side tools now, and five `--db` defaults stated in Java.** The number is getting hard to
  defend on its own; what still defends it is that each has a different relationship to the data and
  an ArchUnit rule that says which — `seed` may not open a store, `export` and `ratings` may read
  one, `retract` may append one kind of claim, and this may read everything and write nothing.
- **`PathRanking.isHub` is public API now.** It was private and is the same code; what changed is
  that two features depend on the same sentence, which is the point. Changing the hub rule now
  changes both, deliberately.
- **Nothing here reads `~/.segue/segue.db` during `./gradlew check`.** Every test runs against an
  invented graph in memory or in a `@TempDir`, and every name, QID and route in the suite and in
  this document that is not a real Wikidata entity's is made up. The real-graph figures quoted above
  are counts and rankings, produced against a copy, with no known-list content in them.
