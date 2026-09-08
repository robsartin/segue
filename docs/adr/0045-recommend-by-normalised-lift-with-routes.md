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
  *(Amended 2026-08-28, issue #85: this bullet is weaker than it was, and it is worth saying so
  rather than letting it stand as if nothing had changed. A rating-shaped known-list would now need
  `readRatings`, which carries no note and no longer offends ADR 16 the way `readAll` does. **The
  first bullet is the one still doing the work**: the input this tool actually takes is ADR 40's
  file of everything the owner already knows, that file is personal data ADR 40 kept away from a
  model, and issue #85 did not touch it. The seventh-tool question stays where ADR 45 left it — and
  issue #85 deliberately declined to reopen it, which is why `recommend` is still a Gradle task.)*
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

*(Amended 2026-08-28, issue #85. **It is wired now.** ADR 33 split the taste layer — the score is
ordinary data, the note is not — and this section is what that unblocked. `RecommendCli` opens the
affinity store, calls the note-free `AffinityStore.readRatings`, and passes
`Recommendations.regardFor(ratings)` into `RecommendRun`; everything below that still takes regard
as a `ToDoubleFunction<String>`, so the seam described below is unchanged in shape and only its
argument has changed.*

***The weighting is centred on the middle of the scale, not proportional to it.** `regardFor` gives
a rating of 3 a weight of 1.0, a 5 a weight of 5/3 and a 1 a weight of 1/3, and an entity with no
rating counts as a 3. That last part is the decision: most of the known-list is unrated, because it
came from ADR 40's file rather than from the taste layer, and a weighting proportional to the raw
rating would push every unrated entity to the bottom the moment the first rating was written. An
empty `affinity` table therefore produces exactly the ranking measured above, weight for weight.*

***The rule that guarded this is narrowed, not removed.** `theRecommenderNeverReadsTheTasteLayer`
banned `AffinityStore` as a type; `theRecommenderReadsRatingsAndNeverNotes` bans `AffinityRecord`
as a type and `find` and `readAll` as calls, which is the same instinct pointed at the half that
still needs it. The old rule's argument — that 800 single-qid `find` calls are a bulk read spelled
slowly — survives literally: `find` is exactly what it forbids, and the one method left returns a
`Map<String, Integer>` that cannot carry a note however it is used.*

***Untested against real ratings, and the ADR says so.** The `affinity` table still held zero rows
the day this landed. `AffinityWeightedRecommendationTest` builds a scratch database with invented
ratings — three entities at 5 reaching one candidate, six at 2 reaching another, both candidates
padded to the same degree — and drives the real `main` twice: without ratings the crowded candidate
wins, with them the loved one does. That proves the wiring and the arithmetic. It does not prove
that 5/3 is the right strength on a real taste layer, and the way to learn that is the way the
degree floor was chosen: run two and read both lists.)*

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

**Amendment (2026-08-29, issue #115): this ADR predates the rule about what an ADR may quote, and
it publishes the strongest of the three exposures that rule was written for.**

Nothing above is withdrawn, no decision changes and no sentence above is edited.
[ADR 51](0051-what-an-adr-may-quote.md), decided today, says an ADR may publish an aggregate over
the owner's data but may not present an entity name as his taste, his holdings, or a tool's output
over them. This document does the third of those, and it would not be written this way now.

**What is exposed: a ranked list, which is a taste profile.** The `--scorer` comparison table and
the issue-#84 amendment's before-and-after top ten are **the recommender's actual output for this
owner's known-list**, with scores. Every candidate is there because of what it connects to on that
list, so the ranking describes the list even though nothing named in it is on the list. ADR 33's
issue-#85 amendment already drew this conclusion in general terms — it calls the known-list "a
statement of taste, handed to a tool and, through its output, to whoever reads it." This document
is that output, and the sentence was written before anybody noticed it applied here.

**The last consequence above reads too narrowly, and is qualified rather than withdrawn.** It says
the real-graph figures are "counts and rankings, produced against a copy, with no known-list content
in them." The counts are exactly that and the claim holds for them. **A ranking is different**: it
is derived from the known-list, which makes it known-list content at one remove, and that is the
whole of what ADR 51 adds to `CLAUDE.md`'s existing rule about ratings and notes.

**Why the names stay, and this is the weaker case of the two ADRs amended today.** They are
illustrative rather than load-bearing. The argument here is that counting connections rediscovers
fame and only normalising by the candidate's own degree finds surprise; the *scores* carry that,
and the issue-#84 amendment's headline — of the top 25, the items citing more than they are cited
went from 18 to 2 — is an aggregate that survives with every name removed. Unlike ADR 31's degree
collision, nothing here would become uncheckable. **This amendment does not claim the names are
needed.** They stay for the reason below and because ADR 1 makes this text immutable, not because
the argument depends on them.

**Redaction would not un-publish.** This repository is public and was created on 2026-08-24; the
scorer table has been on `main` since 2026-08-27 and the before-and-after ten since 2026-08-28,
each through a merged pull request. Git history retains what an edit removes and GitHub keeps its
pull-request refs indefinitely — the lesson this project already recorded about commit email
addresses, where a force-push does not reach them either. An edit would break immutability in
exchange for a false impression that the content had gone.

**What the rule changes going forward.** The same comparison would be published with the scores and
the aggregate and without the names, or with invented ones beside a note that they are invented.

**Amendment (2026-08-29, issues #117 and #118): the degree floor defaults to five, not twelve, and
this ADR argued only the floor's benefit — the cost is recorded here beside it.**

Nothing above is withdrawn, no decision above is edited, and the floor's *reason* is unchanged: a
normalised score divides by the candidate's own degree, so without a floor the answer is whatever is
smallest. What changes is the number, and what is added is the half of the trade this document never
stated.

`Recommendations.MIN_CANDIDATE_DEGREE` is the authority and it is now five. `--min-degree` remains
the dial, exactly as this ADR made it, and both refusals below two are untouched — `RecommendCli`
and `RateCli` each hold their own `LOWEST_USEFUL_FLOOR`. Both `recommend` and `rate` read the
constant by reference, so the two tools still agree at their defaults.

### Why five, measured

**The section above says twelve "survived re-measurement", and the re-measurement it survived asked
only whether the list looked famous.** Issue #118 asked a different question — whether the entities
the floor *excludes* are obscure or merely unfetched — and ran the same known-list at three floors,
which needed no code because the flag already existed. The floor-5 list was not the thin noise this
ADR predicted; it was recognisable acts sitting at low degree because segue had not expanded them.

The decisive evidence is not a ranking at all, it is a rating distribution. Issue #119 let the deck
deal at a lower floor, and one 177-card pass at floor 5 produced **72 cards below neutral — 41%**,
against **8 of 973 — 0.8%** across every rating that preceded it. [ADR 50](0050-suppress-a-candidate-you-have-rejected.md)
carries that table and the pass's full distribution; it is repeated here in one line because it is
the reason this decision goes the way it does. **A floor of twelve was costing the taste layer the
only signal it had no other way of getting: disagreement.** Two things about that denominator, so
that it is not read as more than it is. The 973 is every stored rating rather than a floor-12
candidate list, and [ADR 48](0048-a-high-rating-counts-as-something-you-have.md) counts **167** of
them on entities the known-list does not name — the nearest thing to a candidate population in that
history — of which exactly **2** are below neutral. So the comparison the decision rests on is 72
negatives in one lower-floor deck against 8, or against 2 on the narrower denominator. Nothing is
claimed here about why any particular rating above the old floor was not a negative.

ADR 50 also records the consequence in its own terms: floor 12 sees 16 of the 72 off-list suppressed
entities and floor 5 sees all 72. Moving the default makes the default run the second of those.

### What it did to the ranking, measured on the real graph

Re-run before and after on a copy of the live database, same known-list, same taste layer
(318,116 assertions replayed, 1,150 ratings, 967 known, 143 hub intermediates excluded in both
runs). Aggregates only, per [ADR 51](0051-what-an-adr-may-quote.md) — a ranked list of names is the
recommender's output over the owner's known-list, and this document has already published one it
would not publish now.

| | floor 12 | floor 5 |
|---|---:|---:|
| candidate pool | 1,011 | 1,604 |
| top 25 unchanged | — | **7 of 25** |
| entries that left / entered | — | 18 / 18 |
| median degree of the top 25 | 27 | **6** |
| median degree of the entries that left / entered | — | 26 / 5 |
| top-25 entries sitting exactly on the floor | 1 | **11** |
| top-25 entries whose distinct intermediates equal their degree | 1 | **12** |
| median distinct intermediates in the top 25 | 8 | 5 |

**This is not a small move and should not be reported as one.** Eighteen of twenty-five entries are
different and the median degree of the list falls from 27 to 6. The seven survivors are **exactly
the old list's top seven, in their old relative order** — ranks 8 to 25 all left — which is what a
floor change must look like: lowering it only *adds* candidates, so no score changes and an existing
entry can only be pushed down. Every survivor's score and degree is identical in the two runs, which
was checked rather than assumed. The acceptance criterion on both issues was to re-run and record
whether the top 25 moves. It moves almost entirely.

### The cost, which this ADR records nowhere above

**A lower floor admits entities whose thin connectivity may reflect what segue has fetched rather
than the world, so more of the ranking is exposed to ingest state.** Fetch state is the *candidate*
explanation for that and is not established as the actual one — the owner's retraction on issue #117
withdrew the stronger version, and the correlation below is all that supports the weaker. That is
issue #117's point, and taking this decision converts it from a defect deferred into a documented
property of the tool.

The measurement above says how much more exposed: **11 of the 25 sit exactly on the floor**, and
**12 of the 25 have as many distinct intermediates as they have edges at all** — every edge they
have is evidence being counted, which is another way of saying the graph knows nothing else about
them. One expansion of any of those moves it, which is the anti-pattern below with more purchase on
the default list than it had at twelve.

Two things follow that a reader should not be spared. A run's ranking is now less reproducible
across ingest states than it was, on top of being already irreproducible across rating states (the
issue-#106 amendment's point). And a rejection recorded against a candidate that was only ever
offered because it had been under-fetched is a judgement made on incomplete information — ADR 50
states this, and lowering the floor makes more of the ratings that kind.

### Six alternatives, each measured against this data, each rejected

Seven shapes have now been measured; one — the floor at five — is the decision above. The other six
lost, and the two structural results among them are worth more than any of the six.

- **A denominator that is ingest-independent: Wikidata statement count instead of in-graph degree**
  (issue #117's second option). Simulated over 250 candidates by recovering each numerator from the
  published score and re-dividing. The median degree of the top 15 is **3 either way**, top-15
  overlap is 3/15, and the alternative's top 15 has a median statement count *below* the pool
  median. **The bias is relabelled, not removed:** dividing by degree surfaces the under-fetched,
  dividing by statements surfaces the genuinely obscure. Rejected on being worse by the standard
  that matters — whether the owner recognises the result.

- **A floor of 2.** Pool 3,399 against floor 5's 1,604; median degree of the top 15 is 3 and the
  minimum is 2. This is the configuration every later measurement uses as its example of the
  thinnest thing winning. Rejected: it is the failure mode this ADR predicted, arriving two floors
  lower than predicted.

- **Additive smoothing — divide by `degree + K`** — so that the floor could come down while
  smoothing did the anti-inflation job. Measured at K ∈ {1, 3, 5, 10, 12} across four floors, the
  whole pool ranked rather than a head. At floor 2 the median degree of the top 15 is 3 (K = 1, 3,
  5) or 4 (K = 10, 12) and the minimum is 2 at **every** K — the same number that rejected the
  statement-count denominator. No K reproduces floor 5's useful property: 12 of that list's 15 sit
  in the degree 5-11 band, and smoothing at floor 2 never puts more than 2 there, because raising K
  jumps past the band rather than lifting into it.

  **The first structural result, and it generalises past smoothing.** `degree/(degree + K)` is
  monotonically increasing in degree, so smoothing is the floor's own preference applied softly
  instead of as a cut. Raising K at a fixed floor walks the list toward the next floor up — at
  floor 5 the top 15 overlaps the floor-5 baseline 15/15 at K = 0 and the floor-**12** baseline
  10/15 by K = 12. **Any denominator monotone in degree is a dial on one axis**, and cannot
  separate anti-inflation from worth-showing however it is tuned.

- **Removing the floor altogether**, which smoothing at K ≤ 4 makes arithmetically possible. It does
  admit degree-1 nodes to the top 15, and the result is unusable: at K = 1, **11 of the 15 rows
  carry 5 distinct scores behind 5 intermediates** — a three-way and a five-way tie broken by QID.
  Rejected on the mechanism, not the taste: see "what this does not fix" below.

- **A corroboration threshold — a minimum count of distinct intermediates — as a second tier**, so
  that degree could do anti-inflation while corroboration did worth-showing (issue #118's third
  option, in its strongest form). Falsified, and by the cleanest of the six.

  **The second structural result.** An intermediate is by definition adjacent to the candidate, so
  `intermediates ≤ neighbours ≤ degree` — **0 violations in 9,273 candidates**, with
  `intermediates == neighbours` in 73.4% of them and degree against distinct-neighbour count at
  r = +0.998. **A corroboration threshold of *k* therefore entails a degree floor of *k*.** It is
  the same axis with different numbers printed on it: `≥2` reproduces the floor-2 top 15 15/15,
  including order; `≥5` reproduces floor 5's median degree (6) and minimum (5) at 10/15 overlap;
  `≥7` reproduces floor 12's median (28) at 10/15. Spearman with degree is +0.79 pool-wide. Its one
  real difference is one-sided — being a strict subset, it can only remove — and the swap it makes
  inside floor 5's band is a different judgement rather than a better one.

- **Distinct known entities reaching the candidate**, the honest steelman of the previous item and
  the one quantity here that is genuinely *not* bounded by degree. It fails hardest. At every
  threshold up to 20 the top 15 is degree-1 tie blocks, and the reacher counts *inside* each block
  are identical — a degree-1 node shares its parent's reacher set exactly. **A quantity that is
  constant within a tie block cannot break that tie in principle**, not merely in this data.

**These six are not everything that was proposed.** Issue #117's third option — record expansion
state per node and account for it in the score — was never measured, and it is the only shape
offered on either issue that addresses the demotion directly rather than by changing what is
divided. It is deferred rather than rejected; see the section below.

The raw candidate lists behind all six name entities from the owner's graph and are retained outside
this repository, as ADR 51 requires and as the issue-#115 amendment above does for the same reason.

### What this does NOT fix, and it is #118's title

**A newly discovered node still cannot become a candidate, and that is deliberate rather than
deferred.** Expansion adds nodes at degree 1, and the floor excludes them at five as it did at
twelve. The measurements above are the argument for keeping it that way: **every degree-1 candidate
has exactly one intermediate — all 5,874 of them, by the `intermediates ≤ degree` bound at *d* = 1 —
so its single edge is its whole evidence, and the only part of its score that is about the node
itself is the weight of that one edge.** Everything else in it — which known entities reach the
intermediate, what those hops are worth, the intermediate's own degree — is carried by the parent.

**That is not a claim that such nodes score alike, and this amendment does not make one.**
`CandidateSweep` multiplies the reacher's weight by the candidate's own hop, and
`RecommendationWeights` gives that hop four tiers and a direction multiplier, so two degree-1 nodes
on one intermediate whose edges differ in tier or direction get different scores. What was
*measured* is the tie blocks, not their inevitability: the no-floor run above put 11 of its top 15
rows behind only 5 distinct scores, and the three-way and five-way ties inside them were broken by
QID. Admitting degree-1 nodes puts blocks of that shape into the head of the ranking, and since one
expansion can add hundreds of such nodes at once, part of what would surface is ordered by *which
entity was expanded last*.

So issue #118's floor question is answered here and its title complaint is not. Any remedy for the
title has to give a newly discovered node something of its own to be scored on, which means fetching
a second edge for it rather than re-weighting the first, or abandoning per-candidate normalisation
altogether. Neither is decided here; **issue #134 carries it.**

### The mechanism above is qualified, not overturned

This ADR's argument for lift is that dividing by the candidate's own degree escapes fame. That
argument survives, but more weakly than the text above claims, and the qualification belongs on the
record because it is what a future change would reason from.

Measured on a seeded random sample of 400 nodes at degree ≥ 2 — the population the floor acts on —
using Wikidata's own `wikibase:statements` and `wikibase:sitelinks`: **in-graph degree against
notability is pearson +0.26 / spearman +0.30 for statements, and +0.27 / +0.28 for sitelinks**, with
the two external measures agreeing with each other at +0.87 as the check that the query worked. An
earlier figure on this question was withdrawn on the issues as a selection artefact — it had been
sampled from lift-ranked top-25 lists, which is selection on the very quantity being measured — and
nothing here rests on it.

So dividing by degree **does** partially divide by fame. It also divides by something else, and that
something else is the larger part: **roughly 92% of degree's variance is not notability**, and the
median in-graph degree of those nodes is 2 against a median 34 Wikidata statements about the same
nodes. The typical candidate is barely fetched relative to what is knowable about it, and the floor
is therefore acting on both quantities at once. That is the whole of the cost recorded above,
expressed as a correlation instead of as a list.

### What this amendment leaves open, and where each piece is recorded

Issues #117 and #118 close on this decision, so the three questions they do not answer are filed
separately rather than closed with them. **None is rejected here; each is undecided.**

- **[#133](https://github.com/robsartin/segue/issues/133) — record expansion state and account for
  it.** Issue #117's third option, and the only one of its three that is neither taken nor measured.
  Every remedy above normalises by *how big the candidate is*; this one would distinguish "thin
  because unfetched" from "thin in the world", which is the conflation the whole cost section is
  about. #117's own comment calls it untested and the only remaining shape that addresses the
  demotion directly.
- **[#134](https://github.com/robsartin/segue/issues/134) — a newly discovered node still cannot
  become a candidate.** Issue #118's title, which the section above answers with a deliberate "not
  here". The remedies named there — fetch a second edge, or abandon per-candidate normalisation —
  are undecided, and so is a third the section does not consider: surface such nodes somewhere other
  than the ranked list.
- **[#135](https://github.com/robsartin/segue/issues/135) — the floor drifts as the graph grows.**
  This amendment chose five by running two floors on today's graph, which is the same method ADR 45
  used to choose twelve on a smaller one. Nothing says what would make five wrong later, and 11 of
  the top 25 sit exactly on it, so the head of the list is what moves first.

### Consequences of this amendment

- **The default list is a different population**, not a longer one: the deck and `recommend` both
  deal recognisable-but-thinly-fetched entities where they dealt well-connected ones.
- **`--min-degree 12` reproduces the old behaviour exactly**, and is the way to read the two lists
  side by side — the method this ADR recommends for exactly this question.
- **The anti-pattern in the developer guide matters more now.** Expanding a top candidate raises its
  own denominator and demotes it; at a floor of five, eleven of the top twenty-five are one
  expansion away from moving.
- **Nothing about the scorer changed.** The floor filters candidates and nothing else: every entry
  common to the two runs above carries an identical score and degree, and the same independence was
  checked pool-wide, across 3,399 candidates, while measuring the alternatives.

**Amendment (2026-08-30, issues #133, #134 and #135): the three questions the amendment above left
open are decided in [ADR 57](0057-the-floor-reports-itself.md), which is the authority on them.**

Nothing above is withdrawn and no decision above is edited. In short: the floor now emits a reading
of itself on every run, so the drift this document's amendment left silent is visible; a newly
discovered node is still not ranked, and the run reports how many are held out; and recording
expansion state to feed the score is refused on measurement rather than deferred. The reading
reproduces four of the eight rows of that amendment's before-and-after table, at both floors, which
is how it was checked that it changes no ranking.

**Amendment (2026-09-04, issue #242): the first measured reading of the recommender moved nothing,
and the shipped scorer and floor stand on evidence rather than on judgement alone.**

Nothing above is withdrawn and no decision above is edited. No constant changed and no code changed.
What changed is the standing of two numbers: `lift` and a floor of five were chosen by reading
ranked lists side by side, and they have now been measured against the owner's own held-out ratings
and were not displaced.

**The rule was fixed before the number existed, and that is the whole evidential value of this
entry.** Commit `9937f86` on 2026-09-04 committed
`docs/superpowers/specs/2026-09-04-calibrate-one-constant-design.md`, which states the decision rule
in full — the denominator, the three-hit bar, the no-more-negatives condition, the
dominance-across-floors condition, the one-constant limit, and the clause that says a near miss
stands. The reading below was taken afterwards. Had the reading come first, this paragraph would be
a rationalisation with a table attached; the rule is the authority on what would have counted and is
not restated here.

**The reading.** One run of `./gradlew evaluate` ([ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md))
on the owner's database, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, and
every label is a column name or a `Scorer` spelling.

```
# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings (ADR 51, ADR 63, ADR 65).
# held out every 5 of 152 eligible entity(ies): 31 held out, 121 left on the known-list.
# top 25 per setting, over 16 setting(s).
scorer               floor  pool  in pool  hits  mean rank  negatives  neg mean rank
raw                      2  3426       31     3        9.3          3           18.0
raw                      5  1634       31     3        9.3          3           18.0
raw                      8  1239       22     3        9.3          3           18.0
raw                     12  1030       19     3        9.3          3           18.0
adamic-adar              2  3426       31     3        4.0          2           18.5
adamic-adar              5  1634       31     3        4.0          2           18.5
adamic-adar              8  1239       22     3        4.0          2           18.5
adamic-adar             12  1030       19     3        4.0          2           18.5
resource-allocation      2  3426       31     4       10.3          2            9.5
resource-allocation      5  1634       31     4       10.3          2            9.5
resource-allocation      8  1239       22     4       10.3          2            9.5
resource-allocation     12  1030       19     4       10.3          2            9.5
lift                     2  3426       31     0          -          2           13.5
lift                     5  1634       31     8       11.4         14           12.2
lift                     8  1239       22     6       10.7         10           11.1
lift                    12  1030       19     4       13.8          6            8.2
```

**What the rule made of it.** Clause 1 fixed every denominator as the setting's own `in pool` cell
rather than the held-out count on the header line. Clause 2 asked the scorer question at the shipped
floor: `raw`, `adamic-adar` and `resource-allocation` were each compared with `lift`'s row at that
floor on `hits`, and none of the three reached the three-hit bar — each sits below `lift` there
rather than above it. Each of the three does beat `lift` on `hits` at the lowest floor, where
`lift` records none, and none of the three beats it at the two floors above the shipped one,
`resource-allocation` tying rather than beating at the highest of them. Their
`negatives` cells all satisfied the no-more-negatives condition, which settled nothing, because the
hits condition had already failed for each. Clause 3 then asked the floor question at `lift`, since
clause 2 had displaced nothing, comparing `hits` and `negatives` on `lift`'s rows at the lowest
floor and at the two above the shipped one against `lift`'s shipped-floor row: every one falls short
of the three-hit bar, the lowest floor by the widest margin in the grid. That clause's extra
sub-check for a candidate floor below the shipped one — one `recommend` run whose `FloorReading`
must sit inside [ADR 57](0057-the-floor-reports-itself.md)'s trigger band — was never reached, since
the grid's only sub-shipped floor had already failed on hits, and no `recommend` run was made.
Clause 4 therefore took its remaining branch. Clause 5 refused the two near misses by name:
`adamic-adar` carries the best `mean rank` of any row at the shipped floor and fewer `hits` than
`lift`, which is a mean-rank improvement without a hit improvement and is not a hit; and
`resource-allocation` equals `lift` on `hits` at the highest floor, which is a tie and not a win.

### What the table shows that the rule did not anticipate

Four observations, recorded as observations. **The rule is not amended by any of them.** It was
fixed at the commit named above, before the reading, and stands exactly as committed; each of these
is for the issue that takes the next reading to weigh.

- **The `negatives` column is confounded at the shipped setting, and the confound favours a
  challenger.** A rating of one or two comes mostly from the rating deck
  ([ADR 46](0046-the-rating-deck.md)), and the deck deals what `recommend` surfaces at the shipped
  setting. So `lift`'s `negatives` cell at the shipped floor partly measures what the deck dealt and
  the owner rejected, where every other row's `negatives` cell measures a list the owner was never
  offered and so had no chance to reject. A "no more negatives" condition therefore reads in a
  challenger's favour mechanically, without anything about the challenger being better. It decided
  nothing here — no challenger reached the hits bar, so the negatives condition was never the
  binding one — but a later reading has to weigh it before that condition can be trusted, and the
  repair is that reading's to argue rather than this entry's.
- **The shipped scorer records no hits at all at the lowest floor in the grid.** Its `hits` cell
  there stands alone in that column and its `mean rank` cell is the dash. This ADR gave the floor a
  reason — a score normalised by the candidate's own degree rewards whatever is thinnest, so `lift`
  is paired with a floor rather than used alone — and that row is the reason as a number instead of
  as an argument.
- **The other three scorers do not move with the floor at all.** `raw`, `adamic-adar` and
  `resource-allocation` each hold one `hits` value and one `negatives` value across every floor in
  the grid, while `in pool` falls as the floor rises. What the floor removes is not what those three
  put at the top of a list: their top is high-degree entities the floor never reaches, which is the
  same finding this ADR recorded when it chose to normalise, read from the floor's side.
- **Two conditions in the rule are weaker on this table than they read, and the next reading
  should say so before trusting them.** The dominance-across-floors condition is free at the
  lowest floor, because the shipped scorer's `hits` cell there is the one the bullet above
  singles out and every challenger clears it without being better; and it is one comparison
  repeated at the two upper floors, because the three challengers hold a single `hits` value
  across the grid. Separately, both that condition and the floor clause compare `hits` as counts
  between rows whose `in pool` cells differ — `in pool` falls as the floor rises — so a higher
  floor is judged on absolute hits while reaching fewer of the held-out entities. The rule chose
  counts deliberately and is not amended by this; it is what the issue that takes the next
  reading has to weigh.

### What this does and does not establish

- **It does not establish that `lift` at five is the best setting.** It establishes that on this
  reading, by this rule, nothing displaced it. ADR 65's first consequence is the governing one: no
  row of that table means anything on its own.
- **It does not establish that the harness can tell these settings apart.** The held-out set is
  small — one entity is several points of hit rate, which is why the rule asked for a difference no
  single entity could produce — so a null result is also what an instrument too blunt for the
  question would produce. Nothing here distinguishes those two readings, and a second reading on a
  larger split is the only thing that would.
- **It says nothing about the entities ingest cannot reach.** Rates are read over the reachable
  (ADR 65's consequence), so this is not a verdict on expansion coverage.
- **The constants are no longer untested, and that is the change.** ADR 45 declined to tune them
  because nothing could evaluate them; something now can, it has, and the answer was "stand".

### Consequences of this amendment

- **Nothing in the tool moves**, so no ranking, no deck and no output line changes.
- **`Setting.GRID` is unchanged**, so the next reading is comparable to this one row by row — the
  property #239 fixed the grid for.
- **The question is re-asked, not closed.** A later issue takes a second reading, and a wider grid
  or a further metric is that issue's to argue rather than this one's.
- **Nothing here is unit-testable, and that is said out loud rather than left implied.** No
  behaviour changed, so there is no test to write and nothing to see red. The verification is the
  full gate over an otherwise unchanged tree — `AdrIndexTest` for the index this amendment does not
  touch, `DocumentationLinksTest` for the four relative links above, and `javadoc -Werror` inside
  `./gradlew check` — together with the ruling that applied the rule cell by cell.


**Amendment (2026-09-06, issue #245): a second reading, taken after the graph moved under unchanged
ratings and judged by a rule written to answer the first, moved nothing either — and it was the
first reading, cell for cell.**

Nothing above is withdrawn and no decision above is edited, including the amendment immediately
above this one: that entry's reading, its ruling and its four observations stand exactly as written.
No constant changed and no code changed. What changed is that the shipped scorer and floor have now
been measured twice, the second time by a rule built to answer the first reading's own criticisms of
itself, and on a graph whose routes had been reshaped in between.

**The rule was fixed before the number existed, and it was written to answer the previous reading
rather than this one.** Commit `33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba`, authored 2026-09-04,
committed `docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`, which states the
decision rule in full — rates over the `in pool` cell, the margin that replaced a hit count and the
arithmetic that voids it on a split too small to carry it, the dominance range that excludes the
floors where the shipped scorer has no hits, the dropped negatives clause and why it was dropped, the
one-constant limit, and the clause that says a near miss stands. The same rule text was first pushed to the
remote on the evening of 2026-09-04 as commit `74e757f`; on the morning of 2026-09-06 it was rebased
onto main, which is the commit named above and carries that authorship date, and pushed again
together with a dated note appended to the same document, before the owner ran the harness later
that morning. The note changes no clause; it records that the
trigger for this reading was not the one the rule anticipated. The rule was written for a reading
taken after the owner's ratings moved. The ratings did not move. The graph did: issues #261 and #265
(merged 2026-09-05) re-kinded roughly a tenth of the graph's nodes out of `CONCEPT` and therefore out
of the `CONCEPT`-gated hub demotion in `PathRanking` and the hub exclusion in `CandidateSweep`. No
`PERSON` or `GROUP` changed kind, so the candidate pool is the same; the ratings are the same, so the
held-out set is the same. This reading is the same instrument on the same population with only the
routes changed, and the note asked that its result be set beside the first as an observation. The
rule is the authority on what would have counted and is not restated here.

**The reading.** One run of `./gradlew evaluate` ([ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md))
on the owner's database on 2026-09-06, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, and
every label is a column name or a `Scorer` spelling.

```
# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings (ADR 51, ADR 63, ADR 65).
# held out every 5 of 152 eligible entity(ies): 31 held out, 121 left on the known-list.
# top 25 per setting, over 16 setting(s).
scorer               floor  pool  in pool  hits  mean rank  negatives  neg mean rank
raw                      2  3426       31     3        9.3          3           18.0
raw                      5  1634       31     3        9.3          3           18.0
raw                      8  1239       22     3        9.3          3           18.0
raw                     12  1030       19     3        9.3          3           18.0
adamic-adar              2  3426       31     3        4.0          2           18.5
adamic-adar              5  1634       31     3        4.0          2           18.5
adamic-adar              8  1239       22     3        4.0          2           18.5
adamic-adar             12  1030       19     3        4.0          2           18.5
resource-allocation      2  3426       31     4       10.3          2            9.5
resource-allocation      5  1634       31     4       10.3          2            9.5
resource-allocation      8  1239       22     4       10.3          2            9.5
resource-allocation     12  1030       19     4       10.3          2            9.5
lift                     2  3426       31     0          -          2           13.5
lift                     5  1634       31     8       11.4         14           12.2
lift                     8  1239       22     6       10.7         10           11.1
lift                    12  1030       19     4       13.8          6            8.2
```

**What the rule made of it.** Clause 2's void check was run first and did not fire: the smallest
`in pool` cell among the rows any clause compared is above the bound at which one entity is worth
the whole margin. Clause 3(a) compared every non-shipped scorer's `hits` over its own `in pool` at
the shipped floor with `lift`'s row there, and each is below `lift`'s rate, not merely short of the
fifteen points. Clause 3(b) derived the dominance range from the table as the floors other than the
shipped one at which `lift`'s `hits` cell is non-zero — floors eight and twelve — and every
challenger fails there as well; `resource-allocation`'s rate at floor twelve exactly equals `lift`'s,
which is a tie, and clause 7 is why a tie stands. No scorer moved, so clause 4 compared `lift`'s rate
at every other floor with its rate at the shipped floor: floor eight is the closest and is short of
the margin by an order of magnitude, and floor two, where `lift` records no hits, is the observation
the previous amendment already made. The `negatives` and `neg mean rank` cells were read for every
row and, per clause 5, decided nothing. Outcome: the shipped setting stands.

**The observation the note asked for.** The block above is identical, byte for byte, to the block in
the amendment immediately above this one: every `pool`, `in pool`, `hits`, `mean rank`, `negatives`
and `neg mean rank` cell in all sixteen rows, and the three header lines. The shipped setting's hit
rate therefore differs between the two readings by nothing at all, inside clause 2's margin by the
whole of it. Re-kinding a tenth of the graph changed no aggregate the harness reports, at any setting.
That is an aggregate statement and no more: a held-out entity swapped for another at the same rank
inside one list would leave every cell as it is, and ADR 65's first consequence governs. As
explanation, cited rather than restated: the hub demotion that the re-kinded nodes left is gated on
`PathRanking.HUB_DEGREE`, an absolute in-graph degree, and the census readings on issues #261 and
#265 show that no concert tour on this graph reaches it — the `EVENT` maximum did not move when the
tours joined it — so ADR 42's consequence that two acts sharing only a tour can now route to each
other is, on this graph today, a property of the code and not yet of any route. Of the editions the
census says only that they were the low-degree mass that thinned `CONCEPT`'s own tail, not that
none reaches the hub degree. The identical table is the evidence; the explanation is the best the
aggregates allow.

### What this does and does not establish

- **It does not establish that the shipped setting is the best one.** It establishes that on this
  reading, by this rule, nothing displaced it. ADR 65's first consequence is the governing one: no
  row of that table means anything on its own.
- **It does not establish that the harness can tell these settings apart, and a second null result
  makes that the live question.** The held-out set is small, so a null result is also what an
  instrument too blunt for the question would produce. Two readings that move nothing do not
  distinguish "the setting is right" from "the split is too small to say", and enlarging the split
  (`HeldOut.EVERY`) is the only thing that would.
- **It establishes that the kind changes of #261 and #265 reached no route the harness watches**,
  which is narrower than "reached no route": the harness sees only the held-out entities' top
  twenty-five at each setting, and a route between two entities it does not hold out is invisible to
  it. The `recommend` deck is the place a changed route would show, and nothing here reads it.
- **The negatives condition was dropped rather than satisfied**, so nothing here is a finding about
  the negatives column; the cells are quoted above and decided nothing.
- **It says nothing about the entities ingest cannot reach.** Rates are read over the reachable
  (ADR 65's consequence), so this is not a verdict on expansion coverage.

### Consequences of this amendment

- **Nothing in the tool moves**, so no ranking, no deck and no output line changes.
- **`Setting.GRID` is unchanged**, so a third reading is comparable to both of these row by row —
  the property #239 fixed the grid for.
- **The question is re-asked, not closed, and what the next issue should change is the split rather
  than the rule.** Two identical null readings on the same held-out split say the instrument has
  been asked the same question twice; the next reading that can say something new is
  one taken after the ratings move, or on a wider split, and the rule above applies to it unchanged.
- **Nothing here is unit-testable, and that is said out loud rather than left implied.** No
  behaviour changed, so there is no test to write and nothing to see red. The verification is the
  full gate over an otherwise unchanged tree — `AdrIndexTest`, `DocumentationLinksTest` for the
  relative links above, and `javadoc -Werror` inside `./gradlew check` — together with the ruling
  that applied the rule cell by cell, and a byte comparison of the block above against the owner's
  paste and against the previous amendment's block.


**Amendment (2026-09-06, issue #270): the first reading by the folded harness, judged by the same
rule, moved nothing — and it is the baseline every later folded reading is read against.**

Nothing above is withdrawn and no decision above is edited, including the two amendments immediately
above this one: their readings, their rulings and their observations stand exactly as written. No
constant changed and no code changed. What changed is the instrument: issue #268 made the harness
read every fold of its split, so every eligible entity is held out exactly once over a run and the
hit counts a ruling rests on are the size five folds give rather than one
([ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md)'s 2026-09-06 amendment).

**The rule was fixed before the number existed, for the third time.** The rule is the one committed
as `33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba` and described in the amendment above; it was not
changed. What was added before this reading was a dated note, committed and pushed as
`e0e398b` on 2026-09-06 at 12:57, before the reading the owner pasted carrying a 13:57 timestamp,
appended to `docs/superpowers/specs/2026-09-04-second-reading-rule-design.md` and saying how the
rule reads a folded table: no clause changes; `in pool` and `hits` are entity counts, because each held-out
entity is in exactly one fold, while `pool` and `negatives` are entity-fold counts, because a
rated-down entity is offered once per fold; clause 2's void check is near-vacuous on totals rather
than invalid, and is run and recorded anyway; and the single-fold readings above are not row for row
comparable to this one, so the only cross-reading observation recorded is the shipped setting's own
hit rate. The rule is the authority on what would have counted and is not restated here.

**The reading.** One run of `./gradlew evaluate` on the owner's database on 2026-09-06, on the
folded harness, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, every
label is a column name or a `Scorer` spelling, and the split line names the folds.

```
# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings (ADR 51, ADR 63, ADR 65).
# held out every 5 of 152 eligible entity(ies), in 5 fold(s): 152 held out over all folds, at least 121 left on the known-list in each.
# top 25 per setting, over 16 setting(s).
scorer               floor   pool  in pool  hits  mean rank  negatives  neg mean rank
raw                      2  17104      152    15       10.0         15           17.5
raw                      5   8154      152    15       10.0         15           17.5
raw                      8   6195      115    15       10.0         15           17.5
raw                     12   5140       93    15       10.0         15           17.5
adamic-adar              2  17104      152    16        7.5         12           18.7
adamic-adar              5   8154      152    16        7.5         12           18.7
adamic-adar              8   6195      115    16        7.5         12           18.7
adamic-adar             12   5140       93    16        7.5         12           18.7
resource-allocation      2  17104      152    17       10.0         10            9.4
resource-allocation      5   8154      152    17       10.0         10            9.4
resource-allocation      8   6195      115    17       10.0         10            9.4
resource-allocation     12   5140       93    17       10.0         10            9.4
lift                     2  17104      152     0          -         10           15.0
lift                     5   8154      152    40       12.6         70           11.5
lift                     8   6195      115    37       11.4         53           12.4
lift                    12   5140       93    20       11.9         33            9.0
```

**What the rule made of it.** Clause 2's void check was run first and, as the note expected, did not
fire: the smallest `in pool` cell among the rows any clause compared is a total over five folds and
sits far above the bound at which one entity is worth the whole margin. Clause 3(a) compared every
non-shipped scorer's `hits` over its own `in pool` at the shipped floor with `lift`'s row there, and
each is below `lift`'s rate, not merely short of the fifteen points. Clause 3(b) derived the
dominance range from the table as the floors other than the shipped one at which `lift`'s `hits`
cell is non-zero — floors eight and twelve — and at both, every challenger's rate is below `lift`'s;
no two rates the rule compared are equal. No scorer moved, so clause 4 compared `lift`'s rate at
every other floor with its rate at the shipped floor: floor eight is the closest and clears a little
over a third of the margin, which clause 7 says is a stand; floor twelve is below the shipped
floor's rate; and floor two, where `lift` records no hits, is the observation the 2026-09-04
amendment already made. The `negatives` and `neg mean rank` cells were
read for every row and, per clause 5, decided nothing. Outcome: the shipped setting stands.

**The observation the note asked for.** The shipped setting's hit rate on this reading is within a
single point of its rate on the two single-fold readings above, which were themselves identical,
and the difference is inside clause 2's margin by nearly the whole of it. Two further things this
table shows on its own, both observations and neither a ruling, and neither a comparison with any
earlier row: every non-shipped scorer's `hits` cell is the same at all four floors while its
`in pool` falls, so those scorers' rates rise with the floor — which the aggregates support, and
which says nothing about whether the entities behind the counts are the same ones; and `lift`'s
rate is above every challenger's at every floor in the dominance range.

### What this does and does not establish

- **It does not establish that the shipped setting is the best one.** It establishes that on this
  reading, by this rule, nothing displaced it. ADR 65's first consequence is the governing one: no
  row of that table means anything on its own.
- **It establishes that the null result survives being read on every fold rather than one.** The
  totals are over the whole eligible population and the ruling is the same by every clause. The
  question the previous amendment left live — whether the instrument can tell these settings apart
  — is narrowed rather than answered: the rule issues no affirmative win for the incumbent, `lift`
  has no hits at all at the lowest floor, and the margin is what it was.
- **It is the folded baseline.** Every later folded reading is compared to this block row by row; the
  single-fold readings above are compared to each other and not to this one.
- **The negatives condition was dropped rather than satisfied**, so nothing here is a finding about
  the negatives column; the cells are quoted above, on the entity-fold scale, and decided nothing.
- **It says nothing about the entities ingest cannot reach.** Rates are read over the reachable
  (ADR 65's consequence), so this is not a verdict on expansion coverage.

### Consequences of this amendment

- **Nothing in the tool moves**, so no ranking, no deck and no output line changes.
- **`Setting.GRID` and `HeldOut.EVERY` are unchanged**, so the next folded reading is comparable to
  this one row by row.
- **The next reading that can say something new needs the ratings to move, or a wider split.** The
  instrument now reads every fold, and the graph has been reshaped under it; `HeldOut.EVERY` is
  unchanged, so a wider split (a smaller interval) remains the other lever, and this amendment does
  not take it. What has not changed across three readings is the taste layer. A fourth reading is
  worth taking after the owner has rated through the client, and the rule above applies to it
  unchanged.
- **Nothing here is unit-testable, and that is said out loud rather than left implied.** No
  behaviour changed, so there is no test to write and nothing to see red. The verification is the
  full gate over an otherwise unchanged tree — `AdrIndexTest`, `DocumentationLinksTest` for the
  relative links above, and `javadoc -Werror` inside `./gradlew check` — together with the ruling
  that applied the rule cell by cell and a byte comparison of the block above against the owner's
  paste.


**Amendment (2026-09-06, issue #272): the first reading after the ratings moved, judged by the same
rule, moved nothing — and it is the first reading with something to say about the shipped setting.**

Nothing above is withdrawn and no decision above is edited, including the three amendments
immediately above this one. No constant changed and no code changed. What changed is the taste
layer: the owner rated a deck ([ADR 46](0046-the-rating-deck.md)) on 2026-09-06, and this is the
trigger the rule was written for. How many ratings moved is recorded on issue #272 from the census's
`taste` section, read before the harness ran; the graph, the grid, the fold count and every constant
are as they were at the folded baseline above.

**The rule was fixed before the number existed, for the fourth time.** The rule is the one committed
as `33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba` and described two amendments above; it was not
changed. A dated note was committed and pushed as `3c2171e` on 2026-09-06 at 15:47, before the
reading the owner pasted carrying a 16:11 timestamp, appended to
`docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`: no clause changes; the note on
folded tables applies as written; the eligible population is what is rated at or above the
promotion threshold and absent from the `--known` file, so a session of the deck changes it and every
row's `in pool` is over the changed population; and exactly two comparisons to the folded baseline
are recorded, as observations deciding nothing — the shipped setting's hit rate and the split line's
eligible count. The rule is the authority on what would have counted and is not restated here.

**The reading.** One run of `./gradlew evaluate` on the owner's database on 2026-09-06, on the
folded harness, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, every
label is a column name or a `Scorer` spelling, and the split line names the folds. `in pool` and
`hits` count entities; `pool` and `negatives` count entity-folds.

```
# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings (ADR 51, ADR 63, ADR 65).
# held out every 5 of 263 eligible entity(ies), in 5 fold(s): 263 held out over all folds, at least 210 left on the known-list in each.
# top 25 per setting, over 16 setting(s).
scorer               floor   pool  in pool  hits  mean rank  negatives  neg mean rank
raw                      2  16281      263    24       10.7         18           18.9
raw                      5   7260      263    25       11.2         18           18.9
raw                      8   5668      192    25       11.2         18           18.9
raw                     12   4747      149    24       10.7         18           18.9
adamic-adar              2  16281      263    28        8.1         16           18.5
adamic-adar              5   7260      263    28        8.1         16           18.5
adamic-adar              8   5668      192    28        8.1         16           18.5
adamic-adar             12   4747      149    28        8.1         16           18.5
resource-allocation      2  16281      263    31        9.2         15            7.6
resource-allocation      5   7260      263    31        9.2         15            7.6
resource-allocation      8   5668      192    31        9.2         15            7.6
resource-allocation     12   4747      149    31        9.2         15            7.6
lift                     2  16281      263     0          -          7           18.6
lift                     5   7260      263    42       11.0         68           12.2
lift                     8   5668      192    40       12.1         48           11.8
lift                    12   4747      149    25       11.8         35           10.9
```

**What the rule made of it.** Clause 2's void check was run first and did not fire. Clause 3(a)
compared every non-shipped scorer's `hits` over its own `in pool` at the shipped floor with `lift`'s
row there, and each is below `lift`'s rate. Clause 3(b) derived the dominance range as floors eight
and twelve. At floor eight every challenger's rate is below `lift`'s. **At floor twelve two of them,
`adamic-adar` and `resource-allocation`, are above it**, and the rule saw it and it changed nothing,
because a scorer moves only on both conditions and neither cleared the first; the ruling records the
comparison rather than swallowing it. No scorer moved, so clause 4 compared `lift`'s rate
at every other floor with its rate at the shipped floor: floor eight is the closest and clears about
a third of the margin, which clause 7 says is a stand; floor twelve is a hair above the shipped
floor's rate; floor two is where `lift` records no hits. The
`negatives` and `neg mean rank` cells were read for every row and, per clause 5, decided nothing.
Outcome: the shipped setting stands.

**The two observations the note asked for, and no third.** The eligible population grew by about
three quarters against the folded baseline, which is the deck session's promotions entering it. The
shipped setting's hit rate fell by about two thirds of the margin against the baseline, inside the
margin. Both are read from two cells each and decide nothing. Why the rate fell is not read from
this table: the population under every row changed, every fold was re-cut over it, and the
aggregates do not say which entities the top twenty-five reached before and does not now. That
question is issue #272's to carry, not this amendment's to answer.

### What this does and does not establish

- **It does not establish that the shipped setting is the best one.** It establishes that on this
  reading, by this rule, nothing displaced it. ADR 65's first consequence is the governing one: no
  row of that table means anything on its own.
- **It records, without acting on it, that two challengers exceed the shipped scorer at the highest
  floor in the grid.** The rule asks the scorer question at the shipped floor, where both are below
  it, and clause 7 is why a comparison that clears nothing stands as a comparison.
- **It does not establish why the shipped rate fell**, only that it did, and by how much of the
  margin.
- **The negatives condition was dropped rather than satisfied**, so nothing here is a finding about
  the negatives column; the cells are quoted above, on the entity-fold scale, and decided nothing.
- **It says nothing about the entities ingest cannot reach.** Rates are read over the reachable
  (ADR 65's consequence), so this is not a verdict on expansion coverage.

### Consequences of this amendment

- **Nothing in the tool moves**, so no ranking, no deck and no output line changes.
- **`Setting.GRID` and `HeldOut.EVERY` are unchanged.** The folded baseline above remains what the
  amendment that recorded it says it is; this block stands beside it as the first reading over a
  population the deck has moved.
- **The next reading worth taking follows the next deck session**, and its question is whether the
  shipped rate steadies or keeps falling as the deck's promotions accumulate. The rule above applies
  to it unchanged.
- **Nothing here is unit-testable, and that is said out loud rather than left implied.** No
  behaviour changed, so there is no test to write and nothing to see red. The verification is the
  full gate over an otherwise unchanged tree — `AdrIndexTest`, `DocumentationLinksTest` for the
  relative links above, and `javadoc -Werror` inside `./gradlew check` — together with the ruling
  that applied the rule cell by cell and a byte comparison of the block above against the owner's
  paste.


**Amendment (2026-09-07, issue #278): the fifth reading, read in two halves by rating age, moved
nothing — and its halves say which explanation of the fall the table is consistent with.**

Nothing above is withdrawn and no decision above is edited, including the four amendments
immediately above this one. No constant changed and no code changed. What changed is that the
reading was taken with the rating-age split ([ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md)'s
second 2026-09-06 amendment, issue #276), after the owner's second deck session; how many ratings
that session moved is on issue #278 from the census's `taste` section, read before the harness ran.

**The rule was fixed before the number existed, for the fifth time, and so was the instant.** The
rule is the one described three amendments above and it was not changed. A dated note was committed
and pushed on the morning of 2026-09-07, before the reading the owner pasted carrying an 11:07
timestamp, appended to `docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`; pull
request #279 carries it. The note fixed the instant the halves are drawn at — the moment the fourth
reading's harness began reading ratings, so that the old half is everything rated before either deck
session and the new half is everything the two sessions promoted — and said what the halves are read
for and that they decide nothing. The rule is the authority on what would have counted and is not
restated here.

**The reading.** One run of `./gradlew evaluate` with `--rated-since` on the owner's database on
2026-09-07, on the folded harness, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, every
label is a column name or a `Scorer` spelling, and the header names the folds and the instant.
`in pool`, `hits` and the four half cells count entities; `pool` and `negatives` count entity-folds.
On every row the two halves sum to the whole-population cell, as the report guarantees.

```
# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings (ADR 51, ADR 63, ADR 65).
# held out every 5 of 279 eligible entity(ies), in 5 fold(s): 279 held out over all folds, at least 223 left on the known-list in each.
# split by rating age at 2026-09-06T18:56:00Z: 152 old (rated before it), 127 new (rated on or after it) — a rating's timestamp is its last write, so a re-rated old promotion counts as new.
# top 25 per setting, over 16 setting(s).
scorer               floor   pool  in pool  hits  mean rank  negatives  neg mean rank  old in pool  old hits  new in pool  new hits
raw                      2  16223      279    25       10.7         17           18.4          152        17          127         8
raw                      5   7189      279    25       10.7         17           18.4          152        17          127         8
raw                      8   5623      203    25       10.7         17           18.4          115        17           88         8
raw                     12   4715      158    24       10.2         18           18.7           93        16           65         8
adamic-adar              2  16223      279    31        9.8         16           17.8          152        17          127        14
adamic-adar              5   7189      279    31        9.8         16           17.8          152        17          127        14
adamic-adar              8   5623      203    31        9.8         16           17.8          115        17           88        14
adamic-adar             12   4715      158    31        9.8         16           17.8           93        17           65        14
resource-allocation      2  16223      279    36        9.7         15            7.9          152        19          127        17
resource-allocation      5   7189      279    36        9.7         15            7.9          152        19          127        17
resource-allocation      8   5623      203    36        9.7         15            7.9          115        19           88        17
resource-allocation     12   4715      158    36        9.7         15            7.9           93        19           65        17
lift                     2  16223      279     0          -          5           18.4          152         0          127         0
lift                     5   7189      279    43       10.4         67           12.3          152        38          127         5
lift                     8   5623      203    40       11.7         46           11.4          115        33           88         7
lift                    12   4715      158    23       11.7         36           11.1           93        16           65         7
```

**What the rule made of it.** The rule reads the whole-population `in pool` and `hits` cells and
nothing else, as the note says. Clause 2's void check was run first and did not fire. Clause 3(a)
compared every non-shipped scorer's rate at the shipped floor with `lift`'s row there, and each is
below `lift`'s. Clause 3(b) derived the dominance range as floors eight and twelve; at floor eight
every challenger's rate is below `lift`'s, and at floor twelve all three are above it, which the
rule saw and which changed nothing, because a scorer moves only on both conditions and none cleared
the first. No scorer moved, so clause 4 compared `lift`'s rate at every other floor with its rate at
the shipped floor: floor eight is the closest and clears well under a third of the margin, which
clause 7 says is a stand; floor twelve is below the shipped floor's rate; floor two is where `lift`
records no hits. The `negatives` and `neg mean rank` cells were read for every row and, per clause
5, decided nothing. Outcome: the shipped setting stands.

**The observations the note allowed.** The shipped setting's whole-population hit rate is within a
point of the fourth reading's and well below the folded baseline's, as the fourth was; the eligible
population is a little larger than the fourth reading's, which is the second session's promotions
entering it, and well above the baseline's.

**What the halves say, which is the reason this reading was taken.** On the shipped setting's row,
the old half's rate is within a point and a half of the folded baseline's shipped rate, and the new
half's rate is a few points above zero: the first of the two explanations the note names, for that
row. On every challenger's row at the shipped floor the two halves land within a few points of each
other. Had the taste layer widened into ground the routes serve less well — the note's second
explanation — every scorer's new half would have fallen together, and only the shipped scorer's did.
So the table is consistent with the first explanation and not with the second, which is as far as
the note lets it be read.

**What the code says about the first explanation.** `RateRun` builds the deck's candidate cards from
a sweep scored by `Recommendations.DEFAULT_SCORER`, the shipped scorer, and deals its top
`RateCli.DEFAULT_CANDIDATES` in that ranking's order, skipping whatever is already rated; the harness
reads a top twenty-five. A card dealt from inside those twenty-five and promoted would be a hit; a
card dealt from below them and promoted would not; a promotion made in revision mode passes through
no candidate stream at all. The halves cannot say which cards these were, and this amendment does
not claim it. What they say is consistent with the sessions having dealt, in the main, cards the
shipped scorer ranked below its first twenty-five — which is what a deck that skips the already
rated does once the top of one ranking has been rated through — and with the other scorers, ranking
by other criteria, placing some of those same promotions inside their own twenty-five.

### What this does and does not establish

- **It does not establish that the shipped setting is the best one.** It establishes that on this
  reading, by this rule, nothing displaced it. ADR 65's first consequence is the governing one: no
  row of that table means anything on its own.
- **It answers issue #272's question as far as aggregates can**: the table is consistent with the
  first explanation and not with the second. It does not identify which promotions the shipped
  scorer did not reach, and it does not say the shipped scorer reached none — its new-half `hits`
  cell is not zero.
- **It raises a question about the instrument's pairing with the deck, and records it as a
  question rather than a finding.** If a session's promotions are mostly what the shipped scorer
  ranked below its top, then the harness measures the shipped scorer on a population the deck draws
  from that scorer's own tail while measuring the challengers on the same population without that
  handicap, and the shipped scorer's whole-population rate would fall with each session the deck
  deals from it. One reading cannot show a trend; this one shows the shipped rate within a point of
  the fourth reading's after a full session, which a strong version of the effect would not
  predict. The next reading, after the next session, is the first that can say whether the rate
  falls further, and the deck's candidate order is the thing a later issue would change if it does.
  This amendment decides nothing about that.
- **The negatives condition was dropped rather than satisfied**, so nothing here is a finding about
  the negatives column; the cells are quoted above, on the entity-fold scale, and decided nothing.
- **The halves are drawn on the last write**, so an old promotion re-rated in a session is in the
  new half; the census deltas on issue #278 bound how many ratings changed, not how many are new.
  The reading above does not depend on the boundary being exact: it rests on the scorers' patterns
  being opposite over the same halves.
- **It says nothing about the entities ingest cannot reach.** Rates are read over the reachable
  (ADR 65's consequence), so this is not a verdict on expansion coverage.

### Consequences of this amendment

- **Nothing in the tool moves**, so no ranking, no deck and no output line changes.
- **`Setting.GRID` and `HeldOut.EVERY` are unchanged.**
- **The next reading worth taking follows the next deck session, as the amendment for issue #272
  said**, taken with the same instant so its halves are comparable to these, and the question it
  can answer is whether the shipped rate falls further while the challengers' hold.
- **Nothing here is unit-testable, and that is said out loud rather than left implied.** No
  behaviour changed, so there is no test to write and nothing to see red. The verification is the
  full gate over an otherwise unchanged tree — `AdrIndexTest`, `AdrCitationsTest`,
  `DocumentationLinksTest` for the relative links above, and `javadoc -Werror` inside
  `./gradlew check` — together with the ruling that applied the rule cell by cell and a byte
  comparison of the block above against the owner's paste.


**Amendment (2026-09-07, issue #280): the sixth reading, read against the fifth by the sentence
written before it was taken, held — the shipped setting stands and nothing needs changing yet.**

Nothing above is withdrawn and no decision above is edited, including the five amendments
immediately above this one. No constant changed and no code changed. The reading was taken after the
owner's third deck session, with the same instant as the fifth reading, so the old half is the same
population as the fifth's; how many ratings the session moved is on issue #280 from the census's `taste` section, read
before the harness ran.

**The rule was fixed before the number existed, for the sixth time, and so were both verdicts.** The
rule is the one described four amendments above and it was not changed. A dated note was committed
and pushed on the morning of 2026-09-07, before the reading the owner pasted carrying an 11:37
timestamp, appended to `docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`; pull
request #281 carries it. The note kept the fifth reading's instant, named the cross-reading
observations this amendment may make — the shipped row's whole-population rate, its two half rates,
and each challenger's whole-population rate at the shipped floor, all against the fifth — and wrote
both sentences this amendment could end with, one for "fell" and one for "held", with "held"
defined as a change inside a few points. The rule is the authority on what would have counted and is
not restated here.

**The reading.** One run of `./gradlew evaluate` with `--rated-since` on the owner's database on
2026-09-07, on the folded harness, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, every
label is a column name or a `Scorer` spelling, and the header names the folds and the instant.
`in pool`, `hits` and the four half cells count entities; `pool` and `negatives` count entity-folds.
On every row the two halves sum to the whole-population cell.

```
# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings (ADR 51, ADR 63, ADR 65).
# held out every 5 of 288 eligible entity(ies), in 5 fold(s): 288 held out over all folds, at least 230 left on the known-list in each.
# split by rating age at 2026-09-06T18:56:00Z: 152 old (rated before it), 136 new (rated on or after it) — a rating's timestamp is its last write, so a re-rated old promotion counts as new.
# top 25 per setting, over 16 setting(s).
scorer               floor   pool  in pool  hits  mean rank  negatives  neg mean rank  old in pool  old hits  new in pool  new hits
raw                      2  16150      288    26       11.3         19           18.5          152        17          136         9
raw                      5   7109      288    26       11.3         19           18.5          152        17          136         9
raw                      8   5561      211    26       11.3         19           18.5          115        17           96         9
raw                     12   4667      165    25       10.9         19           18.5           93        16           72         9
adamic-adar              2  16150      288    34       10.0         16           18.6          152        19          136        15
adamic-adar              5   7109      288    34       10.0         16           18.6          152        19          136        15
adamic-adar              8   5561      211    34       10.0         16           18.6          115        19           96        15
adamic-adar             12   4667      165    34       10.0         16           18.6           93        19           72        15
resource-allocation      2  16150      288    36        9.6         15            8.1          152        19          136        17
resource-allocation      5   7109      288    36        9.6         15            8.1          152        19          136        17
resource-allocation      8   5561      211    36        9.6         15            8.1          115        19           96        17
resource-allocation     12   4667      165    36        9.6         15            8.1           93        19           72        17
lift                     2  16150      288     0          -          6           18.8          152         0          136         0
lift                     5   7109      288    41       10.7         65           12.0          152        37          136         4
lift                     8   5561      211    40       12.7         43           10.5          115        32           96         8
lift                    12   4667      165    25       12.2         35           10.9           93        18           72         7
```

**What the rule made of it.** Clause 2's void check was run first and did not fire. Clause 3(a)
compared every non-shipped scorer's rate at the shipped floor with `lift`'s row there, and each is
below `lift`'s. Clause 3(b) derived the dominance range as floors eight and twelve; at floor eight
every challenger's rate is below `lift`'s; at floor twelve two challengers are above it and the third
exactly equals it, which the rule saw and which changed nothing, because a scorer moves only on both
conditions and none cleared the first. No scorer moved, so clause 4 compared `lift`'s rate at every
other floor with its rate at the shipped floor: floor eight is the closest and clears under a third
of the margin, which clause 7 says is a stand; floor twelve is above the shipped floor's rate by
less than that; floor two is where `lift` records no hits. The `negatives` and `neg mean rank` cells
were read for every row and, per clause 5, decided nothing. Outcome: the shipped setting stands.

**The observations the note allowed, and the sentence they select.** Against the fifth reading, the
shipped row's whole-population rate moved by about a point — under a tenth of the margin — inside
the few points the note defined as "held"; its old half moved by less than a point and its new half by about a point, and stays a
few points above zero; each challenger's whole-population rate at the shipped floor moved by less
than a point. So the note's second sentence applies, and it is written here as the note wrote it:
the fifth reading's fall is consistent with a one-time exhaustion of the shipped ranking's top, and
nothing needs changing yet.

### What this does and does not establish

- **It does not establish that the shipped setting is the best one.** It establishes that on this
  reading, by this rule, nothing displaced it. ADR 65's first consequence is the governing one: no
  row of that table means anything on its own.
- **It does not close the question the fifth reading raised; it defers it by the criterion fixed in
  advance.** The shipped row's new half is still a few points above zero after a third session, and
  a session this small — the census deltas on issue #280 bound it — cannot move a rate whose
  denominator is the whole row's `in pool` by more than a point or two. A reading that could show the
  rate
  falling with each session needs sessions large enough for the movement to clear "a few points",
  or more of them, and the note's criterion was written for that reason rather than against it.
- **The negatives condition was dropped rather than satisfied**, so nothing here is a finding about
  the negatives column; the cells are quoted above, on the entity-fold scale, and decided nothing.
- **The halves are drawn on the last write**, so an old promotion re-rated in a session is in the
  new half; the census deltas on issue #280 bound how many ratings changed, not how many are new.
- **It says nothing about the entities ingest cannot reach.** Rates are read over the reachable
  (ADR 65's consequence), so this is not a verdict on expansion coverage.

### Consequences of this amendment

- **Nothing in the tool moves**, so no ranking, no deck and no output line changes, and the deck's
  candidate order is not the next issue yet.
- **`Setting.GRID` and `HeldOut.EVERY` are unchanged.**
- **The next reading worth taking follows a larger deck session, or several**, with the same
  instant, and is read against this one by the same criterion the note fixed. If the shipped rate
  then falls by more than a few points while the challengers' hold, the deck's candidate order is
  the next issue; if it holds again, the note for that reading says what follows.
- **Nothing here is unit-testable, and that is said out loud rather than left implied.** No
  behaviour changed, so there is no test to write and nothing to see red. The verification is the
  full gate over an otherwise unchanged tree — `AdrIndexTest`, `AdrCitationsTest`,
  `DocumentationLinksTest` for the relative link above, and `javadoc -Werror` inside
  `./gradlew check` — together with the ruling that applied the rule cell by cell and a byte
  comparison of the block above against the owner's paste.


**Amendment (2026-09-07, issue #282): the seventh reading, read against the sixth by the sentence
written before it was taken, held again — the shipped setting stands, and the question the fifth
reading raised rests until a larger session has been rated.**

Nothing above is withdrawn and no decision above is edited, including the six amendments
immediately above this one. No constant changed and no code changed. The reading was taken after the
owner's fourth deck session, with the same instant as the fifth and sixth readings, so the old half
is the same population as theirs; how many ratings the session moved is on issue #282 from the
census's `taste` section, read before the harness ran.

**The rule was fixed before the number existed, for the seventh time, and so were both verdicts.**
The rule is the one described five amendments above and it was not changed. A dated note was
committed and pushed at midday on 2026-09-07, before the reading the owner pasted carrying a 12:21
timestamp, appended to `docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`; pull
request #283 carries it. The note kept the instant, named the same four cross-reading observations
as the sixth reading's note — the shipped row's whole-population rate, its two half rates, and each
challenger's whole-population rate at the shipped floor, now against the sixth — and wrote both
sentences this amendment could end with, with "held" meaning a change inside a few points. The rule
is the authority on what would have counted and is not restated here.

**The reading.** One run of `./gradlew evaluate` with `--rated-since` on the owner's database on
2026-09-07, on the folded harness, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, every
label is a column name or a `Scorer` spelling, and the header names the folds and the instant.
`in pool`, `hits` and the four half cells count entities; `pool` and `negatives` count entity-folds.
On every row the two halves sum to the whole-population cell. For the first time on a real reading,
the header's eligible count and the split line's new-half count each exceed the rows' `in pool` at
the lowest floors: an eligible entity the sweep does not reach even at the lowest floor, and so —
since `CandidateSweep.over`'s pool only shrinks as the floor rises — at no floor in the grid. That is
the consequence ADR 65 wrote for, and clause 1 already reads the row's own `in pool` as the
denominator rather than the eligible count.

```
# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings (ADR 51, ADR 63, ADR 65).
# held out every 5 of 299 eligible entity(ies), in 5 fold(s): 299 held out over all folds, at least 239 left on the known-list in each.
# split by rating age at 2026-09-06T18:56:00Z: 152 old (rated before it), 147 new (rated on or after it) — a rating's timestamp is its last write, so a re-rated old promotion counts as new.
# top 25 per setting, over 16 setting(s).
scorer               floor   pool  in pool  hits  mean rank  negatives  neg mean rank  old in pool  old hits  new in pool  new hits
raw                      2  16102      298    26       10.2         17           18.5          152        17          146         9
raw                      5   7059      298    27       10.7         17           18.5          152        17          146        10
raw                      8   5512      220    27       10.7         17           18.5          115        17          105        10
raw                     12   4621      175    26       10.4         18           18.8           93        16           82        10
adamic-adar              2  16102      298    33        9.5         16           18.9          152        18          146        15
adamic-adar              5   7059      298    33        9.5         16           18.9          152        18          146        15
adamic-adar              8   5512      220    33        9.5         16           18.9          115        18          105        15
adamic-adar             12   4621      175    33        9.5         16           18.9           93        18           82        15
resource-allocation      2  16102      298    35        9.4         15            8.3          152        18          146        17
resource-allocation      5   7059      298    35        9.4         15            8.3          152        18          146        17
resource-allocation      8   5512      220    35        9.4         15            8.3          115        18          105        17
resource-allocation     12   4621      175    35        9.4         15            8.3           93        18           82        17
lift                     2  16102      298     0          -          6           19.7          152         0          146         0
lift                     5   7059      298    43       10.7         66           12.3          152        39          146         4
lift                     8   5512      220    38       11.5         45           11.2          115        32          105         6
lift                    12   4621      175    23       11.2         34           10.7           93        18           82         5
```

**What the rule made of it.** Clause 2's void check was run first and did not fire. Clause 3(a)
compared every non-shipped scorer's rate at the shipped floor with `lift`'s row there, and each is
below `lift`'s. Clause 3(b) derived the dominance range as floors eight and twelve; at floor eight
every challenger's rate is below `lift`'s; at floor twelve all three are above it, which the rule
saw and which changed nothing, because a scorer moves only on both conditions and none cleared the
first. No scorer moved, so clause 4 compared `lift`'s rate at every other floor with its rate at the
shipped floor: floor eight is the closest and clears under a fifth of the margin, which clause 7
says is a stand; floor twelve is below the shipped floor's rate; floor two is where `lift` records
no hits. The `negatives` and `neg mean rank` cells were read for every row and, per clause 5,
decided nothing. Outcome: the shipped setting stands.

**The observations the note allowed, and the sentence they select.** Against the sixth reading, the
shipped row's whole-population rate moved by a fraction of a point, well inside the few points the
note defined as "held"; its old half moved by about a point and its new half by a fraction of one,
and stays a few points above zero; each challenger's whole-population rate at the shipped floor
moved by less than a point. So the note's second sentence applies, and it is written here as the
note wrote it: after two consecutive holds the question rests until a session large enough to move
a whole-row rate by more than a few points has been rated — the census's `taste` deltas say when
that has happened — and no further reading is worth taking on a smaller one.

### What this does and does not establish

- **It does not establish that the shipped setting is the best one.** It establishes that on this
  reading, by this rule, nothing displaced it. ADR 65's first consequence is the governing one: no
  row of that table means anything on its own.
- **It rests the question the fifth reading raised, by the criterion fixed in advance, and does not
  answer it.** This is the second consecutive hold; the amendment for issue #280 recorded the first.
  The new half has stayed a few points above zero through both. A larger session is the reading
  that can say, and the note says what "larger" means.
- **The negatives condition was dropped rather than satisfied**, so nothing here is a finding about
  the negatives column; the cells are quoted above, on the entity-fold scale, and decided nothing.
- **The halves are drawn on the last write**, so an old promotion re-rated in a session is in the
  new half; the census deltas on issue #282 bound how many ratings changed, not how many are new.
- **It says nothing about the entities ingest cannot reach**, and this reading has one: the
  eligible entity no floor reaches is a miss for a reason no knob the harness sweeps can change,
  exactly as ADR 65's consequence says.

### Consequences of this amendment

- **Nothing in the tool moves**, so no ranking, no deck and no output line changes, and the deck's
  candidate order is not the next issue.
- **`Setting.GRID` and `HeldOut.EVERY` are unchanged.**
- **The next reading worth taking follows a deck session the census shows to be large enough**, by
  the note's own definition, with the same instant, read against this one by the same criterion.
  A reading on a smaller session is not worth taking, as the note says.
- **Nothing here is unit-testable, and that is said out loud rather than left implied.** No
  behaviour changed, so there is no test to write and nothing to see red. The verification is the
  full gate over an otherwise unchanged tree — `AdrIndexTest`, `AdrCitationsTest`,
  `DocumentationLinksTest` for the relative link above, and `javadoc -Werror` inside
  `./gradlew check` — together with the ruling that applied the rule cell by cell and a byte
  comparison of the block above against the owner's paste.


**Amendment (2026-09-07, issue #289): the eighth reading, the first after every promotion was
expanded, stood the shipped setting by less than a point on the graph it now runs on.**

Nothing above is withdrawn and no decision above is edited, including the seven amendments
immediately above this one. No constant changed and no code changed. What changed is the graph: the
promotion expander ([ADR 66](0066-expand-every-promotion-from-a-dev-tool.md)) ran for the first time
on 2026-09-07, on every promotion, and the census before and after is on issue #284. The ratings did
not move, so the eligible population is the seventh reading's exactly, and this is the first reading
since the folded baseline whose rows can be set beside the previous reading's row for row.

**The rule was fixed before the number existed, for the eighth time.** The rule is the one described
six amendments above and it was not changed. A dated note was committed and pushed at 18:49 on
2026-09-07, before the reading the owner pasted carrying an 18:52 timestamp, appended to
`docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`; pull request #290 carries it. The
note kept the instant, named the observations this amendment may make against the seventh reading —
`in pool` and `hits` on every row, the shipped setting's rate, the halves — and wrote three sentences
in advance for the cases it foresaw: the shipped rate rising by more than a few points, reach rising
while the rate held, and reach not rising. The rule is the authority on what would have counted and
is not restated here.

**The reading.** One run of `./gradlew evaluate` with `--rated-since` on the owner's database on
2026-09-07, on the folded harness, quoted whole and unedited. Aggregates only, per
[ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a one-decimal mean or a dash, every
label is a column name or a `Scorer` spelling, and the header names the folds and the instant.
`in pool`, `hits` and the four half cells count entities; `pool` and `negatives` count entity-folds.

```
# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings (ADR 51, ADR 63, ADR 65).
# held out every 5 of 299 eligible entity(ies), in 5 fold(s): 299 held out over all folds, at least 239 left on the known-list in each.
# split by rating age at 2026-09-06T18:56:00Z: 152 old (rated before it), 147 new (rated on or after it) — a rating's timestamp is its last write, so a re-rated old promotion counts as new.
# top 25 per setting, over 16 setting(s).
scorer               floor   pool  in pool  hits  mean rank  negatives  neg mean rank  old in pool  old hits  new in pool  new hits
raw                      2  18004      298    35       10.7         16           19.8          152        21          146        14
raw                      5   7586      298    35       10.7         16           19.8          152        21          146        14
raw                      8   5717      277    35       10.7         16           19.8          149        21          128        14
raw                     12   4751      255    35       10.7         16           19.8          145        21          110        14
adamic-adar              2  18004      298    39        9.1          8           20.0          152        24          146        15
adamic-adar              5   7586      298    39        9.1          8           20.0          152        24          146        15
adamic-adar              8   5717      277    39        9.1          8           20.0          149        24          128        15
adamic-adar             12   4751      255    39        9.1          8           20.0          145        24          110        15
resource-allocation      2  18004      298    44       10.8         14           10.7          152        25          146        19
resource-allocation      5   7586      298    44       10.8         14           10.7          152        25          146        19
resource-allocation      8   5717      277    44       10.8         14           10.7          149        25          128        19
resource-allocation     12   4751      255    44       10.8         14           10.7          145        25          110        19
lift                     2  18004      298     0          -          7           18.1          152         0          146         0
lift                     5   7586      298     2       17.5         48           12.7          152         1          146         1
lift                     8   5717      277    21       14.4         46           11.2          149        16          128         5
lift                    12   4751      255    31       13.8         32           11.1          145        23          110         8
```

**What the rule made of it.** Clause 2's void check was run first and did not fire. Clause 3(a)
compared every non-shipped scorer's rate at the shipped floor with `lift`'s row there, and for the
first time every one of them is above it: the weakest by some eleven points, the strongest,
`resource-allocation`, by fourteen — under a point short of the fifteen the clause demands. Clause
3(b) derived the dominance range as floors eight and twelve, and every challenger's rate is above
`lift`'s at both — with the repetition the rule asks to be recorded: every challenger's `hits` cell
is the same at every floor in the grid, so the two floors are one comparison read at two
denominators. So every challenger cleared the dominance condition and none cleared the margin, and a
scorer moves only on both. No scorer moved, so clause 4 compared `lift`'s rate at every other
floor with its rate at the shipped floor: floor twelve is the closest, clearing about three quarters
of the margin; floor eight clears about half; floor two is where `lift` records no hits. The
`negatives` and `neg mean rank` cells were read for every row and, per clause 5, decided nothing.
Clause 7 says a near miss stands, and this is the nearest miss the rule has seen. Outcome: the
shipped setting stands.

**The observations the note allowed.** Reach rose where the run could raise it: `in pool` is
unchanged at the two lowest floors, where every eligible entity the sweep reaches was already
reached, and rose at floors eight and twelve by about a quarter and nearly a half. Every challenger's
`hits` cell rose against the seventh reading at every floor. The shipped setting's `hits` cell fell
at the shipped floor to a few, its rate falling by about fourteen points against the seventh —
nearly the whole of the margin. On the halves, the shipped row now misses both halves nearly alike, where before it held
the old half and missed the new.

**None of the note's three sentences applies, and this amendment says so rather than stretch one.**
The note foresaw the rate rising, the rate holding while reach rose, and reach not rising. The table
is the fourth case: reach rose and the shipped rate fell, by more than the note's "few points" many
times over. What the aggregates support is this. The run added neighbours to every promotion, and
the census on issue #284 shows the additions are, in the main, low-degree nodes. The javadoc on `Scorer`
says of lift that it "rewards a thin entity whose whole presence in the graph is a list of influences",
which is why ADR 45 paired it with a degree floor, and why floor two is where `lift` records no
hits. The expansion put thin entities adjacent to exactly the entities the owner rates highly, most of
them at degrees the shipped floor admits, and the shipped setting's top twenty-five is consistent
with being filled by them. The other three scorers, which do not divide by the candidate's degree, are
consistent with being helped by the same additions. That is an explanation the table is consistent
with; it is not a ruling, and this amendment moves nothing.

### What this does and does not establish

- **It does not establish that the shipped setting is the best one**, and for the first time it
  does not suggest it either. It establishes that on this reading, by this rule, nothing displaced
  it, by a margin under a point. ADR 65's first consequence is the governing one: no row of that
  table means anything on its own — and the rule was written so that no single reading could move a
  constant on a near miss, which is what it has just refused to do.
- **The shipped setting was decided on a graph that no longer exists**, which ADR 66 and the census
  on issue #284 establish rather than this table. ADR 45 chose `lift` at a floor of five on the
  graph as ingest had left it; the expander changed that graph's shape around the owner's
  promotions, and what this table adds is how the setting reads on the new one. Whether to re-decide the setting is a decision, not a reading, and it is the next issue:
  a rule written now cannot be blind to this table, and the issue that writes one must say so.
- **The rating deck deals its candidate cards from the shipped setting** (ADR 46, `RateRun`), so
  what this table says about the shipped setting's top twenty-five is what the deck will deal until
  that decision is taken. Recorded so the owner does not rate through it unknowing.
- **The negatives condition was dropped rather than satisfied**, so nothing here is a finding about
  the negatives column; the cells are quoted above, on the entity-fold scale, and decided nothing.
- **It says nothing about the entities ingest cannot reach.** Rates are read over the reachable
  (ADR 65's consequence); reach rose, and this amendment records that the rule did not.

### Consequences of this amendment

- **Nothing in the tool moves**, so no ranking, no deck and no output line changes.
- **`Setting.GRID` and `HeldOut.EVERY` are unchanged.**
- **Whether to re-decide the shipped setting on the expanded graph is the next issue's decision**,
  with this table as its evidence. A further reading on an unchanged database would print this
  block again, since the split and the sweep are deterministic (`HeldOut`).
- **Nothing here is unit-testable, and that is said out loud rather than left implied.** No
  behaviour changed, so there is no test to write and nothing to see red. The verification is the
  full gate over an otherwise unchanged tree — `AdrIndexTest`, `AdrCitationsTest`,
  `DocumentationLinksTest` for the relative links above, and `javadoc -Werror` inside
  `./gradlew check` — together with the ruling that applied the rule cell by cell and a byte
  comparison of the block above against the owner's paste.

**Amendment (2026-09-07, issue #291): the default scorer moves to `resource-allocation`. A decision
taken outside the rule, on the graph the expander left, with the eighth reading as its evidence and
its post hoc position on the record.**

Nothing above is withdrawn and no decision above is edited, including the eight amendments
immediately above this one. This ADR's title and its Decision section's "defaulting to lift" are
history: they record what was decided, and measured, on a graph that no longer exists, and they are
not edited to match what the code now does.
[ADR 50](0050-suppress-a-candidate-you-have-rejected.md)'s sentence naming `LIFT` "the measured
default" is history in the same way, and so is
[ADR 65](0065-an-offline-evaluation-harness-for-the-recommender.md)'s account of the grid's lowest
floor as the point below which "a normalised score stops meaning anything": true of the default it
was written under, and the reason `CandidateSweep` gives for the floor is the one that holds now.
What changes is one constant,
`Recommendations.DEFAULT_SCORER`, and every sentence in the tree that named its value.

**This is a decision, and it is openly post hoc.** The rule of issue #245 was written so that no
single reading could move a constant on a near miss, and on the eighth reading — the amendment
immediately above, for issue #289 — it refused to move this one. That refusal was correct and it is
not overturned here: no clause is edited, no threshold is softened, and nothing below re-reads the
rule "in the spirit of" anything. What is recorded here is a decision taken outside it, with the
eighth reading as evidence, and with the admission that amendment's own last section demanded — a
rule written today cannot be blind to the table it would be written after. Deciding in the open is
the honest form of that; writing a new rule that happens to reach the answer already known is not.

**What the eighth reading showed.** The amendment immediately above is the authority, its table is
quoted there once, and no cell or figure of it is restated here. By reference: at the shipped floor
every other scorer in the grid is above the shipped scorer's hit rate for the first time, the best
of them is `resource-allocation`, and it fell short of the rule's margin by less than a point while
clearing the rule's dominance condition at every floor in the derived range. Its two halves, split
by rating age, land near each other where the shipped setting's now miss both nearly alike. That
amendment also records the hazard this one acts on: the rating deck deals its candidate cards from
the shipped setting, so what the table says about that setting is what the deck deals until a
decision is taken.

**Why the expansion did this to `lift` and to no other point on the dial.** `Scorer.score` divides
by the candidate's own degree for exactly one point: `normalisedByCandidateDegree` is true for
`LIFT` alone. `Scorer`'s own javadoc says of it that it "rewards a thin entity whose whole presence
in the graph is a list of influences", which is why this ADR paired it with a degree floor rather
than shipping it alone. The promotion expander
([ADR 66](0066-expand-every-promotion-from-a-dev-tool.md), issue #284) added neighbours to every
promotion; the census on that issue is the authority on what it added. A thin node adjacent to
several of the owner's highest-rated entities is precisely what that divisor rewards, and it is
precisely what the three scorers that do not divide by the candidate's degree do not reward. The
eighth amendment reached this explanation from the aggregates and declined to rule on it. This
amendment acts on it, and names it as the reason it acts rather than as a finding the table
establishes.

**Alternatives, each rejected, with the reason each lost.**

- **Keep `lift` and raise the floor to twelve.** The eighth reading leaves `lift` at the highest
  floor in the grid still some points behind the best challenger at the shipped floor, so this buys
  back less than the move does. It also spends the harness's *other* knob to repair the first, and
  #245's rule allows one constant to move; spending it on the floor leaves the scorer question open
  and nothing left to move next time.
- **Keep the setting and rate through it.** The deck deals from the shipped setting
  ([ADR 46](0046-the-rating-deck.md), `RateRun`), so this spends a deck session rating the top
  twenty-five of a setting that the reading shows finding almost nothing at the shipped floor. The
  eighth amendment recorded that hazard in as many words so the owner would not rate through it
  unknowing; reading that and doing it anyway is the one option the record forbids.
- **Retract the expansion.** Reach rose where the run could raise it, so the expansion improved
  the instrument's coverage rather than damaging it. And the log is append-only by design
  ([ADR 24](0024-sqlite-assertion-log.md), [ADR 44](0044-retraction-as-a-new-claim.md)): retracting
  is a new claim, not an undo, and there is nothing here worth spending one on.
- **Write a new rule and take a ninth reading.** The harness is deterministic — an unchanged
  database prints the same block, which is `HeldOut`'s design and the eighth amendment's own
  consequence — so a ninth reading taken before anything moves is the eighth reading. A rule written
  now would be written by somebody who has read that table. That is a decision wearing a rule's
  costume, and it is worse than a decision that says what it is.

**What this costs, and it is a real cost.** This ADR chose `lift` for one property: connected to you
more than its size predicts — surprise rather than popularity — and that property is given up at the
default. `resource-allocation` discounts the busy intermediate and nothing else, and this ADR's own
argument against stopping there is not withdrawn: a candidate connected to everything shares its
intermediates with everything. What the eighth reading says is that on the graph as it now stands,
the point that keeps the property finds almost nothing at the shipped floor and the point that gives
it up finds the most. The property is one flag away — `--scorer lift` at the unchanged floor is
exactly the setting shipped until today — which is why `--scorer` exists, and the honest way to
disagree with this amendment is to run both and read the two lists.

**What it does to the harness, and what the ninth reading must say.** The deck's sweep scores with
`Recommendations.DEFAULT_SCORER` by reference (issue #244), so from the owner's next session the
deck deals `resource-allocation`'s top twenty-five. The population the harness reads is what the
owner has rated, so the bias issue #272's amendment named — the deck's own cards shaping the
population the next reading judges — does not go away with this move; it moves with it. **The ninth
reading is the first taken after a deck session dealt from this default, and its note must say so
before the reading exists.** `Setting.GRID` and `HeldOut.EVERY` are unchanged, and
`evaluate` reads no default at all: it sweeps every scorer, so the harness needed no edit for this.

**The rule of issue #245 applies unchanged from here.** "The shipped scorer" is whatever
`Recommendations.DEFAULT_SCORER` holds, which is now `RESOURCE_ALLOCATION`; the shipped floor is
unchanged. Every clause, the fifteen-point margin, the dominance range, the void check, the
negatives deciding nothing, at most one constant moving, and a near miss standing — all as written.
This amendment is not a precedent for deciding outside the rule a second time: it records one
decision, the reason for it, and the fact that the rule declined to make it.

### What this does and does not establish

- **It does not establish that `resource-allocation` is the best scorer**, and no reading has said
  so under the rule. It establishes that the owner moved the default on the evidence of one table,
  knowing the rule refused to.
- **It does not establish why the shipped rate fell.** The explanation above is an explanation the
  aggregates are consistent with and an argument from `Scorer.score`'s arithmetic; it is the reason
  for the decision, not a finding.
- **It changes what the next reading is a reading of.** The deck deals from the new default from the
  next session, so the ninth reading's population is shaped by it.
- **It says nothing about the floor.** `Recommendations.MIN_CANDIDATE_DEGREE` is unchanged and
  unexamined here. Whether the floor measured for a normalised scorer is the right floor for one
  that does not normalise is a question for a reading, and it is not this amendment's.

### Consequences of this amendment

- **One constant moves.** `Recommendations.DEFAULT_SCORER` becomes `Scorer.RESOURCE_ALLOCATION`.
  `RecommendCli`'s `--scorer` default, the usage sentence it prints and `RateRun`'s sweep all read
  that constant (issue #244) and follow it without being edited — which is the property #244 bought
  and this amendment is the first to spend.
- **The ranking, the deck and the recommender's output all change**, together and by construction.
  `RateRunTest`'s guard is what holds them together: it reads the constant, and its fixture
  discriminates the shipped default from every other point on the dial.
- **`Recommendations.MIN_CANDIDATE_DEGREE`, `Setting.GRID` and `HeldOut.EVERY` are unchanged.**
- **This is unit-testable, and it was tested that way.** The guard's fixture was rebuilt to separate
  `resource-allocation` from every other scorer, seen red on the old constant, seen green on the
  new, and seen red again with the old constant planted back and with a `lift` literal planted into
  `RateRun`'s sweep. The rest of the gate — `AdrIndexTest`, `AdrCitationsTest`,
  `DocumentationLinksTest` for the relative links above, and `javadoc -Werror` inside
  `./gradlew check` — covers this amendment itself.
- **No commit hash is cited above.** The ordering facts this amendment leans on are the eighth
  reading's, and pull request #290 carries them.
- **The ninth reading is a follow-up issue**, taken after the owner's next deck session, with a note
  fixed before it that records the deck now deals from this default.
- **Half of what ADR 57's floor reading reports moves with this change, and the owner's next run is
  the first to show it.** `FloorReading`'s fields split in two: `pool`, `poolMedianDegree`,
  `heldOut` and `heldOutAtDegreeOne` are counted from the floor and the sweep and do not depend on
  the scorer; `head`, `headMedianDegree`, `headOnTheFloor` and `headEveryEdgeCounted` are counted
  from the ranked head and move with it. Under `lift` the head was pulled towards thin candidates
  (the 2026-08-29 amendment above reported the head median falling from 27 to 6); under
  `resource-allocation` there is no such pull, so `headMedianDegree` should rise and
  `headOnTheFloor` should fall on the very next run for that reason alone — a step that reads as
  drift and is the scorer, not the graph.

**Amendment (2026-09-08, issue #297): the ninth reading, the first judged with
`resource-allocation` as the shipped scorer, stood the shipped setting.**

Nothing above is withdrawn and no decision above is edited, including the nine amendments
immediately above this one; two sentences of the 2026-09-07 decision amendment are corrected
below by date, in place of an edit. No constant changed and no code changed. What changed is the
taste layer: the owner's first deck session dealt from the default that amendment set, and the
census on issue #297 shows the graph did not move — its node, edge and log-row totals are the ones
issue #284 recorded after the expander's run.

**The rule was fixed before the number existed, for the ninth time.** The rule is the one described
eight amendments above and it was not changed; "the shipped scorer" is what
`Recommendations.DEFAULT_SCORER` holds, as the amendment above says, and the shipped floor is
unchanged. A dated note was committed and pushed at 13:25 local on 2026-09-08, before the reading
the owner pasted carrying 13:38 and 13:40 timestamps, appended to
`docs/superpowers/specs/2026-09-04-second-reading-rule-design.md`; pull request #298 carries it. The
note kept the instant, said before the table was seen that the deck's bias had moved with the
default and that no inference about the scorer would be drawn from a new-half cell, named the
observations this amendment may make against the eighth reading, and wrote both outcomes in
advance. The rule is the authority on what would have counted and is not restated here.

**The reading.** One run of `./gradlew evaluate` with `--rated-since` on the owner's database on
2026-09-08, on the folded harness, quoted whole and unedited; the census taken before it is on
issue #297. Aggregates only, per [ADR 51](0051-what-an-adr-may-quote.md): every cell is a count, a
one-decimal mean or a dash, every label is a column name or a `Scorer` spelling, and the header
names the folds and the instant. `in pool`, `hits` and the four half cells count entities; `pool`
and `negatives` count entity-folds.

```
# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings (ADR 51, ADR 63, ADR 65).
# held out every 5 of 397 eligible entity(ies), in 5 fold(s): 397 held out over all folds, at least 317 left on the known-list in each.
# split by rating age at 2026-09-06T18:56:00Z: 152 old (rated before it), 245 new (rated on or after it) — a rating's timestamp is its last write, so a re-rated old promotion counts as new.
# top 25 per setting, over 16 setting(s).
scorer               floor   pool  in pool  hits  mean rank  negatives  neg mean rank  old in pool  old hits  new in pool  new hits
raw                      2  17799      397    49       12.2         26           19.2          152        23          245        26
raw                      5   7234      397    51       12.6         26           19.2          152        23          245        28
raw                      8   5328      375    51       12.6         26           19.2          149        23          226        28
raw                     12   4331      352    51       12.6         26           19.2          145        23          207        28
adamic-adar              2  17799      397    60       11.4         16           19.3          152        27          245        33
adamic-adar              5   7234      397    60       11.4         16           19.3          152        27          245        33
adamic-adar              8   5328      375    60       11.4         16           19.3          149        27          226        33
adamic-adar             12   4331      352    60       11.4         16           19.3          145        27          207        33
resource-allocation      2  17799      397    62       11.5         18           13.4          152        26          245        36
resource-allocation      5   7234      397    62       11.5         18           13.4          152        26          245        36
resource-allocation      8   5328      375    62       11.5         18           13.4          149        26          226        36
resource-allocation     12   4331      352    62       11.5         18           13.4          145        26          207        36
lift                     2  17799      397     0          -          7           18.4          152         0          245         0
lift                     5   7234      397     3       15.7         47           12.8          152         1          245         2
lift                     8   5328      375    20       14.3         46           11.9          149        14          226         6
lift                    12   4331      352    29       13.7         31           11.7          145        19          207        10
```

**What the rule made of it.** Clause 2's void check was run first and did not fire. Clause 3(a)
compared every non-shipped scorer's rate at the shipped floor with `resource-allocation`'s row
there, and none is above it: `adamic-adar` sits under a point below, `raw` a few points below, and
`lift` about the whole margin below. A scorer moves only on the margin and the dominance condition
together, so clause 3(b) was not reached and no scorer moved. Clause 4 compared the shipped scorer's
rate at every other floor with its rate at the shipped floor: its `hits` cell is the same at every
floor in the grid, so the rate rises only as `in pool` falls, and at floor twelve it clears about an
eighth of the margin. The `negatives` and `neg mean rank` cells were read for every row and, per
clause 5, decided nothing. Outcome: the shipped setting stands, and this is the first reading judged
with `resource-allocation` as the shipped scorer.

**The observations the note allowed.** Against the eighth reading, whose `resource-allocation` row
was not the shipped row then: the shipped row's `in pool` rose by about a third, its `hits` by about
two fifths, and its rate by under a point. The old half's `in pool` is unchanged, which the
instant being unchanged permits but does not compel — a re-rated old promotion would have moved to
the new half — and its `hits` moved by the least it could. The previously shipped row, `lift` at
the shipped floor, records a few hits in both readings, a rate under a point in each. At the shipped
floor every eligible entity is in pool.

**The halves are read as the note directed, and no further.** What the latest session added to the
new half of the shipped row is entities the deck offered from `resource-allocation`'s own ranking,
rated, and read back through the same ranking; the rest of that half was dealt under the previous
default, since the instant is unchanged from the fifth reading and the default changed only on
2026-09-07. That is the hazard the 2026-09-06 amendment for issue #272 named, arriving where the
decision amendment above said it would. The note said the whole new half was dealt from
`resource-allocation`; that overstated it, for the reason just given, and the note stands as it was
pushed. This amendment draws no inference about the scorer from any new-half cell and does not read
the new half's rate against the old half's; the cells are quoted above and that is where they stay.

**One correction, by date.** The decision amendment above describes the deck's deal as
`resource-allocation`'s "top twenty-five" twice: in the sentence naming what the deck deals from the
owner's next session, and in the rejected alternative "Keep the setting and rate through it". The
deck's candidate list is `RateCli.DEFAULT_CANDIDATES` deep, dealt a candidate every
`Deck.CANDIDATE_EVERY`th card, and twenty-five is the harness's per-setting head,
`RecommendCli.DEFAULT_TOP`. The code is the authority on all three numbers; both sentences conflated
the deck's depth with the harness's head, and no conclusion of that amendment turns on either.

### What this does and does not establish

- **It does not establish that the shipped setting is the best one.** It establishes that on this
  reading, by this rule, nothing displaced it, and that the rule applied unchanged with a scorer
  chosen outside it, as it has applied to every reading since the second. ADR 65's first consequence
  is the governing one: no row of that table means anything on its own.
- **It does not read the decision amendment's outcome off this table.** The population moved by
  the deck's own dealing from the setting under judgement, which the note said before the table
  was seen; a reading whose new half the scorer it judges has begun dealing cannot vouch for that
  scorer, and this amendment does not ask it to. The old half is the half not dealt from it, and its
  cells are quoted above for that reason, deciding nothing.
- **The negatives condition was dropped rather than satisfied**, so nothing here is a finding about
  the negatives column; the cells are quoted above, on the entity-fold scale, and decided nothing.

### Consequences of this amendment

- **The rule of issue #245 applies unchanged from here**, with `resource-allocation` as the
  shipped scorer and the floor unchanged, as the decision amendment above said it would.
- **The next reading follows the next deck session**, dealt from the same default, and its note is
  written before the reading exists, as every one so far has been. What it may compare against this
  reading is for that note to say.
- **Nothing here is unit-testable.** The harness has its own tests; no behaviour changed, so there
  is no test to write and nothing to see red. The verification is the note that preceded the
  reading, the ruling that applied the rule cell by cell, a byte comparison of the block above
  against the owner's paste, and the full gate over an otherwise unchanged tree — `AdrIndexTest`,
  `AdrCitationsTest`, `DocumentationLinksTest` for the relative links above, and `javadoc -Werror`
  inside `./gradlew check`.
