# A merge inflates the degree of everything the local entity touched

Issue #178. Written 2026-09-02, on branch `178-merge-degree`. **Nothing here is decided.** The
decision below is a recommendation the owner must ratify before any of it is built.

Every qid, label, degree and score in this document is invented — a fixture built for this
measurement, not a run over the owner's graph — so ADR 51's rule about publishing a ranking does not
bite: there is no known-list behind it and nothing here describes anybody's taste.

## What was measured, before anything was designed

`IngestService.carry` copies a merged local entity's edges onto its canonical QID and leaves them on
the local id, because ADR 19 is append-only and ADR 59 decided a merge is an appended equivalence
rather than an edit. `LogProjection.carry` does the same thing to the same log (#177). So after a
merge the graph holds **two nodes carrying the same edges**, and every neighbour of the merged
entity has one more incident edge than the world justifies. `Scorer.LIFT` divides by the candidate's
own degree and discounts each intermediate by the log of its degree, so the inflation reaches the
score twice over.

The issue named the measurement worth taking first: **how much a real merge actually moves a real
ranking**, by ADR 45's amendment method — same known-list, top 25 before and after, ranks and scores
compared.

### The fixture

Four known entities on the `--known` file (two of them rated, 5 and 4), six `PERSON` intermediates
at degrees 12–22, thirty `GROUP` candidates at degrees 6–33, and one minted local entity whose owner
edges reach one intermediate and then candidates. Padding is `WORK` fillers, which no kind rule ever
admits as a candidate. Scored by `lift` at the shipped default floor of five
(`Recommendations.MIN_CANDIDATE_DEGREE`), top 25, driving the real `RecommendCli.main` against a
scratch SQLite log in a temp directory. The merge is appended to that same log exactly as production
makes it (`SameAs.declared`), and the second run replays it.

**The local entity is deliberately unrated.** #92's Task 4b already folded the *ratings* so a merged
entity counts once; this measurement is of the graph half alone, and rating the local entity would
mix the two back together. It carries the shape ADR 59 exists for — a thing no source knows, minted
and connected by the owner — and nothing else.

**The instrument was validated before it was believed.** Replaying the unchanged log a second time
produced a byte-identical file at every degree, so a difference below is a difference the merge
made.

### What a merge does to the ranking

Headline, per merged-entity degree; the per-candidate table for the twenty-edge case follows, and
the other two are in the working note this spec was written from.

| edges the merged entity carried | top-25 entries whose **score** changed | entries whose **rank** changed | max rank displacement | Σ\|Δrank\| | top-25 churn (out / in) | worst single score change |
|---:|---:|---:|---:|---:|:--:|---:|
| 2 | 8 of 25 | 4 | 3 places | 6 | 0 / 0 | **−9.15 %** |
| 5 | 10 of 25 | 5 | 3 places | 12 | 0 / 0 | **−9.15 %** |
| 20 | 18 of 25 | 8 | 3 places | 12 | 1 / 1 | **−12.50 %** |

The twenty-edge run, candidate by candidate. `Q930900` is the canonical id; every other id is an
invented candidate.

| qid | rank before | rank after | Δrank | score before | score after | Δscore | degree before | degree after |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Q930210 | 1 | 2 | +1 | 0.2601 | 0.2276 | −12.50 % | 7 | 8 |
| Q930220 | 2 | 1 | −1 | 0.2404 | 0.2404 | 0.00 % | 6 | 6 |
| Q930221 | 3 | 3 | 0 | 0.2003 | 0.1983 | −1.00 % | 9 | 9 |
| Q930222 | 4 | 4 | 0 | 0.1504 | 0.1504 | 0.00 % | 12 | 12 |
| Q930223 | 5 | 5 | 0 | 0.1177 | 0.1177 | 0.00 % | 15 | 15 |
| Q930201 | 6 | 8 | +2 | 0.1137 | 0.1033 | −9.15 % | 10 | 11 |
| Q930202 | 7 | 9 | +2 | 0.1110 | 0.1030 | −7.21 % | 13 | 14 |
| Q930214 | 8 | 7 | −1 | 0.1101 | 0.1037 | −5.81 % | 19 | 20 |
| Q930200 | 9 | 6 | −3 | 0.1083 | 0.1053 | −2.77 % | 6 | 6 |
| Q930215 | 10 | 10 | 0 | 0.1041 | 0.0996 | −4.32 % | 22 | 23 |
| Q930224 | 11 | 11 | 0 | 0.1002 | 0.0992 | −1.00 % | 18 | 18 |
| Q930211 | 12 | 12 | 0 | 0.0991 | 0.0901 | −9.08 % | 10 | 11 |
| Q930225 | 13 | 13 | 0 | 0.0859 | 0.0859 | 0.00 % | 21 | 21 |
| Q930216 | 14 | 14 | 0 | 0.0844 | 0.0812 | −3.79 % | 25 | 26 |
| Q930208 | 15 | 15 | 0 | 0.0832 | 0.0806 | −3.12 % | 31 | 32 |
| Q930209 | 16 | 16 | 0 | 0.0764 | 0.0742 | −2.88 % | 34 | 35 |
| Q930212 | 17 | 18 | +1 | 0.0749 | 0.0682 | −8.95 % | 13 | 14 |
| Q930226 | 18 | 17 | −1 | 0.0736 | 0.0736 | 0.00 % | 24 | 24 |
| Q930203 | 19 | 19 | 0 | 0.0721 | 0.0678 | −5.96 % | 16 | 17 |
| Q930213 | 20 | 20 | 0 | 0.0710 | 0.0669 | −5.77 % | 16 | 17 |
| Q930207 | 21 | 21 | 0 | 0.0638 | 0.0610 | −4.39 % | 28 | 29 |
| Q930217 | 22 | 22 | 0 | 0.0527 | 0.0509 | −3.42 % | 28 | 29 |
| Q930229 | 23 | 23 | 0 | 0.0443 | 0.0443 | 0.00 % | 33 | 33 |
| Q930228 | 24 | 24 | 0 | 0.0439 | 0.0433 | −1.37 % | 30 | 30 |

`Q930219` left the top 25 and `Q930227` entered it.

**At twenty edges the merge unseated the top recommendation**: the rank-1 candidate lost 12.50 % of
its score — its degree went 7 → 8, and 7/8 is 0.875 — and dropped to rank 2 behind an entity that
did not move at all. One entry left the page and one entered.

Two mechanisms, both visible in the per-candidate rows:

- **A candidate adjacent to the merged entity** has its own degree raised by one, so `lift` returns
  `d/(d+1)` of what it returned. The measured drops sit exactly there: degree 10 → 11 is −9.15 %,
  13 → 14 is −7.21 %, 19 → 20 is −5.81 %, 31 → 32 is −3.12 %. **The residual the issue calls "roughly
  3 %" is the large-degree end of this curve**, not its middle.
- **An intermediate adjacent to the merged entity** has its degree raised by one, so every candidate
  reached through it loses `log(v)/log(v+1)` — around 3 % at degree 12. That is why eight of
  twenty-five scores moved at a merged degree of *two*, when only one candidate was touched
  directly.

The effect scales with the merged entity's edge count, as the issue predicted: 8 → 10 → 18 of the
top 25 disturbed at 2, 5 and 20 edges. It does **not** scale into large rank jumps in this fixture,
because scores near the head are well separated; the observed ceiling is three places. A denser
graph would move more.

### What is *not* wrong, and was checked rather than assumed

- **The candidate pool does not grow.** 30 → 30 at degree two and 31 → 31 at five and twenty, before
  and after. `KnownList.notOffered` retires the local id and the canonical id takes its place, so
  #92's fold is doing its job and nothing is offered twice.
- **The merged entity does not seize the top of the page.** `Equivalences`' javadoc records an
  invented graph where the local entity "becomes a candidate — ranked first". It does not here, and
  the reason is ADR 45's issue-#84 amendment: the only hop into the canonical id is one the entity
  states about itself, worth `SELF_STATED` (0.2), so the direction rule already demotes it. The
  entity is in the pool at degrees 5 and 20 and outside the top 25 in both runs.
- **The floor reading barely moves.** Pool median degree 19 → 20 at a merged degree of five;
  identical at two and twenty. **Nothing in ADR 57's reading would tell an operator this had
  happened.** That is the "it is silent" bullet of the issue, confirmed against the instrument that
  was supposed to make it visible.

### The positive control for any fix

The same fixture was rebuilt with the owner's edges counted **once**, on the canonical id — the
graph a projection-side fold would leave behind. At every merged degree its top 25 is the pre-merge
top 25, **in the same order, with a largest score difference of 0.0000000000**.

So the target state is not approximate and does not need a tolerance: a correct fold restores the
ranking exactly. That equality is the acceptance criterion below.

## The three shapes, costed

### Shape 1 — fold at projection, so both ids are one node

Both replay paths resolve every edge endpoint through the equivalences before applying it, so the
merged entity's edges exist once. `Equivalences.in(log)` already exists in `domain`, already applies
`Retractions.survives` to the `SameAs` rows, and already keeps log order for determinism.

**What it changes in the table:** everything. The after-merge top 25 equals the before-merge top 25,
score for score, at every degree — measured, above.

**What it touches:** `GraphProjector.project`, `LogProjection.of`, and `IngestService`'s `SameAs`
arm (the edge-copy loop goes, the canonical-node creation stays, because a rewritten edge still
needs both endpoints to exist). `MergeCarriesEverythingTest` and `BothFoldsAgreeTest` move with
them; `OwnerClaimProjectionTest` and the export fixtures follow.

**The cost is smaller than it looks, and the reason is worth stating.** Both entry points already
call `log.readAll()` before they fold anything, so the whole-log view a rewrite needs is already in
hand. And a `SameAs` never reaches a *live* graph: `OwnRun` appends through `IngestService.claim`
and says so in its own note — the running graph is rebuilt at the next boot (ADR 24) — while
`recommend`, `rate` and `exportGraph` each do a full replay of their own. There is no incremental
path that would need an edge delete the `GraphStore` port does not have.

**Which ADR it amends:** ADR 59, the merge bullet. That bullet says the local id keeps its node, its
edges and its affinity row. Under this shape it keeps its **node and its affinity row**, and its
edges move. The log is untouched, so ADR 19 is untouched and a wrong merge is still retractable by
ADR 44's ordinary mechanism — `Equivalences.in` asks `Retractions.survives` already.

**Pros.** It is the only shape that fixes every reader at once. `PathRanking.isHub` on the routing
side, the exporter's picture, `find_paths` offering two identical routes, and the recommender's
degree are four readings of the same inflated number, and one fold corrects all four.
It **reduces** the number of places that know what a merge means rather than adding one: today that
is `IngestService.carry`, `LogProjection.carry`, `Equivalences` and `ratings/Labels`.
It is the shape "a graph and a picture of that graph must not disagree" already argues for —
`BothFoldsAgreeTest` exists precisely so the two folds move together, and this change is one both of
them make.

**Cons.** It amends a decision ADR 59 argued for at length, and the amendment is real rather than a
re-reading. A merged local id becomes an isolated node in an export — a picture of nothing — until
someone decides how to draw it. And the fold now moves claims made *after* a merge as well as
before, which contradicts `carry`'s stated "order is log order" property; the alternative is to keep
that property and accept a residual for post-merge claims, which is a question for the owner and is
listed as open below.

### Shape 2 — exclude merged local ids from degree in the scorer

`CandidateSweep` receives `Equivalences.merged()`, skips a merged local id as a neighbour, and
subtracts its edges from every degree it computes.

**What it changes in the table:** the recommender's half of it, and only that. The candidate-degree
drops and the intermediate-discount drops both go, so the after-merge top 25 would match the
before-merge one for `recommend` and for the deck.

**What it touches:** `recommend/CandidateSweep` and its two call sites, `RecommendRun` (which
already holds an `Equivalences`) and `rate/RateRun`. Nothing in `ingest`, `export` or the port.

**Which ADR it amends:** ADR 45 — the scorer would no longer divide by the candidate's degree in the
graph but by a corrected degree, which is a change to the formula the whole ADR is about.

**Pros.** By far the cheapest, and it is the change with the smallest blast radius: no projection
moves, no export artefact changes, no ADR 59 clause is touched, and the log's meaning is exactly
where it was.

**Cons, and they are the issue's own.** It puts the equivalence knowledge in the scorer rather than
in the projection — and ADR 45's 2026-08-29 amendment is the document that establishes that degree
in this scorer is already doing two jobs it cannot separate (anti-inflation and worth-showing). This
would give it a third. More seriously, it is the shape of the bug it is fixing: a second reading of
one log that has to be remembered separately. #176 and #177 are both instances, and a corrected
degree that only `CandidateSweep` knows about leaves `PathRanking.isHub` on the routing side, the
exporter and `find_paths` reading the inflated number with nothing comparing them. The repo already
made this argument once, when it made `PathRanking.isHub` public rather than letting recommendation
carry a second copy of the hub rule.

### Shape 3 — accept and record

State the residual in an ADR, and make it visible: a line in `RecommendRun`'s notes and in the
report header saying how many merged entities are in this graph and that their neighbours' degrees
carry one extra edge each.

**What it changes in the table:** nothing. It changes what a reader knows about the table.

**What it touches:** an ADR, `RecommendRun`'s notes, `RecommendationReport`'s header, and
`FloorReading` if the count belongs beside the floor's other figures.

**Which ADR it amends:** ADR 57, which is the decision that a reading of the floor is emitted at
all — a merge count is the same kind of figure and belongs in the same two lines.

**Pros.** This repo has shipped four documented refusals that each beat their fix, and honesty about
a known residual is its house style. A merge is rare. And accept-and-record is the only shape that
costs nothing if the owner's real graph turns out to hold no merges at all.

**Cons.** The measurement above is what it has to be weighed against, and it is worse than the
issue's own "roughly 3 %": up to 12.5 % on one score, enough to unseat rank 1 and to swap an entry
off the page, at a merged degree the owner would call ordinary. It compounds — every merge adds
another edge to its neighbours, and merges land in exactly the neighbourhood the owner cares most
about. Recording it honestly is itself a build, because "silent" is half the defect, and once that
build is done Shape 2 is not much further.

## Recommendation, for the owner to ratify or refuse

**Shape 1, in the form that keeps the local node and moves only its edges.**

Two reasons, and the second is the decisive one.

1. **It is the only shape measured to work, and it works exactly.** The fold control returns the
   pre-merge top 25 in the pre-merge order with a largest score difference of 0.0000000000. Shape 2
   would reach the same place for `recommend` and `rate`; Shape 3 does not try.
2. **Shape 2 builds the defect it is fixing.** #178, #177 and #176 are one family — a second reading
   of one log that did not move when the first one did — and the scorer's own idea of degree would
   be the fourth member, with nothing comparing it to the graph's. Correcting the fold corrects the
   scorer, the router's hub judgement, the exporter's picture and `find_paths` in one place, and
   `BothFoldsAgreeTest` already exists to stop the two folds from drifting while it happens.

The cost that made Shape 1 look expensive is not there: both folds already read the whole log before
they fold it, and no `SameAs` ever reaches a live graph, so no port change and no edge delete is
needed.

**The positive control, and the definition of done.** With the fix in, the fixture's after-merge top
25 equals its before-merge top 25 — same qids, same order, scores equal to within 1e-9 — modulo the
merged entity appearing under the canonical id instead of the local one. And the control must be
seen to fail: restore `carry`'s copy and watch the guard go red naming the inflated candidate, which
in this fixture is the degree-10 candidate that loses 9.15 % and moves three places. A guard never
seen red has never been tested (#93, #139).

## Rejected

- **Shape 1 in its "rename" form** — the local id becomes the canonical id everywhere, node
  included, so the graph holds exactly one node and the local id resolves to nothing. Cleaner, and
  it removes the isolated-node-in-an-export problem outright. **Rejected for now** because it
  deletes more of ADR 59's merge bullet than the defect requires, and `get_entity` on a local id
  would start answering nothing with no redirect to offer instead. Worth re-opening if the isolated
  node turns out to be worse than the missing one.
- **Shape 2**, above: cheapest, and it is the shape of the bug.
- **Shape 3**, above: honest, and the measurement is worse than the figure the issue accepted it
  against.
- **A `GraphStore` edge delete, so `carry` can move edges incrementally.** Widens the port that
  exists to keep the engine choice reversible (ADR 18), for a case that does not arise: no `SameAs`
  reaches a live graph.
- **Subtracting the duplicate at read time in `EdgeRecord`** — teaching the edge to know it is a
  copy. It would have to know about the equivalences, which puts `domain`'s smallest type in the
  business of reading the log.

## Open questions for the owner

1. **Claims appended after a merge.** `carry` deliberately runs at the merge's own position, so a
   claim made against a local id *after* it was merged stays on the local id. A whole-log rewrite
   moves those too. Is that the intended reading — the owner said the two ids are one thing, full
   stop — or should `OwnCli` refuse a claim against an already-merged local id instead?
2. **How a merged local id is drawn.** Under Shape 1 it becomes an isolated node in `exportGraph`.
   Drawn, hidden, or drawn with a note?
3. **How many merges the real graph holds.** Nothing in this measurement touched
   `~/.segue/segue.db`, and the size of the real effect is `merges × their degree`. A run over a
   copy would settle whether this is one entity or thirty.
4. **Whether the measurement fixture becomes a committed test.** It is a fixture with a control and
   it reads cleanly; it is also a second graph fixture beside `InventedWorld`, which #171 already
   wants consolidated.

## Controller rulings (2026-09-02)

1. **The fix shape awaits the owner's ratification** (fold at projection, recommended, amends ADR 59;
   versus a scorer-side exclusion or accept-and-record). No implementation task starts before it.
2. **Claims appended after a merge are refused at `OwnCli`, which is already the case** —
   `OwnRun.labelOrRefuse` refuses an edge against a merged local id by name and points at the canonical
   id. Rewriting is not needed; the fold must not assume it either: a later claim naming the local id
   (by a path that bypasses the tool) folds onto the canonical id like any other.
3. **A merged local id that has lost its edges is drawn as it is** — a node with no edges, visible in a
   `full` or `subgraph` export like any orphan (the retraction chapter's precedent). Nothing hides it.
4. **The fixture is committed on this branch**; folding it into #171's `InventedWorld` is #171's business
   once both land — no cross-branch coupling now.
5. **One differential harness for #176/#177/#178 is a separate issue**, filed when this lands, not built
   here.

**Ratified by the owner, 2026-09-02:** fold at projection — keep the local node, move its edges onto
the canonical id, in both folds in one commit; ADR 59's merge bullet gets a dated amendment.
