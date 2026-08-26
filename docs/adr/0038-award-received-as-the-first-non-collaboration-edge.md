---
status: Accepted
date: "2026-08-25"
topic: award-received-as-the-first-non-collaboration-edge
tags: [project, ingest, wikidata, graph]
supersedes: []
related: [wikidata-identity-and-vocabulary, reverse-lookup-via-sparql, path-ranking-by-confidence, six-kind-ontology, mcp-tool-surface]
---
# 38. Register award received (P166), and only that, as the first non-collaboration edge

## Context

Every Wikidata-backed type in `EdgeTypes` modelled people working **together**: co-credits on one
work (`DIRECTED`, `AUTHORED`, `COMPOSED_FOR`, `ACTED_IN`, `PERFORMED`, `WROTE_SCREENPLAY_FOR`),
membership of one group (`MEMBER_OF`), or a work's relation to another work (`PART_OF`,
`BASED_ON`). That is a good model of music and film, where one work has a director, a composer and
a cast. It is barely a model of literature at all, because a novel has exactly one author.

Dogfooding made the consequence concrete. Three science-fiction novelists — William Gibson
(Q188987), John Scalzi (Q277308) and Martha Wells (Q6774606) — were added as people and expanded.
Each produced a substantial neighbourhood; **no pair of them shared a single node**, and
`find_paths` returned zero routes for all three pairs. Not "these are distant": no route at all,
which for the project's payoff feature is indistinguishable from being broken. Two writers whose
only connection is that the field honoured them both had no hop to travel along.

The obvious repairs are all "register another property", and the obvious candidates are genre,
occupation, movement and record label. They were measured against the Query Service rather than
argued about — the count of items pointing at one node through one property:

| property | node | items |
|---|---|---|
| P106 occupation | "novelist" | **35,977** |
| P136 genre | "science fiction" | **16,552** |
| P264 record label | the largest label | **11,350** |
| **P166 award received** | "Hugo Award for Best Novel" | **127** |

Two orders of magnitude, and it decides the shape of the fix. `Gibson → science fiction → Scalzi`
is two hops at perfect confidence and explains nothing — it is true of every science-fiction writer
who ever lived, and a vocabulary that connects everything to everything has stopped saying anything.
`Gibson → Hugo Award for Best Novel → Scalzi` is exactly the same shape and is a real segue.

*(The issue as filed recorded 14 for the Hugo node. Re-measured against WDQS while implementing
this: 127 items state P166 → Q255032, of which 51 are humans — the rest are the novels, which
Wikidata also records as recipients. The argument is unaffected and the corrected figure is the one
carried here; the genre and occupation figures reproduced exactly.)*

## Decision

- **Register P166 as `RECEIVED_AWARD`, and register nothing else.** Awards alone demonstrably fix
  the observed failure: all three novelists share the Hugo for Best Novel, Scalzi and Wells also
  share the Locus and the Bob Morane, Wells and Gibson the Nebula, and Scalzi and Gibson the Seiun.
  Those are precisely the pairs that returned nothing.
- **DIRECT, not inverted.** Wikidata states P166 on the recipient — `person P166 award` — so the
  subject stays on the left and the edge reads `William Gibson RECEIVED_AWARD Hugo Award for Best
  Novel`. Inverting it would file the award as the recipient of the person, and since a two-hop
  route works either way the only symptom would be that every citation `find_paths` prints reads
  backwards. That is why direction is asserted end to end and not only in `ClaimMapper`.
- **Not `fallbackOnly`.** ADR 36's issue-#33 condition is that a property is the other end of one
  already registered here. The award-side way of stating this fact is P1346 ("winner"), which this
  vocabulary does not register, so there is no second end being ingested and nothing to deduplicate.
- **An award node is a `CONCEPT`.** "Hugo Award for Best Novel" is P31 Q378427 (literary award),
  which `KindMapper` does not whitelist, so it falls through to `CONCEPT` — which is the right
  answer, not a near miss. ADR 21's six kinds stand; no new kind is wanted or added.
- **The acceptance criterion is executable, twice.** `SharedAwardRouteTest` pins the mechanism
  offline against `StubWikidataServer` and runs in `check`; `SharedAwardRouteLiveTest` runs the real
  criterion against the live API with nothing seeded by hand, and is the only one of the two that
  can notice the underlying data moving.
- **The negative case is half the criterion.** Steve Hofstetter (Q7612859), a comedian who shares no
  award with any of the three, must still connect to none of them. A hub property would have
  connected him as readily as it connected the novelists, and a test that only checked the positive
  pairs could not tell the two outcomes apart.

### Deliberately still open, and not settled by this ADR

