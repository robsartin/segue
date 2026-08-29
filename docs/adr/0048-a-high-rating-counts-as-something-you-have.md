---
status: Accepted
date: "2026-08-29"
topic: a-high-rating-counts-as-something-you-have
tags: [project, tooling, domain, privacy]
supersedes: []
related: [bulk-seeding-as-a-dev-tool, the-rating-deck, recommend-by-normalised-lift-with-routes, affinity-capture-and-read, taste-layer-separation, listing-your-own-ratings, mcp-tool-surface, privacy-and-data-handling]
---
# 48. Count a rating of 4 or 5 as something you have, and leave suppression unbuilt

## Context

[ADR 46](0046-the-rating-deck.md) closes with a bullet headed *"A candidate's rating is recorded but
changes no score, today"*, and names what closing it would cost: deriving the known-list from the
taste layer reopens [ADR 40](0040-bulk-seeding-as-a-dev-tool.md) and [ADR 43](0043-listing-your-own-ratings.md).
This is that argument, and this is its issue — #106.

### `--known` did not mean what the recommender assumed it meant

ADR 40 turned a list that already existed outside the repository into `name → QID`, and
[ADR 45](0045-recommend-by-normalised-lift-with-routes.md) made that file the input `--known`
takes: everything the owner already has, so the recommender can rank what they do not.

**The file was produced from a concert history.** It therefore means *"acts I have seen live"*, and
`--known`'s contract is *"things I have"*. Those are different sets, and everything liked but never
attended falls in the gap. Nothing in the code was wrong; the input meant something narrower than
the flag reading it.

### The 167, measured

Counted on 2026-08-29 against a copy of the real database and the real `--known` file, re-derived
for this ADR rather than carried over from the issue: **973** stored ratings against **815** file
entities, of which **167 ratings sit on entities the file does not name**. Their distribution:

| rating | 1 | 2 | 3 | 4 | 5 |
| --- | --- | --- | --- | --- | --- |
| count | 1 | 1 | 78 | 51 | 36 |

**87 of the 167 are a 4 or a 5, and exactly 2 are below neutral.** Both halves of that sentence
decide something below — the first that there is a signal worth using, the second that there is not
enough of the opposite one to build a rule against.

These 167 were in a uniquely useless position, and it took two call sites to put them there:
`Recommendations.regardFor` weights known-list qids only, so it never read them; `Deck.dealRevision`
walks the known list only, so `--revise` could not reach them either. A first-person judgment the
system could neither use nor revisit.

### Why this matters more than the ratings that were already load-bearing

ADR 46's issue-#109 amendment records what the ratings *on* the list are worth: 973 of them moved
one entity in the top 25 against running with no ratings at all. **A list of acts the owner chose to
go and see cannot produce disagreement** — on the distribution measured for this ADR, 881 of the 973
ratings are a 4 or a 5. The 167 are the only
judgments in the corpus made about things the owner did not already select, which is what makes them
the only ones capable of carrying a genuine "no", and the reason this issue was rewritten around
them.

## Decision

- **An entity rated at or above `KnownList.PROMOTION_RATING` counts as known.** `KnownList.promoted`
  is a pure function of the file's list and the note-free ratings map: the file's entities in the
  file's order, then everything rated at or above the threshold that the file does not already name.
  It lives in `domain` and is the authority on its own rule; this ADR does not restate the code.

- **At both call sites that read a `--known` file, and there are exactly two.**
  `RecommendRun.run` and `RateCli.known` each wrap `QidList.read` in `KnownList.promoted`. (The
  third `QidList.read` call in `src/main`, `ExportRun`'s, reads `--qids` for a subgraph view and is
  not a known-list at all.) One rule, applied where the list is composed, rather than a filter
  inside `CandidateSweep` that only one of the two tools would have gone through.

- **The threshold is 4, and that is a judgement rather than a measurement.** Stated plainly because
  the numbers around it were measured. ADR 45's degree floor came from watching a normalised score
  put a degree-2 node first; its scorer came from running `raw` against the real graph and reading
  back the most famous entities in it; ADR 47's admission turned on one concept counted twice.
  **This one did not come from running anything.** It is the reading
  that a 4 means "this is mine" and a 3 means "no opinion" — which is at least consistent with
  `Recommendations.NEUTRAL_RATING`, where a 3 already weighs exactly what no rating weighs — and it
  is a place to start, not a finding.

- **Promotion only. Suppression is deliberately not built.** A low rating does not remove a candidate
  from future sweeps, and the reason is the distribution above: **2 ratings below neutral against 87
  above.** A suppression rule would have shipped against two data points. This issue's own history is
  of plausible theories that did not survive measurement — #101's premise that the taste layer was
  unwired, #109's premise that the threes were suppressed twos — and a rule built on two observations
  is the same mistake with less evidence. Recorded here as considered and declined, so a later reader
  does not read the omission as an oversight. The re-open condition is arithmetic: enough ratings
  below neutral to see a distribution rather than two points.

