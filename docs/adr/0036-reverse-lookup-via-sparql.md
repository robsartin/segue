---
status: Accepted
date: "2026-08-25"
topic: reverse-lookup-via-sparql
tags: [project, ingest, wikidata, mcp]
supersedes: []
related: [wikidata-identity-and-vocabulary, source-adapter-spi, quarantine-model-generated-assertions, bitemporal-time-model, mcp-tool-surface]
---
# 36. Discover inverted relations with one SPARQL reverse lookup per expansion

## Context

Expansion only ever read the claims stated ON the entity being expanded, and Wikidata
states most of the vocabulary on the other end of the edge. Trialling the MCP server
end to end made the shape of that plain (issue #20):

| seed | kind | edges found |
|---|---|---|
| `Q192668` Nick Cave | PERSON | 4, all `MEMBER_OF` |
| `Q1051182` Nick Cave and the Bad Seeds | GROUP | **0** |
| `Q180337` The Proposition | WORK | 14 |

Same adapter, same graph, three seeds; only the work was productive. Six of the ten
registered edge types are `inverted` — `PERFORMED` (P175), `AUTHORED` (P50), `DIRECTED`
(P57), `WROTE_SCREENPLAY_FOR` (P58), `COMPOSED_FOR` (P86), `ACTED_IN` (P161) — and
Wikidata states every one of them on the work. `EdgeType.wikidataInverted` fixes the
stored *direction*; it does nothing about *discovery*. The group case is the same shape
again: a band's roster is P527 on the band, and membership in the useful direction is
P463 on each member.

A person is precisely what someone starts from in a "things I'm interested in" graph.
You begin at an artist you like, not at a filmography entry you already know. So the
payoff feature — `find_paths` — only worked for a user who had already seeded the works,
which is knowledge they would have had to be told.

Two candidate mechanisms were measured against the live APIs rather than argued about.

## Decision

- **One SPARQL query per expansion, against `https://query.wikidata.org/sparql`.** A
  `VALUES`-driven query asks the reverse question for the whole vocabulary at once:
  `VALUES ?p { wdt:P463 wdt:P527 … } ?other ?p wd:SEED`. Measured on Q192668: 86 rows,
  73 distinct works, HTTP 200 in 0.28s.
- **The property set is derived from the vocabulary, not hand-kept.** `ReverseClaims`
  reverses every property in `ClaimMapper.reverseProperties()` — which is `EdgeTypes`,
  minus the fallback-only types of the issue-#33 amendment below — for the same reason
  the forward whitelist is derived rather than duplicated. A hand-kept subset would stop
  covering a relation type the day someone registered one, which is this bug again one
  level down; note that the subtraction is derived from the vocabulary too, and is not a
  second list.
- **Direction reuses ClaimMapper's rule with the subject swapped.** A reverse hit means
  Wikidata holds `other P seed`, so it maps exactly as ClaimMapper would with
  `subject = other`. An inverted type yields `seed DIRECTED other`; a direct one yields
  `other PART_OF seed`. How an edge is stored does not depend on which pass found it.
- **`maxNewEdges` moves server-side, as `ORDER BY DESC(?sitelinks) LIMIT n+1`.** The n
  we keep are the most-linked rather than an arbitrary slice — for the Bad Seeds at
  n=15 that is Nick Cave, Blixa Bargeld, Mick Harvey and Warren Ellis ahead of a 2024
  album track — and the one extra row makes `truncated` an observation rather than a
  guess.

  **Amendment (2026-08-27, issue #71): the bound is a quality decision, not only a cost
  compromise, and the acts it truncates are deliberately left truncated.** A re-seed of the
  815-act list at `maxNewEdges=500` left 16 acts still reporting `truncated`, and the
  obvious reading was that they are under-served: J.S. Bach measured 200 → 200 edges,
  500 → 499, 1200 → 1174, never plateauing. Three mechanisms were on the table — a per-act
  override in the seed list, a "complete this one act" mode, and automatic escalation of any
  act that truncates. **None was built, because completing a truncated act does not improve
  its routes**, and the reason is a property this bullet already decided.

  The measurement, on a copy of the real 54,448-node graph: three of the 16 re-expanded into
  a second copy at `maxNewEdges=3000` — enough that all three came back `truncated=false` —
  and `find_paths` run over the same 21 pairs against both copies, through the same ADR 31
  ranking.

  | act | edges at 500 | edges complete | neighbours added | of those, ones that connect to anything else | seconds |
  |---|---|---|---|---|---|
  | Stephen King `Q39829` | 493 | 864 | 371 | **0** | 10.6 |
  | J.S. Bach `Q1339` | 499 | 1197 | 698 | **5** | 80.3 |
  | David Bowie `Q5383` | 500 | 662 | 156 | 15, six of them duplicate release items for two songs | 1.9 |

  - **Stephen King's 371 extra edges changed nothing whatsoever.** Every one of the 371
    entities they name has degree 1 — it touches King and nothing else in the graph — so his
    connector count is 33 before and 33 after, and the route counts to Edgar Allan Poe, the
    Ramones and Isaac Asimov are byte-identical across the two graphs: 63, 41 and 184 routes
    either way. A node that appears in no route is not a thinner answer, it is weight.
  - **Bach's 698 gained five connectors, and the top-ranked route was unchanged in 20 of the
    21 pairs.** The five are *After Bach*, *Christmas*, *Knife-Edge*, a Keith Jarrett
    Goldberg Variations recording, and a 2023 school Christmas concert in Zerbst. Where new
    routes did appear they arrived at rank 2 or 3 as exact ties — three hops, weakest
    confidence 0.80, identical to what they displaced — so they reshuffled the answer rather
    than improving it.
  - **The one ranking change that did happen was a regression.** Bach ↔ Paul Simon led with
    an influence chain and now leads with a concert programme:

    ```
    before:  Bach <-[INFLUENCED_BY]- The Beatles <-[INFLUENCED_BY]- Eels -[INFLUENCED_BY]-> Paul Simon
    after:   Bach -[COMPOSED_FOR]-> Christmas concert 2023: music school "Johann Friedrich Fasch" Zerbst
                  <-[COMPOSED_FOR]- Simon & Garfunkel <-[MEMBER_OF]- Paul Simon
    ```

    Both are three hops at 0.80, so the new one won on a tie, and "a school concert in 2023
    programmed pieces by both" is a coincidence of programming rather than a relationship.
    This is ADR 31's own argument arriving from a new direction: a route that means nothing
    is not made better by being true.
  - **For a well-connected act the extra routes are invisible anyway.** Every Bowie pair
    measured was already past `PathRanking.MAX_PATHS` — 2,952 raw routes to The Beatles,
    2,047 to Bob Dylan, 1,879 to Queen — so the 201, 101 and 52 routes completion added are
    below a cap the caller never sees past (issue #65). The single visible change was rank 3
    of Bowie ↔ Queen swapping one *Under Pressure* item for `Q137488650`, a duplicate item
    for the same song; three of Bowie's fifteen new connectors are *Under Pressure* and three
    more are *Peace on Earth/Little Drummer Boy*. Completing him mostly bought restatements
    of edges the graph already had — the clutter issue #67 is about, arriving through the
    tail of the bound.

  **Why this is a property of `DESC(?sitelinks)` rather than luck.** The bound does not take
  an arbitrary 500; it takes the 500 most-linked, and sitelink count is roughly the
  probability that some other seed in a personal graph also touches that item. So connector
  density falls off exactly where the bound cuts — **4.6% of Bach's first 496 neighbours
  connect to something else against 0.7% of the next 698; 7.0% against 0.0% for King; 31%
  against 9.6% for Bowie.** The tail beyond the bound is, by construction, the part of a
  catalogue that connects to nothing: individual BWV numbers, single releases, editions.
  Raising the bound buys nodes, not connectivity.

  **The honest caveat, and the trigger that would reopen this.** Every act other than the
  three was still bounded at 500 in both copies, so a tail work is partly a dead end because
  the acts that might have met it are bounded too. That is a real limit on the measurement
  and not one this ADR can dismiss — but it is bounded itself: those 698 works were dropped
  into a graph of 54,448 nodes built from 815 seeds, which is a great many chances to match,
  and 5 of them did. Re-measure if a future graph is complete for some reason of its own; do
  not raise the bound in order to find out, because completing all 815 acts at Bach's 80
  seconds is the cost the bound exists to avoid.
- **Neighbour identity rides along.** The query returns `?otherLabel` and `?type` (P31),
  and `ExpandResult` gained a `neighbors` list so `SegueService.expandEntity` uses them
  instead of fetching each neighbour. Without this the fix would be a regression: 73
  discovered works would mean 73 `wbgetentities` calls before a single edge could be
  recorded. A description is deliberately *not* selected — `NodeAssertion` has nowhere
  to put one, and fetching a field that is discarded is someone else's bandwidth.
- **P527 is registered as `HAS_PART`, as a degraded fallback only.** It makes a band
  expand to something without any Query Service call at all. It is not the answer:
  measured on Q1051182, P527 lists 8 members while reverse-P463 returns 10 — the same 8
  *plus Mick Harvey and Blixa Bargeld*, two of the most significant Bad Seeds. The two
  directions disagree and reverse-P463 strictly dominates, so P527 alone would quietly
  miss people.

  **Amendment (2026-08-26, issue #33): "fallback only" is now implemented, not just
  written.** As accepted, this bullet described an intent nothing enforced — P527 was
  registered like any other property, so it was read on every expansion, including the
  ones where the reverse pass answered. Wikidata defines P527 as the inverse of both P463
  and P361, the same relationship stated from the other end, so one membership was
  recorded as two edges: measured on the dogfooding graph, **4 of 23 `HAS_PART` edges
  duplicated a `MEMBER_OF` over the same pair.** Three mechanisms now make the sentence
  true, and all three are derived from one flag rather than from a list of property codes:
  - `EdgeType.fallbackOnly` marks the registration, so the vocabulary states it;
  - `ClaimMapper.reverseProperties()` subtracts fallback-only properties from the
    `VALUES` clause, so the reverse pass never asks the backwards question about both
    ends of an inverse pair (and `ReverseClaims` ignores such a row if one arrives
    anyway);
  - `WikidataSourceAdapter` drops the forward fallback-only claims whenever
    `reverse.lookup` returned rather than threw — the adapter already knew which had
    happened, so this cost no new state.

  Degraded behaviour is therefore unchanged and deliberate: with the Query Service
  unreachable there is no better direction to defer to, the P527 claims are kept, and a
  band still expands to its 8-member roster with `sourceUnavailable` set.
- **Both passes run, and the forward one goes first.** Neither is redundant: a film's
  director is stated on the film, so only the forward pass finds it when expanding the
  film and only the reverse pass finds it when expanding the director. Forward claims
  are concatenated first, so when the bound bites, better-evidenced claims survive.

  **Note (2026-08-25, issue #32): this ordering, not the sitelinks ranking, is what a
  forward-heavy property spends the bound on.** ADR 38 registers P166, which Wikidata
  states on the recipient — so it adds nothing to the reverse answer for a person seed,
  and `ORDER BY DESC(?sitelinks)` is untouched by it. But a decorated novelist states a
  dozen awards on their own item, and forward-first means those twelve are kept before
  any reverse-discovered work is considered. Measured on William Gibson: at
  `maxNewEdges=15` the expansion is 12 award edges and 2 others, against 12 of 118 at the
  default 200. Nothing here is changed in response — forward-first exists because forward
  claims carry references and validity qualifiers that a truthy triple does not, and that
  reasoning still holds. The measurement is recorded because it is the concrete form of a
  question ADR 38 deliberately leaves open.
- **Failure degrades in one direction only.** The Query Service is reached through the
  existing `WikidataClient`, so it inherits the User-Agent (ADR 16: repository URL, never
  an email address), the retry policy and `WikidataUnavailableException`. A Query Service
  failure after the Action API succeeded returns the forward claims flagged
  `sourceUnavailable`, rather than discarding an answer already in hand. `Retry-After` is
  now honoured on a 429 — WDQS throttles with it — capped so a header cannot decide how
  long an interactive tool call blocks.

## Alternatives considered

- **The `haswbstatement` search API.** It works, and it is the only option that sees the
  whole graph (below). It costs six queries plus a batch resolve — 8 HTTP calls and
  ~2.4MB against SPARQL's 2 calls and ~240KB, because labels and kinds need a second
  `wbgetentities` pass. Worse, a search hit carries no property attribution: you cannot
  tell which edge type a hit represents, so the six queries cannot be merged into one.
  Cross-validation kept it honest — all six OR'd gives `totalhits=73`, matching SPARQL's
  73 distinct works exactly. (Syntax trap, recorded because it fails silently:
  `haswbstatement:P86=Q192668 OR P161=Q192668` returns **0**; the correct form is
  `haswbstatement:P86=Q192668|P161=Q192668`.)
- **Register P527/P1830 and stop there.** Cheaper — no new endpoint, no new failure mode
  — and it fixes only the group case, incompletely, while leaving every person seed
  exactly as dead-ended as before. The issue as filed proposed P1830; it is "owner of",
  not a roster property, so that half of the suggestion was a dead end on inspection.
- **Applying `maxNewEdges` client-side, as before.** Simpler, and it means paying for
  rows in order to throw them away, with the survivors chosen by whatever order the
  service happened to return.
- **Fetching references and qualifiers for reverse hits**, via `p:`/`ps:`/`pq:` instead
  of `wdt:`. It would restore the confidence grade and the validity window (below), at
  the cost of a substantially heavier query that must also filter deprecated ranks by
  hand — something `wdt:` does for free. Revisit if undated memberships prove to matter
  in practice.

Considered again for the issue-#33 amendment, and rejected:

- **Dropping P527 from the vocabulary entirely.** The simplest way to end the
  duplication, and reverse-P463 dominates it 10 to 8 whenever the Query Service answers,
  so nothing is lost on the happy path. It was rejected because the whole of the degraded
  path is lost with it: WDQS is a shared, rate-limited service that this project has
  already seen throttle, and "the Query Service is down, so this band has no members"
  is a worse answer than eight of them, flagged. Deleting a fallback to fix a bug that
  was only ever "the fallback is not conditional" solves the wrong half.
- **Suppressing inverse pairs at ingest**, by teaching `IngestService` that two edges over
  one entity pair whose types are inverses are one edge. The most general answer, and the
  only one that would also catch a duplicate arriving from two different sources. It was
  rejected as bigger than the problem: the vocabulary has no notion of inverse pairs, so
  it would have to gain one *and* a collapse rule *and* a decision about which of the two
  provenances survives — while the concrete defect here is one property being read on a
  path the accepted decision already said it should not be. Revisit if a second inverse
  pair appears, or if a non-Wikidata source starts restating Wikidata's edges.

Considered for the issue-#71 amendment, and all three rejected. They are listed because each
is a reasonable answer to "some acts truncate" and they were dropped for the same reason: the
thing they achieve is not worth having. **The order below is how attractive they looked, which
is the opposite of how expensive they are.**

- **A "complete this one act" mode** — one QID, no bound, run when you notice an act is thin.
  The cheapest of the three, the easiest to keep out of a bounded run, and the one that
  matches how the problem actually arrives. It was still rejected, because its entire output
  is the 693 dead-end nodes measured above, and shipping it would invite exactly the use that
  makes the graph worse.
- **A per-act override column in the seed list.** Fits the CSV-driven design of ADR 40, keeps
  a default run bounded, and costs nothing to anyone who leaves the column blank. Rejected
  because it makes the bound look like a per-act tuning parameter, when the measurement says
  the same number is right for every act for a reason that has nothing to do with the act:
  `DESC(?sitelinks)` puts the connective neighbours first, so what the bound drops is the
  disconnected tail whoever the seed is. A knob nobody should turn is worse than no knob.
- **Escalating automatically whenever an adapter reports `truncated`.** The most convenient,
  and the worst of the three on both axes at once. It spends the most — a bounded run becomes
  unbounded in wall-clock precisely on the acts that are slowest, and Bach alone is 80s — to
  buy the least, since `truncated` is reported by the acts whose tails are longest and
  therefore least connected. It would also turn the bound's own honesty against it: the `n+1`
  row exists so truncation can be observed rather than guessed, and this would make that
  observation a trigger for undoing it.

## Consequences

- Expanding a PERSON went from 4 edges to **88**, and a GROUP from **0 to 106** — both
  measured against the live API through the adapter, not projected. The band's 106
  includes 8 `HAS_PART` from P527 and 10 `MEMBER_OF` from reverse-P463.

  *(Amendment, 2026-08-26, issue #33: those two sets overlap — all 8 of the `HAS_PART` are
  the same memberships as 8 of the 10 `MEMBER_OF`, stated from the band's side. Re-measured
  live against the same two seeds at `maxNewEdges=200`: the PERSON goes 88 → **87** edges,
  1 duplicate removed; the GROUP goes 106 → **98**, 8 duplicates removed, keeping all 10
  `MEMBER_OF`. Nothing but the duplicates was lost.)*
- `find_paths` works from person seeds alone. Nick Cave and John Hillcoat, added as two
  people and expanded, yield **8 routes**, the shortest running through *Ghosts… of the
  Civil Dead* — a film neither of their Wikidata items mentions. `PersonSeededRouteLiveTest`
  is that acceptance criterion, executable.
- **`truncated` reports a fact, and it is not a defect report** *(issue #71)*. A caller
  reading it should understand "this entity has more catalogue than the bound keeps", not
  "this entity's routes are incomplete" — the amendment above measures the difference and
  finds the second reading false. Nothing in the flag changes; what changes is that the
  16 acts reporting it on the 815-act list are now a recorded, measured outcome instead of
  an open question, and the next person to notice them has the numbers rather than the
  itch.
- **Truthy triples are lossy, and both losses are priced in.** `wdt:` exposes only the
  best-ranked, non-deprecated value — which usefully reproduces ClaimMapper's deprecated
  filter for free — but it discards the statement's reference block and its qualifiers.
  So a reverse-discovered edge is graded 0.80 and never ADR 23's referenced 1.00 (we
  cannot see which it is, and the honest reading of an unknown is the lower one), and it
  carries no `validFrom`/`validTo`. "Blixa Bargeld was a Bad Seed from 1983 to 2003" is
  ADR 20's own example and it arrives here undated; expanding Bargeld himself recovers
  the window from the forward direction, and the append-only log merges the two claims
  about one edge rather than choosing between them.
- **The scholarly graph is split, and P50 under-reports because of it.** Scholarly
  articles have moved to `query-scholarly.wikidata.org`; `query.wikidata.org` holds only
  the main graph. Measured on Albert Einstein (Q937): main WDQS returns **32** P50 works,
  the scholarly endpoint **85**, and `haswbstatement:P50=Q937` **117** — exactly the sum.
  There is no error and no warning; the number is simply smaller. Nick Cave's P50 agreed
  exactly at 12, so the music-and-film domain this project was built for is unaffected,
  but an academic seed hits a real correctness cliff.

  **The mitigation chosen is to document it and let the notability ranking absorb it, not
  to federate.** Scholarly articles carry approximately zero sitelinks, so they sort to
  the very bottom of `ORDER BY DESC(?sitelinks)` and are the first thing `maxNewEdges`
  discards; for any seed where the bound binds, querying both endpoints would change
  almost nothing about what is kept. Querying the second endpoint unconditionally would
  double the request cost of every expansion — including the overwhelming majority that
  can have no scholarly hits at all — to recover rows the bound would then drop. If
  academic seeds become a real use case, the fix is a second query issued only for those
  seeds, or routing P50 alone through `haswbstatement`; both are follow-ups with a
  trigger, not work to do speculatively now.
- Expansion now depends on a second Wikidata service with its own budget: 60s query
  deadline, 60s of processing per 60s per client, 5 parallel queries per IP, 429 with
  `Retry-After` over the limit. One ~0.3s query per expansion is trivially inside that,
  and Wikidata's own guidance is that WDQS suits narrowly-scoped result sets — which
  this is, by construction, because the bound is inside the query.
- `expand_entity`'s tool description had to change, and not only cosmetically. Its cost
  warning described a round trip per neighbour, which the inline identities largely
  remove; it now also tells the model that expanding a person or a band is productive,
  which is exactly the thing a user would otherwise have had to be told.
- P527 is "has part" generally, not "has member": expanding an album emits a `HAS_PART`
  edge per track. That is real data and it is bounded by `maxNewEdges`, but it is noisier
  than the group case that motivated the registration. *(Amendment, 2026-08-26, issue #33:
  on the normal path it no longer does. A track states `P361` back at its album, and the
  reverse pass reads that — so the album's tracks now arrive as `PART_OF` from the track's
  side, once each, instead of as `HAS_PART` and `PART_OF` over the same pair.)*
- ~~A group and its members are now connected by two parallel edges in opposite
  directions.~~ **Resolved 2026-08-26 (issue #33).** That redundancy was described here as
  "the price of keeping the fallback", but it was really the price of the fallback not
  being conditional. It is now conditional, and the consequences of that are:
  - **One membership is one edge whenever the Query Service answers.** `find_paths` stops
    returning two structurally distinct, semantically identical routes through every
    affected membership — the duplicate-route noise ADR 31's ranking exists to reduce —
    `get_entity` stops reporting one relationship under two type groups, and no slot of
    the `maxNewEdges` bound is spent on a duplicate (the drop happens before the bound is
    applied).
  - **A band's members cost one `wbgetentities` fetch each again, when expanding the
    member.** Their identity used to ride in inline on the P527 row of the reverse answer;
    the reverse pass no longer asks for that row. This is the pre-ADR-36 cost of any
    forward-discovered neighbour, it is bounded by the number of memberships on one item,
    and paying it beats asking WDQS for rows in order to throw their edges away.
  - **Asymmetrically-stated memberships now depend on P463 alone.** If a band lists
    someone under P527 and that person's item has no P463 back, the membership is invisible
    on the normal path. Wikidata's inverse-constraint reports make this rare and the
    measured case is clean — the Bad Seeds' 8 are a subset of reverse-P463's 10 — but it
    is a real narrowing, and it is the reason the alternative of suppressing inverse pairs
    at ingest is recorded as a revisit rather than dismissed.
  - **The degraded mode is unchanged and now actually degrades.** With WDQS unreachable a
    band still expands to its P527 roster, flagged `sourceUnavailable`, which is what the
    registration was for in the first place.
