---
status: Accepted
date: "2026-08-28"
topic: main-subject-as-the-route-through-what-a-book-is-about
tags: [project, ingest, wikidata, graph]
supersedes: []
related: [award-received-as-the-first-non-collaboration-edge, wikidata-identity-and-vocabulary, reverse-lookup-via-sparql, path-ranking-by-confidence, recommend-by-normalised-lift-with-routes, six-kind-ontology]
---
# 47. Register main subject (P921), retargeted at the subjects the books actually state

## Context

[ADR 38](0038-award-received-as-the-first-non-collaboration-edge.md) fixed one half of a problem and
named the other half in its own opening paragraph: a vocabulary of co-credits and memberships models
music and film well and single-authored work badly. Awards repaired it for novelists, because the
field honours them and Wikidata records the honour.

**A shelf of technical books is the same failure one step further out**, and it defeats the award
repair too. *Clean Code* (`Q109996684`), *Structure and Interpretation of Computer Programs*
(`Q1249747`), *The Pragmatic Programmer* (`Q7758002`) and *Design Patterns* (`Q1283101`) have no
author in common — their `P50` sets are disjoint — and **none of the four states a `P166` at all**,
so the property ADR 38 admitted reaches none of them. Issue #111 records the consequence: they share
no node, so `find_paths` returns nothing between them. For the project's payoff feature that is
indistinguishable from being broken, which is the same sentence ADR 38 had to write about three
science-fiction novelists.

`EdgeTypes` is the authority on what the vocabulary holds; this ADR does not reproduce it.

### The measurement that made a hub property stop being a hub property

Issue #78 assumed `P921` was "the same shape or larger" than genre, and on that assumption a subject
edge was never going to be admitted. Issue #89 measured it against the subjects the owner actually
named rather than against famous ones, and the assumption is false.

Items pointing at one node through `P921`, from that measurement:

```
religion 796 · accounting 508 · finance 309 · science fiction 228 · hiking 157
operating system 40 · graph theory 34 · scuba diving 30 · computer network 27
Java 26 · birdwatching 25 · performance engineering 1
```

Against ADR 38's own measured rejections — occupation "novelist" **35,977**, genre "science fiction"
**16,552**, the largest record label **11,350** — and its single acceptance, *Hugo Award for Best
Novel* at **127**. The technical subjects are the size of an award node, not the size of a genre
node.

**The single number that decides it is science fiction, counted twice.** It is **16,552** as a genre
(`P136`) and **228** as a main subject (`P921`): the same concept, 72× smaller, measured on the same
day against the same endpoint. The reason is a modelling difference rather than an accident of
population, and issue #89 states it plainly: `P136` is what a creative work is *in*, so a genre node
accumulates an edge per work of that genre as a matter of course, while `P921` is stated on works
*about* a subject and is sparsely populated by comparison. A property is not a hub because of what
it means; it is a hub because of how widely it is stated, and these two are stated at completely
different rates.

### The second measurement, which retargeted the whole issue

Issue #111 was written to admit `P921` aimed at the subjects a person would name — Java, operating
systems, graph theory, object-oriented design. Measuring the other end killed that version of it.
**The owner's books do not say they are about those subjects.** All four of the books that carry
`P921` point at `computer programming` (`Q80006`) or `software engineering` (`Q80993`) or both.
*Design Patterns* additionally states `Q181156` (software design pattern) and `Q79872`
(object-oriented programming), and those four QIDs are the whole of what the four books state:
nothing on any of them points at Java, at graph theory or at operating systems. Book-shaped items per
named subject bear the same shape out: Java 12, birdwatching 12, computer network 9, performance
engineering 1, **object-oriented design 0**.

Admitted as originally planned, `P921` would have joined a technical shelf through nodes almost
nothing on it points at. The risk was never that the routes would be demoted. It was that they would
never exist.

## Decision

