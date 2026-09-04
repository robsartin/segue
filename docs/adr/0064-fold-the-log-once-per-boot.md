---
status: Accepted
date: "2026-09-04"
topic: fold-the-log-once-per-boot
tags: [project, domain, ingest, performance, testing]
supersedes: []
related: [assertion-log-source-of-truth, retraction-as-a-new-claim, owner-claims-as-a-third-layer, store-p31-and-rederive-kind-at-projection, mikado-method-for-changes, use-test-driven-development, layering-and-archunit]
---
# 64. Fold the assertion log once per boot, and hand it to every reader

## Context

[ADR 24](0024-sqlite-assertion-log.md) rebuilds the graph from the log at every boot, and
[ADR 44](0044-retraction-as-a-new-claim.md) and [ADR 59](0059-owner-claims-as-a-third-layer.md) each
added a rule that decides what the replay makes of a row. Those rules live in `Equivalences` and
`Retractions`, each reached through a static that takes the whole log — and `GraphProjector.project`
was calling four of them.

**It read the log once and then walked that one list four separate times.** `Retractions.in` is one
whole-log walk. `Equivalences.folding(List)` is two, because it computes the #228 emptied-canonical-id
fixed point twice — once inside `Equivalences.in` and once beside it in `retractedStandIns`.
`Equivalences.standIns(List, UnaryOperator)` runs `Equivalences.in` again, so a third fixed point,
plus `localsOfMerges`. The pre-flight `refuseRowsNamingAnEntityNoNodeStandsFor` then asked
`Equivalences.nodesTheFoldHolds(List)`, which derives its own stand-ins and so pays a fourth. That
fixed point is a loop of whole-log walks, not a scan: ADR 44's 2026-09-04 amendment carries the
figure that prices a single pathological round of it at this log's scale.

**The issue that filed this work read that multiple as "roughly a threefold multiple off the
boot's whole-log work"**, from the #228 whole-branch review's instrumented counts on a small log —
`Equivalences.in` three times per boot, the fixed point four times with no retractions and eight
with one, `localsOfMerges` six and ten. Those are fold invocations, not wall-clock. The measurement
below is what the multiple is worth in seconds, and it is not threefold: the boot's time is the
replay loop's store writes, which this change does not touch.

**The code already admitted it, in prose, in the one place best positioned to know.**
`Equivalences.emptiedCanonicalIds`' javadoc said the round count is per invocation of that method
rather than per boot, *because* `GraphProjector.project` invoked the fold more than once while
replaying a single log. That sentence was accurate when it was written and is corrected by this
decision.

Nothing here was a correctness problem. Every one of the four calls returns the right answer; the
boot simply derived the same answer from the same rows four times, and the cost grows with the log,
which grows with every expansion.

## Decision

**The boot folds the log once, into one value, and every reader takes what that value holds.**

`Fold` is a new record in `domain`, beside `Equivalences` and `Retractions`, carrying the
retractions, the folding `Equivalences`, the stand-in map and the held node set. **It decides
nothing.** `Equivalences` and `Retractions` keep every fold rule there is; `Fold.of` calls them in
the order they already require of each other and stores what comes back. There is deliberately no
second home here for a rule to drift into.

**The saving is the emptied set, computed once and threaded.** `Fold.of` computes the fixed point a
single time and hands it on, which needs four new overloads — `Equivalences.in(List, Set)`,
`Equivalences.folding(Equivalences, Set)`, `Equivalences.standIns(List, UnaryOperator, Equivalences)`
and `Equivalences.nodesTheFoldHolds(List, Set)`. Each is the existing method with the part the caller
already has taken as a parameter, and each is trust-the-caller: correct only for a set and a merges
value derived from that same log. Every existing log-taking static keeps its signature and its
answer, becoming a one-line delegation, so no tool's API moved. Each overload is pinned to its
log-taking twin by a test that asserts the two answers are equal on a log carrying a merge, a
retraction that empties a canonical id, and an edge naming it.

`Fold.of` requires its `rederive` rather than defaulting it, on `Equivalences.localsOfMerges`' own
stated reason: an overload quietly restoring the identity operator is how a third fold would arrive
carrying the kind lag [ADR 42](0042-store-p31-and-rederive-kind-at-projection.md) exists to close,
with nothing at the call site saying so.

**What pins the property is an ArchUnit fence, not an invocation counter.**
`ArchitectureTest.theBootFoldsOnce` forbids `GraphProjector` from calling `Equivalences.in`,
`folding`, `standIns`, `nodesTheFoldHolds`, `retractedStandIns`, `localsOfMerges` or
`Retractions.in`. The boot's fold arrives through `Fold.of` and nowhere else. `Retractions.in` is in
the list although only the `Equivalences` statics were named in the issue: after the migration
`GraphProjector` genuinely does not call it, and leaving it out would let one whole-log walk return
with the fence green.

**The scope is the boot, and only the boot.** The fence names one class rather than the `ingest`
package, because `IngestService` calls the same statics on the *live* path, where there is no boot
and no fold to reuse. The dev tools — `census`, `export`, `own`, `rate`, `ratings`, `recommend`,
`retract` — are untouched.