- **The promoted portion is sorted.** `Map` iteration order is not guaranteed, and this list feeds
  the known-list filter that decides which entities are candidates at all, so two runs over the same
  ratings must produce the same list. `Recommendations.rank` makes the same argument for its own
  qid tiebreak, and attributes it to [ADR 43](0043-listing-your-own-ratings.md), whose comparators
  end in `qid` so that two runs over an unchanged table produce byte-identical files.

## What ADR 40 keeps, and what it loses

ADR 40 put the seeding list in a file outside this repository and made that file the authority for
`--known`. **It is no longer the sole authority**, and saying so is the point of this section.

What survives, unchanged:

- **The file is still the authority for what was seeded.** ADR 40's tool resolves names to QIDs and
  writes a mapping file; nothing here changes what that file is or how it is produced.
- **The file is still the only way an entity gets onto the list without having been rated**, which
  is most of it: 815 file entities against 87 promotions.
- **Nothing on the MCP surface can see either half.** The file is a path on the owner's machine that
  no tool takes, and the ratings reach this rule through `AffinityStore.readRatings`, which
  `ArchitectureTest.onlyTheRecommenderReadsEveryRating` reserves to `recommend` and `rate` — the two
  dev-side tools this decision touches. ADR 26's surface is still six tools. ADR 40's refusal to hand
  a model the personal list is untouched, because the second source of the list is not on that
  surface either.
- **Neither half is written to the other.** Promotion happens in memory, per run. The file is not
  rewritten and the taste layer gains no row.

What changes:

- **`--known` now names a smaller part of the truth than it did.** A reader of ADR 40 who concluded
  "the file is what the owner has" must now read "the file plus what they have rated highly".
- **The list is no longer reproducible from the file alone.** Two runs a rating apart can produce
  different candidate pools from identical arguments. That is intended — it is the feedback property
  below — and it means a run's output is only interpretable together with the taste layer as it stood
  at the time.

ADR 43's fence is untouched: the bulk read was already shared with `recommend` and `rate` before
this, by ADR 46. No rule widened here.

## Alternatives considered

- **Let `--revise` reach the 167, and decide nothing about what they mean.** The issue's own smallest
  option, and genuinely attractive: it stops the ratings being unreachable without reopening ADR 40 at
  all. Rejected because "unreachable" was the smaller half of the problem. The 87 fours and fives were
  *inert* as well as unreachable — the recommender was still free to recommend them back, which is the
  failure the issue was filed against. Making them editable would have left that intact. This
  decision delivers the revision half as a consequence anyway; see below.
- **Union the file with the whole rated set, at any rating.** The issue lists it, and it has the
  cleanest sentence: everything you have an opinion about is yours. Rejected on the 78 threes among
  the 167. A 3 is what the deck records for "I have heard of this and that is all", and
  `Recommendations.regardFor` already treats it as identical to no rating; promoting it would put 78
  entities the owner has not claimed onto the list of what they have, and would remove all 78 from the
  candidate pool where they may still belong.
- **A threshold of 5.** Defensible — a 5 is unambiguous — and it would promote 36 rather than 87.
  Rejected because the gap this closes is real for both values: an entity rated 4 is one the owner
  says they like, and being recommended it back is the same failure whichever of the two numbers they
  typed. It also makes the scale's most-used value carry no consequence: 541 of 973 ratings are 5s and
  340 are 4s, so a threshold of 5 would ignore the second-commonest judgment in the corpus.
- **A threshold of 3.** Rejected by the same argument as the whole-rated-set option, of which it is
  the general case.
- **Suppress on a low rating, as well as promoting on a high one.** The issue's second shape, and the
  one thing in the corpus that could carry a "no". Rejected on 2 data points — argued above.
- **Promote into the *weighting* but leave the entity in the candidate pool.** This would separate the
  two mechanisms: the rating would start contributing to other candidates' scores without the entity
  itself disappearing from the results. Rejected because being recommended something you have already
  rated 5 is precisely the reported failure, and because the resulting state has no honest name — the
  entity would be simultaneously known enough to vouch for its neighbours and unknown enough to be
  offered. The two mechanisms are separable in measurement, and both fired; see the consequences.
- **Write the promotions back into ADR 40's file.** It would make the list reproducible again and keep
  one authority. Rejected: the file is personal data outside the repository whose meaning is "what the
  seeding run resolved", and a tool that edits it makes a read-only measurement into a writer of the
  one input the owner curates by hand. `ArchitectureTest.theRecommenderOnlyReads` keeps `recommend` off
  the graph, the log and the taste layer's writes, and this would not be the reason to loosen it.
- **Derive the known-list entirely from the taste layer, and drop `--known`.** The end state ADR 46's
  bullet was pointing at. Rejected because `--known` being required is itself a decision ADR 45 made —
  a tool that picks that list for you has guessed — and because the taste layer holds 973 entities
  against the file's 815, in a different and much more partial shape. The file remains the authority
  for what was seeded.