- **Register `P921` as `ABOUT`, and register nothing else.** One property, admitted on a
  measurement, argued in an ADR — ADR 38's shape deliberately, because the general selection rule
  ADR 38 left open (its question 1) is still open and this is not the change that answers it.

- **Aimed at `Q80006` (computer programming) and `Q80993` (software engineering)**, the two nodes the
  owner's books actually state, rather than at the subjects a person would name. Both labels and
  descriptions were confirmed against Wikidata rather than inferred from the QIDs.

- **DIRECT, and this corrects issue #111's own text.** The issue asserts that because `P921` is
  stated on the work pointing at the subject, it is `inverted` in ADR 22's sense. **That is wrong,
  and a reader who finds the issue should know it is wrong.** `inverted` means the *stored* direction
  reverses the *stated* one. `P50` is `inverted` because Wikidata states `book P50 person` and segue
  stores `person -AUTHORED-> book`. Wikidata states `book P921 subject` and segue wants
  `book -ABOUT-> subject` — the same direction — so by `EdgeType.direct`'s own contract this is
  `direct`. Nothing about "which end Wikidata states it on" settles the flag on its own; only the
  comparison with the direction segue wants to store does.

- **Not `fallbackOnly`.** ADR 36's issue-#33 condition is that Wikidata defines another property as
  this one's inverse *and* that property is already registered here. Checked at the source rather
  than by memory: `P921` states **no `P1696`** (inverse property) at all. What it does state is
  `P7087` → `Q70782961` "main subject of", which is an *item* Wikidata uses to generate an
  inverse-reading sentence in its own UI, not a registrable `Pxxx`. There is no second end to ingest
  and nothing to deduplicate.

- **Weighed as a new tier, `ABOUTNESS = 0.1`, strictly below `RECOGNITION = 0.2`.** The argument, in
  full, is below; `RecommendationWeights` is the authority on the table itself.

- **A subject arrives only as a neighbour of an ingested book, and is never an expansion seed.**
  This ADR states the constraint and does not enforce it. Enforcement is issue #112.

- **No third hub rule.** None was needed, on a thin and contingent margin — see the consequences.

### The weight, and the tuning that was declined

`ABOUT` is admitted for exactly the reason ADR 38 admitted `RECEIVED_AWARD`: a book whose authors
appear on nothing else the shelf holds has no collaboration to find, so a shelf of them needs a
relation that is neither co-credit nor prize. That argument justifies *admitting* the property. It
does not justify rating it as strongly.

An award is a body's comparative judgement — somebody looked at a field of candidates and chose. A
shared subject is two authors happening to write about the same topic, which two books about software
engineering do constantly without either author knowing the other exists. That is weaker evidence, so
`ABOUTNESS` sits below `RECOGNITION` rather than beside it.

**The awkward fact was confronted rather than used.** Issue #111's measurement found that 2,062 of
2,148 award nodes in the real graph — 96% — sit below `PathRanking.HUB_DEGREE`. Awards are therefore
not being held back by hub exclusion at all: the typical shared award is a non-hub and survives it,
and the issue reads that as a shared minor award already outranking a shared subject on hub count
before any weight applies. Weighing `ABOUT` above `RECOGNITION` would have fought that gap; weighing
it below compounds it.

(What the demonstration below actually observed is narrower than either reading: the two subjects
reached degree 3 and 2, so they were not hubs either, and no route through them was demoted. The
offsetting effect the issue predicts was not exercised, which is a reason to leave the number alone
rather than a reason to trust it.)

**The compounding is accepted, and the number was not tuned to offset it.** The reason to rank
aboutness low is independent of the hub arithmetic — the evidence really is weaker — and
`RecommendationWeights`' own javadoc keeps the two mechanisms separate on purpose: hub intermediates
are *excluded* before any weight applies, and a weight is what a relation is worth, not a correction
for how often it survives a different rule. Using the weight to offset a hub-survival side effect
would conflate them, and the next person reading either mechanism would find a number that means
something other than what its own documentation says it means.

## What this cannot reach