This is a first step chosen because it produces a real graph to judge the harder questions against,
rather than deciding them in the abstract. None of the following is answered here, and nobody
should read this ADR as having answered them:

1. **The general hub-degree rule.** Is the selection criterion a threshold on hub degree, and what
   is it? Get that right and the next property is decided mechanically instead of argued one
   P-number at a time. This ADR registers one property on a measurement, which is not the same as
   having a rule. **Open.**
2. **Whether "shared kind" is an edge at all.** If "both write science fiction" should still
   influence recommendations without creating a hop, it has to be a node *attribute* feeding a
   scorer rather than an edge — and `NodeRecord` is `(qid, kind, label)`, with nowhere to put one.
   That is the architecturally significant half of the original question and it is untouched.
   **Open.**
3. **ADR 31 cannot demote a hub.** Ranking is by weakest confidence, and a hub edge is perfectly
   confident, so a meaningless route through a 16,552-item node would rank *top*. Either ADR 31
   gains a second dimension — specificity, as inverse hub degree — or hub properties never become
   edges. Registering P166 needed neither, because a 127-item node is not a hub. Amending ADR 31
   remains an amendment to a deliberate earlier decision. **~~Open.~~ Answered, 2026-08-26, issue
   #52 — see the amendment below.**
4. **The truncation conflict.** `ReverseClaims` orders by `DESC(?sitelinks)` and keeps the top *n*,
   and hubs have the most sitelinks — so a hub property would make `maxNewEdges` preferentially keep
   the useless edges. Measured for P166 and it does not arise (see Consequences), but the conflict is
   unresolved for the properties this ADR declined. **Open.**
5. **The roles-as-edges invariant.** CLAUDE.md and ADR 21 say "'musician', 'director', 'novelist'
   are ROLES expressed as edges" — and `P106 novelist` is a 35,977-degree hub, so the numbers argue
   against the design's own prescription for occupation. This ADR neither honours nor amends that
   invariant; it registers a property that is not a role. **Open, and to be resolved deliberately
   rather than by drift.**

