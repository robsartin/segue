# Fold the log once per tool — design

**Issue:** [#246](https://github.com/robsartin/segue/issues/246). **Scope: the dev tools that read
the log directly.** Branch `246-ready`, from `da8efa9`.

[ADR 64](../../adr/0064-fold-the-log-once-per-boot.md) made the boot fold once and named the tool
side as its first residual. This issue takes that residual. It changes no fold rule, no fold
answer, and no tool's output.

## 1. What the code actually does today

### 1.1 What "one fold" is counted in

The unit throughout is a **whole-log fixed point**: one invocation of the private
`Equivalences.emptiedCanonicalIds`, which is a loop of whole-log walks. Derived from the code at
`da8efa9`:

| entry point | fixed points it pays |
| --- | --- |
| `Equivalences.in(List)` | 1 (`emptiedCanonicalIds`) |
| `Equivalences.retractedStandIns(List)` | 1 (it *is* `emptiedCanonicalIds`) |
| `Equivalences.folding(List)` | 1 (`retractedStandIns`, then `in(log, emptied)` which pays none) |
| `Equivalences.standIns(List, UnaryOperator)` | 1 (opens with `in(log)`) |
| `Equivalences.nodesTheFoldHolds(List)` | 1 (via `standIns(log, identity)`) |
| `Fold.of(List, UnaryOperator)` | 1 (`retractedStandIns`, then the three caller-trusting overloads) |
| `Retractions.in(List)` | 0 — a whole-log walk, not a fixed point |
| `Equivalences.localsOfMerges(List, UnaryOperator)` | 0 |

`Retractions.in` re-derivation inside `mergesIn`, `referencedEndpoints`, `nodesHeld`, `emptiedGiven`
and `localsOfMerges` is ADR 64's other, explicitly separate residual and is **not** this issue's
business.

### 1.2 Per tool, read off the call sites

| tool | fixed points per run today | whole-log reads today | call sites |
| --- | --- | --- | --- |
| `census` | **5** | 2 | `Census.of` reads the log, then `LogProjection.of(log)` reads it again and pays 2 (`standIns`, `folding`); `ClaimCensus.of` pays 2 (`Equivalences.in`, `standIns`); `TasteCensus.of` pays 1 (`standIns`) |
| `export`, whole-log views (`full`, `subgraph`) | **2** | 1 | `LogProjection.of` — `Retractions.in` 0, `standIns` 1, `folding` 1 |
| `export`, bounded views | 1 | 1 | inside `GraphProjector.project`; nothing after it |
| `retract` | **3** | 2 | `measure` reads the log and pays 0; `strandedByThisRetraction` reads it again and pays `retractedStandIns(after)` 1, `retractedStandIns(before)` 1, `folding(after)` 1 |
| `recommend` | **2** | 2 | `GraphProjector.project` 1, then a second `assertions.readAll()` + `Equivalences.in` 1 |
| `rate` | **2** | 2 | same shape, through `RateCli.deck` |
| `evaluate` | **2** | 2 | same shape, in `EvaluateCli.run` |
| `own` | **1** (`Assert`, `Merge`); **0** (`Mint`) | 1 | `OwnRun.assertEdge` → `Equivalences.in`; `OwnRun.declareMerge` → `Equivalences.in`. One branch runs per invocation |
| `ratings` | **1** | 1 | `Labels.forQids` → `Equivalences.in`; its `Retractions.in` pays none |

### 1.3 Three places the issue's premise does not match the code

**(a) `census` pays five, not three.** ADR 64's consequences bullet reads "`census`, and `export`'s
whole-log views, fold through `LogProjection.of` — three to two", and the issue repeats it. That is
the count for `LogProjection.of` alone. `census` also runs `ClaimCensus.of` and `TasteCensus.of`,
which fold the same rows three more times, and it reads the whole log twice. Attributing all of
`census`'s folding to `LogProjection.of` understates it by three fixed points and one whole-log
read. Designed against the code; the ADR 64 amendment in Task 9 says what it corrects.

**(b) `own` and `ratings` have nothing to collect, because each already folds once per run.** The
issue lists them as tools that "still pay extra fixed points per run … each folding again through
`Equivalences.in` or `standIns` after or instead of a boot". They fold once, not again: neither
reaches a boot, neither calls `standIns` at all (each implements the stand-in rule inline — those
are two of ADR 59's four homes, held together by `StandInAgreesInEveryHomeTest`), and each calls
`Equivalences.in` exactly once on the one list it read. **So this issue changes no code in `own` or
`ratings`**, and no fence is added for them: there is no new single-home property to pin, and a
fence over code this issue does not touch is a separate decision. Their counts are recorded in the
ADR 64 amendment as verified-unchanged rather than assumed.

**(c) `evaluate` is a fourth tool with the boot-then-refold shape, and the issue does not name it.**
`EvaluateCli.run` (ADR 65, issue #242 — filed after ADR 64 was written) replays through
`GraphProjector.project` and then does a second `assertions.readAll()` plus `Equivalences.in`,
exactly as `recommend` and `rate` do. It is in scope: the fence in Task 8 states "a tool that
replays does not fold again", and a fence that skipped `evaluate` would be green while the third
copy of the defect sat inside it.

## 2. Decision

**Each tool derives the log's fold once per run, in one place, and hands it to every reader.** Three
different mechanisms, chosen per tool by what the tool is actually allowed to know.

### 2.1 The tools that need the whole fold take a `Fold`

`export`'s `LogProjection` and `census` both need stand-in nodes with re-derived kinds, the folding
`Equivalences`, the retractions and (for `census`) the same rows twice. Both packages already name
`KindMapper`, so both can build a `Fold`.

- `LogProjection.of(List<LoggedAssertion> logged, Fold fold)` becomes the real body;
  `LogProjection.of(AssertionLog log)` keeps its signature and becomes
  `of(logged, Fold.of(logged, KindMapper::rederive))` over one `readAll`. Roughly fifty test call
  sites and `ViewSelector` use the one-argument form and are untouched.
- `Census.of` reads the log once, builds one `Fold`, and hands both to `LogProjection.of`,
  `ClaimCensus.of` and `TasteCensus.of`. `TasteCensus` then needs no log rows at all.
- `Census`'s javadoc paragraph "**The log is read twice** … The alternative is an overload on
  `LogProjection` taking an already-read list, which widens another package's public API for a dev
  tool's convenience" records the rejected alternative this issue takes. It is corrected in the same
  commit: the overload is now taken, because it is what carries the `Fold` as well as the rows, and
  the second read went with it.

### 2.2 The tools that replay take the boot's fold back

`GraphProjector.project` already builds exactly the `Fold` that `recommend`, `rate` and `evaluate`
then re-derive from a second read of the same log. Returning it is the whole change.

```java
/** What one replay produced: how many assertions it applied, and the fold it applied them under. */
public record Replay(long applied, Fold fold) { … }

public static Replay replay(AssertionLog log, GraphStore store, IdentityMerge merges) { … }

public static long project(AssertionLog log, GraphStore store, IdentityMerge merges) {
  return replay(log, store, merges).applied();
}
```

`project` keeps its signature, its javadoc and every one of its call sites — one production caller
in `app`, one in `export`, three in the tools being migrated, and roughly sixty in tests.

**Why a result record and not `project(log, store, merges, Consumer<Fold>)`.** A consumer cannot
return a value, so every one of the three callers would have to invent a mutable holder — an
`AtomicReference`, or a one-element array — whose only purpose is to defeat the callback and get the
`Fold` back out. That is a seam that exists to be worked around. It also says nothing about *when*
the consumer runs relative to the replay, which a caller reading the fold afterwards has to know. A
record is a value with an obvious meaning, matches `Fold`'s own carry-don't-decide shape, and adds
one type instead of one control-flow convention.

**Why not change `project`'s return type outright.** Sixty-odd call sites in one commit is the
big-bang change ADR 4 forbids; the overload is the parallel field.

**Why the tools may take `fold.equivalences()` where they had `Equivalences.in(log)`, and it is not
an answer change.** `folding(merges, emptied)` differs from `in(log)` in exactly one record
component, `retractedStandIns`, and that component is read by exactly two methods —
`namesARetractedStandIn` and `foldEndpoints`, which delegates to it. `stands`, `last`, `canonical`,
`merged` and `resolve` read only `canonicalByLocal` and `referencedEndpoints`, which are identical
between the two. No class in `recommend`, `rate`, `evaluate` or `census` calls either of the two
methods that differ. `Equivalences.folding(List)`'s own javadoc already states this ("Every other
caller of `in()` — `OwnRun`, `RateCli`, `ratings/Labels` and `RecommendCli` — … neither folds an
edge nor asks that question"); this design does not take it on the javadoc's word. Task 2 pins it
with a test over a log whose emptied set is non-empty, and proves the pin can fail.

That javadoc list is also stale in two ways once this lands, and is corrected in the commits that
make it stale: it omits `EvaluateCli`, which has called `in` since #242, and it will name `RateCli`
and `RecommendCli` after they stop calling it.

### 2.3 `retract` threads the emptied set, and keeps two folds because it asks two questions

`RetractRun.strandedByThisRetraction` compares the log as it stands against the log this retraction
would produce. Those are two different lists, so two fixed points is the floor, and the third is the
one to remove: `Equivalences.folding(after)` recomputes `retractedStandIns(after)` that the method
is already holding.

```java
Set<String> emptiedAfter = Equivalences.retractedStandIns(after);
Set<String> newlyEmptied = new LinkedHashSet<>(emptiedAfter);
newlyEmptied.removeAll(Equivalences.retractedStandIns(before));
…
Equivalences equivalences =
    Equivalences.folding(Equivalences.in(after, emptiedAfter), emptiedAfter);
```

Three to two, with the caller-trusting overloads used exactly as their contract requires: the set is
`retractedStandIns` of that same list.

**`retract` must not build a `Fold`, and that is a decision rather than an omission.** `Fold.of`
requires a `rederive`, and `Equivalences.retractedStandIns` carries no such parameter *precisely* so
that `retract` can call it — its javadoc says so, on ADR 44's "a retraction is nobody's vocabulary".
Handing `Fold.of` a `KindMapper::rederive` would teach `retract` Wikidata's vocabulary to compute a
set that does not depend on it; handing it `UnaryOperator.identity()` would put stand-in nodes
carrying the ADR 42 kind lag into a value the next reader of `retract` would reasonably trust. No
ArchUnit rule catches either, which is why it is written down here.

**Its second whole-log read stays.** `measure` and `strandedByThisRetraction` each call
`log.readAll()`. Threading one list through both is a read saving, not a fold saving, and this issue
is about folds; it is recorded as a residual rather than done in passing.

### 2.4 Fences: three, and the three tools that do not get one

Extending `ArchitectureTest.theBootFoldsOnce`'s idea per tool, **only where the tool ends up with one
obvious home for its fold**. Each new rule forbids the seven log-taking statics *and* `Fold.of` to
every class in the package but the one home — `Fold.of` included, because a second `Fold` built
elsewhere in the package is a second fold that a statics-only fence would not see.

| tool | fence | the one home | why |
| --- | --- | --- | --- |
| `export` | `theExportFoldsOnce` | `LogProjection` | `LogProjection` is already the only class in `export` that folds; the one-argument `of` is now the only place a `Fold` is built |
| `census` | `theCensusFoldsOnce` | `Census` | after the migration `Census.of` builds the single fold and the five section types take what it holds |
| `recommend`, `rate`, `evaluate` | `theReplayingToolsTakeTheBootsFold` | none — no class in any of the three may fold | their fold arrives from `GraphProjector.replay`; one rule over three packages, because it states one property |

**No fence for `retract`**, on the issue's own boundary: it folds twice, legitimately, for two
different questions about two different lists. A package fence would have to name `RetractRun` as an
exception, and a rule whose only clause is "`RetractRun` may fold" is green while `RetractRun` folds
five times — it would pin nothing this issue is about.

**No fence for `own` or `ratings`**, because this issue changes no code in either (§1.3(b)). Each
already folds once; a fence there would guard a property this issue did not create, on code it did
not touch.

Every fence gets a planted positive control written out as plan steps: put a forbidden call into a
class the rule covers, run `ArchitectureTest`, quote the named violation, remove the plant.

## 3. What must not change

- **No fold rule and no fixed point.** `emptiedCanonicalIds`, `emptiedGiven`, `referencedEndpoints`,
  `reference`, `nodesHeld`, `standInCanonicalIds`, `mergesIn`, `localsOfMerges`, `foldEndpoints`,
  `Retractions.survives` and `Retractions.reaches` keep their bodies exactly.
- **No answer.** `BothFoldsAgreeTest`, `StandInAgreesInEveryHomeTest` and `CensusIsSafeToPasteTest`
  are not edited, and every tool's own tests stay as they are and stay green.
- **No signature any caller depends on.** `LogProjection.of(AssertionLog)`,
  `GraphProjector.project(AssertionLog, GraphStore, IdentityMerge)` and every log-taking static in
  `Equivalences` and `Retractions` keep theirs.
- **No output.** No tool prints a different line, a different count or a different order.
- `theBootFoldsOnce` is not edited.

## 4. Alternatives rejected

| alternative | why it lost |
| --- | --- |
| **Leave it** — ADR 64 said the tools were out of scope. | It said the tools were out of scope *for that issue*, and named them as the residual a follow-up may take. This is that follow-up, and the counts in §1.2 say what each still pays. |
| **One mechanism for every tool: every tool builds a `Fold`.** | `retract` may not (§2.3), and `ratings` is fenced from `ingest` and would have to learn Wikidata's vocabulary to build one. A uniform mechanism here costs two stated decisions to buy tidiness. |
| **Give `Fold.of` a no-`rederive` overload** so `retract` and `ratings` could use it. | Exactly what `Fold`'s javadoc and ADR 64 forbid: "an overload that quietly restored `identity()` is how a third fold would arrive carrying the kind lag ADR 42 exists to close, with nothing at the call site saying so." |
| **A second `Fold`-shaped carrier holding only the merges and the emptied set**, for `retract` and the tools that ask about ratings. | A second home for the same four answers, which is the drift `BothFoldsAgreeTest` exists to catch, bought for two call sites. The existing caller-trusting overloads already carry the value; nothing new is needed. |
| **`project(log, store, merges, Consumer<Fold>)`.** | §2.2: every caller has to invent a mutable holder to get the value back out, and the callback's ordering relative to the replay is a convention rather than a type. |
| **Change `project`'s return type** from `long` to a record. | Sixty-plus call sites edited in one commit — the big-bang change ADR 4 forbids. The overload is the parallel field, and `project` stays. |
| **Cache a fold between the tool's own reads**, or between runs. | ADR 24 rebuilds from the log every time and that decision stands; a cached fold is derived state that can disagree with the log. Threading a value within one run is not caching. |
| **Also thread the log list through `retract`'s two reads**, and through `own`'s helpers. | Read savings, not fold savings, and out of this issue's stated boundary. Recorded as residuals. |
| **Also remove the re-derived `Retractions.in` inside `Equivalences`' private methods.** | ADR 64's other residual, stated there as a separate change with its own risk. Not this issue. |
| **Count folds per run with a counter** rather than fencing. | ADR 64 rejected this for the boot and the reasons carry: a test-only mutable static in `domain`, fooled by a reader that folds without incrementing. |
| **Fence `retract`, `own` and `ratings` too**, for symmetry. | §2.4: for `retract` the fence would be vacuous; for `own` and `ratings` it would guard code this issue does not touch. |

## 5. What this buys, in counts rather than seconds

Stated as fixed points and whole-log reads per run, derived from the code both before and after. **No
timing figure is claimed anywhere in this issue**, and none is measured: ADR 64 already records what
the boot's share was worth, the tools run once and exit, and the machine is loaded.

| tool | before | after |
| --- | --- | --- |
| `census` | 5 folds, 2 reads | 1 fold, 1 read |
| `export`, whole-log views | 2 folds, 1 read | 1 fold, 1 read |
| `export`, bounded views | 1 fold, 1 read | unchanged |
| `retract` | 3 folds, 2 reads | 2 folds, 2 reads |
| `recommend` | 2 folds, 2 reads | 1 fold, 1 read |
| `rate` | 2 folds, 2 reads | 1 fold, 1 read |
| `evaluate` | 2 folds, 2 reads | 1 fold, 1 read |
| `own` | 1 fold (0 on `mint`), 1 read | unchanged, and unchanged deliberately |
| `ratings` | 1 fold, 1 read | unchanged, and unchanged deliberately |

The after-column is what Task 9's ADR 64 amendment records, **verified by reading the post-change
call sites** rather than by subtracting from the before-column.

## 6. Residuals, stated rather than hidden

- `Retractions.in(log)` is still re-derived inside `Equivalences.mergesIn`, `referencedEndpoints`,
  `nodesHeld`, `emptiedGiven` and `localsOfMerges`, once per invocation of each. ADR 64's residual,
  untouched.
- `retract` still reads the whole log twice, and `own`'s `labelsInTheProjection` and
  `mintedInTheProjection` each call `Retractions.in` on the same list. Read savings, not fold
  savings.
- `retract` still folds twice per run, and that is the design rather than a shortfall.
- `own` and `ratings` still fold once per run. There was never more than one to remove.
