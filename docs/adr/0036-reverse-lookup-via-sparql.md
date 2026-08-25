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
  reverses every property in `ClaimMapper.mappedProperties()` — which is `EdgeTypes` —
  for the same reason the forward whitelist is derived rather than duplicated. A
  hand-kept subset would stop covering a relation type the day someone registered one,
  which is this bug again one level down.
- **Direction reuses ClaimMapper's rule with the subject swapped.** A reverse hit means
  Wikidata holds `other P seed`, so it maps exactly as ClaimMapper would with
  `subject = other`. An inverted type yields `seed DIRECTED other`; a direct one yields
  `other HAS_PART seed`. How an edge is stored does not depend on which pass found it.
- **`maxNewEdges` moves server-side, as `ORDER BY DESC(?sitelinks) LIMIT n+1`.** The n
  we keep are the most-linked rather than an arbitrary slice — for the Bad Seeds at
  n=15 that is Nick Cave, Blixa Bargeld, Mick Harvey and Warren Ellis ahead of a 2024
  album track — and the one extra row makes `truncated` an observation rather than a
  guess.
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
- **Both passes run, and the forward one goes first.** Neither is redundant: a film's
  director is stated on the film, so only the forward pass finds it when expanding the
  film and only the reverse pass finds it when expanding the director. Forward claims
  are concatenated first, so when the bound bites, better-evidenced claims survive.
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

## Consequences

- Expanding a PERSON went from 4 edges to **88**, and a GROUP from **0 to 106** — both
  measured against the live API through the adapter, not projected. The band's 106
  includes 8 `HAS_PART` from P527 and 10 `MEMBER_OF` from reverse-P463.
- `find_paths` works from person seeds alone. Nick Cave and John Hillcoat, added as two
  people and expanded, yield **8 routes**, the shortest running through *Ghosts… of the
  Civil Dead* — a film neither of their Wikidata items mentions. `PersonSeededRouteLiveTest`
  is that acceptance criterion, executable.
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
- P527 is "has part" generally, not "has member": expanding an album now emits a
  `HAS_PART` edge per track. That is real data and it is bounded by `maxNewEdges`, but it
  is noisier than the group case that motivated the registration.
- A group and its members are now connected by two parallel edges in opposite directions
  — `HAS_PART` from P527 and `MEMBER_OF` from reverse-P463. The graph is a multigraph and
  `find_paths` handles it, but the redundancy is real, and it is the price of keeping the
  fallback that works when the Query Service does not.