An ADR that implies the shelf is covered would be worse than no ADR at all. It is not covered.

Every row below was checked against live Wikidata while writing this, not carried over from the
issue text:

| Book | Item | `P921` |
| --- | --- | --- |
| Tanenbaum, *Computer Networks* | `Q18201424` — "book by Andrew S. Tanenbaum" | **none at all** |
| Bloch, *Effective Java* | **no Wikidata item exists** (a search returns only "Effective JavaScript", an unrelated 2013 article) | — |
| Evans, *Domain-Driven Design* | `Q100742558` — "2003 book by Eric Evans" | `Q6453666` modular programming, `Q524367` domain driven design |
| Cormen et al., *Introduction to Algorithms* | `Q1141518` — "book on computer programming" | `Q8366` algorithm |

**The last two rows correct the issue and the plan, which both say those books "carry no `P921` at
all".** They do carry it. What they do not do is point at `Q80006` or `Q80993`, so the practical
outcome is the same — they still do not route to the four books this admits — but the stated reason
was wrong, and a wrong reason invites the wrong fix. "Add the property" would not reach them; only
Wikidata gaining the statement, or those two subjects gaining their own bridges, would.

Two of the four are an upstream data gap that no work in this repository can close. The other two are
a subject mismatch, which a later measurement could revisit.

## Alternatives considered

- **`P136` genre.** The property a reader names first, and it would connect far more of the shelf
  immediately. Rejected on ADR 38's measurement, unchanged and re-confirmed here: at **16,552** items
  for one node, `Gibson → science fiction → Scalzi` is two perfectly-confident hops that explain
  nothing, being true of every science-fiction writer who ever lived. The whole reason `P921` is
  admissible is that the *same concept* counts 228 through it.
- **`P106` occupation.** Rejected on ADR 38's **35,977** for "novelist" — the number, not an
  argument about meaning. It is worth being clear that the design's own roles-are-edges invariant
  points the *other* way here, which is ADR 38's open question 5 and is still open. `seed` already
  reads `P106` as a resolver filter, which creates no edge and does not reopen this.
- **`P108` employer.** Refused, and — stated plainly — **not measured for this decision.** Issue #89
  named it as the interesting relation for newspapers-as-connectors and called it a vocabulary
  decision of its own. It is also employment rather than aboutness, so it does not belong in this
  admission whatever its size turns out to be.
- **`P27` country of citizenship.** Refused, and **also not measured for this decision.** It is
  self-evidently the shape this project rejects — "both American" connects everyone to everyone — but
  self-evidence is not a measurement, and this ADR does not pretend to have one. ADR 38's discipline
  is that a property is admitted on a number; the corollary is that an unmeasured property is refused
  by default, and admitting either of these later costs the measurement first.
- **Raise `HUB_DEGREE`.** Issue #111 measured the distribution against it: on the now
  123,752-node graph, 89 `CONCEPT`s sit at or above 10, which is 0.072% against ADR 31's recorded
  0.058% on a graph 4.8× smaller. The threshold has not drifted, so raising it would not be a
  correction — it would be turning the rule down to admit this property, which is the opposite of
  earning admission.
- **A third, aboutness-scoped hub rule.** Permitted by the issue and not needed by the retarget: the
  subjects reached degree 3 and 2 against a threshold of 10. Writing it now would mean writing a rule
  against a failure nobody has observed, and `PathRanking.HUB_DEGREE`'s own comment says what happens
  to a threshold nobody re-measures. If subject degrees do cross it, that measurement will exist by
  then and the rule can be written against it.
- **Solve the expansion flood here.** One `expand_entity` on a broad subject pulls up to the
  `ReverseClaims` cap: religion and accounting both hit the 501-row probe, giving degree **500** in a
  single call. The hazard is real and it is **not created by this ADR** — Java already expands to 91
  edges today through `P737`/`P361`, and the "only expand PERSON and GROUP" discipline lives in a
  scratch script rather than in the code. Fixing it properly is a decision with four live options and
  belongs to **issue #112**; bolting a narrow guard on here would foreclose that ADR's choices.