- **A seventh MCP tool that reads the taste layer as the known-list.** Refused for the reason ADR 45
  and ADR 46 both refused one, unchanged by this decision: the input is still ADR 40's file, which
  ADR 40 declined to hand a model.

## Consequences

### It moved the ranking substantially, where three earlier comparisons had not

Measured on 2026-08-29 by running `./gradlew recommend` against a copy of the real database at the
merge-base and at this branch's head, with the same real `--known` file, and diffing both the top 25
and the full candidate pool. The context for reading these numbers is the table in issue #106:
**three earlier comparisons on this same data produced almost no movement** — 973 ratings against
none at all moved one entity, a further 164 ratings changed the ordering not at all, and #109's 37
revisions produced one rank swap.

- **The list grew by exactly 87**, 815 to 902, which is 51 + 36 from the table above. Confirmed three
  independent ways that agreed: the tool's own count, a query against the `affinity` table checked
  against the file, and the full-pool diff.
- **The top 25 turned over by 36%** — 9 left, 9 entered, 16 stayed.
- **Six of the nine that left, left because they were promoted.** Each was rated 4 or 5 and each is
  absent from the whole 1,033-candidate pool afterwards, not merely reranked below the cut. **That is
  the fix working, not a regression**: being offered back something you rated 5 is the failure #106
  was filed against. The other three were never rated at all and were displaced by reordering.
- **Both mechanisms fired, separately.** Removal is the six above. Reweighting is visible on its own:
  the four top-ranked entities kept their exact ranks while gaining 40–46% in score, and every one of
  the nine that entered the top 25 rose 35–246%.

### What it did *not* do, which matters more than what it did

**All nine entrants were already mid-pack candidates** — ranks 27 to 194 of 1,110 before promotion.
Entities that became reachable *only* through a newly-promoted entity's connections did appear —
ten of them — and landed at rank 817 and below of 1,033, scoring 0.001 to 0.02 against a top-25 cut
around 0.6.

So the honest conclusion is narrower than the turnover figure suggests: **promotion substantially
reordered the pool that already existed, and brought in no new territory.**

That is evidence for a separate line of work rather than a disappointment. **The candidate pool is
bounded by what has been expanded, not by what is in the graph.** Counted on the same copy on
2026-08-29: of **123,752** distinct nodes, **16,860** are the subject of at least one stored edge —
**13.6%**. The rest arrived as the far end of somebody else's expansion and have never been expanded
themselves, so nothing routes *through* them and no amount of reweighting can promote what the sweep
cannot reach. (Issue #92 measured the same shape from the other side: 87% of those nodes are
degree-1 leaves.) Reordering was the whole of the available effect because reordering is the whole of
what this lever can touch.

### The deck is now self-feeding, and one thing bounds it

A promoted entity is on the known-list, so its own connections start contributing to every future
candidate score, and every candidate it reaches is one more thing the deck can deal and the owner can
rate. Rating is therefore no longer a passive measurement of a fixed pool: **the pool grows as the
owner rates.** That is intended, and it is the mechanism by which a graph seeded from one concert
history can come to describe more than concerts.

What bounds it is the paragraph above. A promotion can only add candidates its own expanded
neighbourhood reaches, and the measurement found ten such arrivals for 87 promotions, all of them far
below the cut. There is no runaway: the loop is gated by expansion, which is a deliberate, network-bound
act nobody performs by accident. It is worth saying that the bound is a property of the current graph
rather than a rule in the code — nothing enforces it, and if expansion coverage rises this loop gets
correspondingly livelier.

### `--revise` now reaches the promoted entities, and only those

`RateCli` composes the same promoted list it passes to the deck, and `Deck.dealRevision` walks that
list — so `--revise 4` and `--revise 5` now deal the 87. **`--revise 3` still cannot reach the 78
threes among the 167**, because a 3 is below the threshold and those entities are still not on the
known list. The issue's "make them reachable" option is therefore delivered for a little over half of
the 167 as a by-product, and explicitly not delivered for the rest. Whether the threes should be
reachable is the suppression question in a different costume, and it waits on the same evidence.

### ADR 46's accepted gap is closed; its prediction of the cost was half right

ADR 46's bullet said closing this reopens ADR 40 and ADR 43. It reopens ADR 40, in the bounded way
the section above states. It does **not** reopen ADR 43: by the time this landed, ADR 46 had already
widened `readRatings` to `rate` as well as `recommend`, so this decision needed no new access to the
taste layer and widened no fence. That ADR's bullet should be read as pointing here.

### The measurement is a snapshot, not a fixture

Every figure above comes from one `--known` file and one moment's 973-rating state of one real
database. It is not repeatable and is not in the repository — the ratings are personal data that ADR
33 keeps out of it. The behaviour is demonstrated in the gate on invented ratings instead, in
`KnownListTest` and in `RecommendRunTest`'s issue-#106 case, exactly as
`AffinityWeightedRecommendationTest` demonstrates ADR 45's weighting. Re-running against a later
snapshot will name different entities; what should hold is the two-mechanism shape, and the finding
that reordering rather than discovery is what this lever produces.