## Alternatives considered

**Leave it.** The duplicated work is a multiple of the whole-log cost, and the log grows with every
expansion, so the price of doing nothing rises on its own. ADR 44's 2026-09-04 amendment already
prices a single pathological `Equivalences.in` at this log's scale; paying `in` three extra times
per boot is a cost with nothing to say for itself.

**Cache the fold across boots, or keep an on-disk projection.** ADR 24 rebuilds the graph from the
log at every boot and that decision stands. A cached fold is derived state that can disagree with
the log, which is precisely the failure the append-only design exists to make impossible. Rejected
on the standing decision, not on effort.

**Widen every reader's signature**, so the dev tools also take a prebuilt fold. YAGNI. Each of those
tools runs once and exits; the boot is the path that runs on every start of the server. A follow-up
issue can take the tools if a measurement ever asks for it — and, as the consequences below note,
they already collect half the saving without being touched.

**Make `Fold` compute its own answers** rather than carry them. A second home for a rule is exactly
the drift `BothFoldsAgreeTest` exists to catch. `Fold` carries; `Equivalences` decides.

**Count fold invocations with a static counter in `domain`.** The issue asked for a count pinned by
a test, and a counter needs a seam that exists only to be counted: a test-only mutable static in the
layer [ADR 18](0018-graph-engine-gremlin.md) keeps dependency-free and
[ADR 32](0032-layering-and-archunit.md) fences. Worse, it is fooled by exactly the
regression it would be guarding against — a reader that folds without incrementing passes it. The
fence states the property structurally instead.

**Change the fixed point** — memoise `emptiedCanonicalIds`, or cap its rounds. Explicitly out of
scope. ADR 44's amendment argues why no cap is imposed. Threading an already-computed set through is
not a change to the rule, and this issue changed no rule.

## Consequences

**The measurement.** Measured 2026-09-04 by this issue's Task 7 implementer, on a synthetic log
generated into a JUnit `@TempDir` by `FoldOnceBenchmark` at the scale
[ADR 57](0057-the-floor-reports-itself.md) publishes for the owner's real log, replayed into a fresh
`TinkerGraphStore`: **`GraphProjector.project` took a median 10,047 ms before and a median 9,825 ms
after** — three runs a side, alternating nothing else, in one sitting on one machine. The individual
runs were 10,305 / 10,047 / 10,028 ms before and 9,825 / 9,890 / 9,481 ms after. Reproducing it means
setting `SEGUE_BENCHMARK_ROWS` to that scale before running `FoldOnceBenchmark` — it defaults to a
small fixture otherwise — and passing Gradle `--rerun`, because an environment variable is not a
task input and a second run without it reports the task `UP-TO-DATE` and never re-logs the figure.

**The ratio is the claim, not the absolute.** The machine was heavily loaded throughout — one-minute
load averages between roughly 138 and 244 across the six runs — so no absolute figure here
transfers to another box or another day. What does transfer is that the three before-runs and the
three after-runs do not overlap: the slowest boot after the change is faster than the fastest boot
before it, under load that moved far more than the difference between the two sides. The after side
also ran on the busier half of the window — one-minute load at dispatch roughly 169–244 for the
after-runs against roughly 138–168 for the before-runs — so the comparison is biased against the
change, not for it.

**What the figure does not cover, and it is most of it.** The timing is the whole of
`GraphProjector.project`, which is dominated by the replay loop's per-row store writes — work this
change does not touch and does not claim to. So the saving reads as a small fraction of the boot
rather than a fraction of the fold. It is a **floor** on the saving rather than a ceiling for a
second reason too: the synthetic log is shallow in merges, carrying a handful, where the owner's
real log may not be, and the fixed point costs more the more merges and retractions interact.

**Every dev tool collects half the saving without being migrated.**
`Equivalences.folding(List)` now delegates to the new overloads, so it pays the fixed point once
rather than twice. Nothing that calls it was edited and nothing about its answer changed.

**The residual, stated rather than hidden.** `Retractions.in(log)` is still re-derived inside
`Equivalences.mergesIn`, `referencedEndpoints`, `nodesHeld`, `emptiedGiven` and `localsOfMerges` —
once per invocation of each. This decision removes the *fold* multiplier and not that one. Threading
a `Retractions` through those private methods is a separate change with its own risk, and whether it
is worth doing is a question the figures above inform rather than answer. The tool-side re-folds are
the other residual: `census`, `export`, `recommend` and `rate` each fold again per run, deliberately,
and a follow-up may take them.

**The fence is the pin.** Nothing about the boot's cost is asserted by any test — the machine is
loaded and a wall-clock assertion would be a flake generator, so `FoldOnceBenchmark` asserts only
that its fixture is the size it asked for and that the replay applied a non-zero count, and logs the
elapsed milliseconds. What is enforced is the structural property: `theBootFoldsOnce` is what stops a
second fold arriving in `GraphProjector`, and it was seen red under a plant that restored the
pre-migration body, naming all four call sites.