- **Do not admit `P921` at all.** Genuinely supported by the coverage numbers, and the option issue
  #111's measurement comment listed last. It was rejected because the retarget makes the win real
  rather than hypothetical: four books that returned nothing now return routes, demonstrated below.
  What it costs is honesty about the other four, which is what the section above is for.

## Consequences

- **Books that returned nothing now return routes, measured against real Wikidata data** on a copy of
  the real database, with only the four books added and expanded and nothing seeded by hand:
  - SICP ↔ *The Pragmatic Programmer* — 2 hops, both `ABOUT`, through `Q80006` computer programming.
  - *The Pragmatic Programmer* ↔ *Design Patterns* — 2 hops, both `ABOUT`, through `Q80993` software
    engineering.
  - *Clean Code* ↔ *Design Patterns* — **4 hops, not 2.** They share no subject: Clean Code states
    only `Q80006` and Design Patterns does not state it. The route that exists bridges through *The
    Pragmatic Programmer*, which is about both subjects. This is the honest shape of the feature —
    two books route when something in the graph actually connects their subjects, and nothing invents
    a connection that is not there.

- **The margin under the hub rule is thin and contingent, and the demonstration was small.** The
  measured in-graph degrees were **3** for `Q80006` and **2** for `Q80993`, against
  `PathRanking.HUB_DEGREE = 10`. **Those numbers came from four books, not from a shelf**, and they
  are not a confirmation of the 4–9 range the plan predicted — a smaller degree obtained from fewer
  books is not independent evidence that the margin is safe. In this graph a subject's degree is the
  count of the owner's own books naming it, one forward edge per book, so the claim "no third hub
  rule is needed" rests on the shelf staying under ten books per subject. **The tenth book about
  software engineering crosses the threshold** — the test is `>=`.

  What crossing it costs is narrower than it sounds, and issue #111 measured that too: hub count is a
  *sort key* in `find_paths`, not a filter, so two books whose only connection is one subject produce
  routes that all carry the same intermediate, tie on that key and fall through to confidence — true
  even at degree 500. It bites where a competing route exists, and it bites harder in
  `./gradlew recommend`, where `PathRanking.isHub` **excludes** an intermediate rather than demoting
  it. So the thing to watch for is not routes disappearing; it is a subject quietly dropping out of
  recommendations while `find_paths` still shows it.

- **Both subjects are `CONCEPT`, which is what makes the hub rule applicable at all.** Checked
  against `KindMapper`: `Q80006` states three `P31` classes and `Q80993` states seven, and none of
  those ten is in the whitelist, so both fall through to `CONCEPT` — the same fallback ADR 38
  requires for an award node, and the kind ADR 31's issue-#52 amendment keys its demotion on.

- **The vocabulary now says something about *aboutness* as well as work and recognition**, and
  `RecommendationWeights` has a fourth tier. Adding an `EdgeType` still fails
  `RecommendationWeightsTest.everyRegisteredTypeIsNamed` until it is weighed, so the next one costs a
  decision rather than inheriting a default.

- **The subject-as-neighbour constraint is documented and unenforced.** `expand_entity` accepts any
  QID in the graph, and nothing in the code refuses a `CONCEPT` seed. A model asked to "expand
  everything you know about software engineering" would add hundreds of works nobody has read, on the
  correct side of ADR 19's append-only log — reachable only one entity at a time through ADR 44's
  retraction. **This ADR does not close that**; issue #112 is where it gets closed, and until it does
  the discipline is a human one.

- **ADR 38's open question 1 is still open.** This is the second property admitted on a measurement,
  which is still not the same as having a rule for selecting the next one. Two data points now exist
  where there was one, and the second is more useful than it looks: one concept counts 16,552 through
  `P136` and 228 through `P921`, which says the eventual rule has to be about *how widely a property
  is stated*, not about what the property means.