**Amendment (2026-08-26, issue #52): the award decision stands, and it was measured against too
narrow a sample.**

Nothing here is withdrawn. P166 remains registered, DIRECT, not `fallbackOnly`, and an award node
remains a `CONCEPT` — that last one is load-bearing for the fix below and must stay true. What
this amendment adds is the distinction the evidence above could not see.

**The Hugo sample was not representative.** This ADR argued from *Hugo Award for Best Novel* at 127
recipients against genre at 16,552 and concluded that awards are specific. A curated seeding of 272
acts (30,685 edges, 25,590 nodes) showed the real axis is not size at all: it is **an award for a
work versus recognition of a career**. A Hugo says "this novel". A Walk of Fame star says "this
person was famous" — barely a relationship, and precisely the kind everyone notable accumulates, so
it connects everyone to everyone. Of the 25,525 nodes shared by two or more seeds, only 26 were
shared by ten or more, and they were nearly all of the second kind: a Walk of Fame star (76 edges),
the Rock and Roll Hall of Fame (64), the Grammy Lifetime Achievement Award (39), the Kennedy Center
Honors (25).

**Two fixes were measured and rejected before the third was chosen.**

- **A global recipient-count threshold at ingest** — option 1 of the issue, and the natural
  extension of this ADR's own argument. It does not separate them: the Rock and Roll Hall of Fame
  has 410 recipients globally and connects 64 seeds, while the Inkpot Award has 717 and connects 9.
  *Hugo Best Novel* (127) and the *Grammy Hall of Fame* (139) are near-identical in size and behave
  oppositely. Global size is simply not the signal; **in-graph** degree is.
- **Excluding career-recognition awards by P31 class** — semantically the right idea and only
  partially true in the data. It catches the Walk of Fame (`commemorative plaque`), the halls of
  fame (`hall of fame`), the Grammy Lifetime (`lifetime achievement award`), the Presidential Medal
  (`civil decoration`) and the CBE (`grade of an order`) — and **the Kennedy Center Honors is P31
  `award` and nothing else**, with 25 seeds. Time 100 and the National Medal of Arts escape it too.

**The fix is in ranking, not in ingest: ADR 31 gains a specificity dimension.** The edges stay;
routes through a high-degree `CONCEPT` intermediate are demoted. This is why "an award node is a
`CONCEPT`" above is now enforced by a test rather than merely recorded — a `KindMapper` entry that
placed awards elsewhere would switch the rule off silently. See ADR 31's amendment for the rule,
the threshold and the composition with confidence.

**Open question 1 is still open.** This answers how ranking survives a hub, not how the next
property is *selected*. Registering genre or occupation is still not licensed by this: at 16,552
and 35,977 items they would flood the bound before ranking ever saw them (question 4), which is a
different failure from the one issue #52 fixed.

## Alternatives considered

- **Register genre (P136) as well, or instead.** It is the property a reader would name first, it
  needs no new mechanism, and it would connect the three novelists immediately. Rejected on the
  measurement: at 16,552 items it makes every pair of science-fiction writers two perfectly-confident
  hops apart, and ADR 31 would rank that route *above* a specific one because a hub edge is not less
  confident, merely less informative. It would also fight the `DESC(?sitelinks)` truncation on day
  one. Every one of those objections is a question in the open list above; none has to be answered to
  ship awards.
- **Register occupation (P106), movement (P135) and record label (P264) too**, so the vocabulary
  gains "shared kind" in one pass. The largest single change and the one that would have made the
  graph least useful: at 35,977, 16,552 and 11,350 they are hubs by any threshold anyone would
  eventually choose, and adding them before there is a threshold is deciding question 1 by accident.
- **Derive a `COLLABORATED_WITH`-style edge between co-recipients**, so the award itself never
  appears in the graph. It keeps the node count down, and it throws away the explanation — "they both
  won the Hugo for Best Novel" is the citation, and a derived person-to-person edge cannot carry it.
  It also lands in ADR 23's quarantined tier by construction, for a fact Wikidata states outright.
- **Do nothing until the hub-degree rule exists.** Honest, and it leaves the payoff feature broken
  for an entire domain while a general rule is designed with no data to design it against. The
  award graph is what makes questions 1–5 answerable from evidence.

## Consequences

- **The three failing pairs now return routes, measured live**, with only the three people added and
  expanded and nothing seeded by hand:
  - Gibson ↔ Scalzi — 2 routes: through the Hugo Award for Best Novel, and through the Seiun Award
    for Best Translated Long Work.
  - Wells ↔ Scalzi — 3 routes: the Locus Award for Best Science Fiction Novel, the Hugo, and the Bob
    Morane award for best foreign novel.
  - Gibson ↔ Wells — 2 routes: the Nebula Award for Best Novel, and the Hugo.
  - Hofstetter ↔ each of the three — **0 routes**, which is the control holding.
- **The bound now spends slots on awards before it spends them on works, and at a tight bound that
  crowds out the specific neighbours.** This is worth stating precisely, because it is *not* the
  interaction ADR 36 warned about. The `DESC(?sitelinks)` ordering is untouched: P166 is stated on
  the recipient, so the reverse query returns no awards for a person seed at all. The crowding comes
  from ADR 36's other rule — forward claims are concatenated first, and a novelist's awards are all
  forward claims. Measured on Gibson, whose item states 13 P166 statements and exactly one other
  whitelisted forward claim:

  | `maxNewEdges` | award edges | other edges |
  |---|---|---|
  | 15 | 12 | 2 |
  | 30 | 12 | 16 |
  | 200 (the default) | 12 | 106 |

  At the default bound there is no crowding worth the name — 12 of 118. At 15 the expansion is
  almost entirely awards and his 82 authored works are gone. **No rework of the truncation is made
  here.** Forward-first exists because forward claims are the better-evidenced ones (they carry
  references and validity qualifiers; a truthy reverse triple carries neither), and that reasoning is
  unchanged. The observation is recorded so that whoever answers open question 4 has the measurement,
  and so that nobody is surprised by a small-bound expansion of a heavily-decorated person.
- **Award nodes are shared by construction, which is the point and also the risk.** Two people
  connected through the Hugo are connected through a node 127 items point at. That is small enough to
  be a fact about them and large enough that a future `get_entity` on an award is a long list. The
  degree is bounded by `maxNewEdges` like everything else.
- **`RECEIVED_AWARD` edges can reach ADR 23's 1.00 tier**, unlike anything the reverse pass
  discovers, because they arrive on the forward pass with their reference block intact. All three
  novelists' Hugo statements carry a P854 reference URL, so those hops are graded 1.00 rather than
  the 0.80 a truthy `wdt:` triple is capped at — the award routes are the best-evidenced routes in
  the graph, not merely the only ones. Unreferenced award statements still land at 0.80, so the two
  hops of one route can carry different confidences, and `PathRanking` orders by the weaker.
- **The vocabulary is now 14 types, and one of them is not a collaboration.** That is a small
  widening of what the graph means by "related", and it is the first time the vocabulary has said
  anything about *recognition* rather than *work*. The open questions above are the price of having
  taken that step on one property rather than on five.
