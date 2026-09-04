# Fold the log once per boot — design

**Issue:** [#238](https://github.com/robsartin/segue/issues/238). **Scope: the boot only.**
Branch `238-ready`, from `787ecdc`.

## 1. What the code actually does today

The issue's premise holds. Verified against `787ecdc`:

`GraphProjector.project` reads the log once (`log.readAll()`, `GraphProjector.java:89`) and then
walks that one list four separate times through four log-taking rules:

| call site | rule | what it re-derives underneath |
| --- | --- | --- |
| `GraphProjector.java:90` | `Retractions.in(assertions)` | one whole-log walk |
| `GraphProjector.java:96` | `Equivalences.folding(assertions)` | `Equivalences.in` **and** `retractedStandIns` — the emptied-canonical-id fixed point runs **twice** here alone, because `in` calls `emptiedCanonicalIds` itself |
| `GraphProjector.java:104` | `Equivalences.standIns(assertions, KindMapper::rederive)` | `Equivalences.in` again (third fixed point) plus `localsOfMerges` |
| `GraphProjector.java:166` (the pre-flight) | `Equivalences.nodesTheFoldHolds(assertions)` | `standIns(log, identity)` → `Equivalences.in` again (fourth) plus `localsOfMerges` again, plus `nodesHeld` |

Two facts that matter for the design and are easy to get wrong:

- **`Equivalences.in` is not cheap.** It calls `emptiedCanonicalIds(log)`, which is the #228 least
  fixed point — a loop of whole-log walks. So "fold once" is not merely "call `in` once"; the
  emptied set has to be computed once and threaded through.
- **`Equivalences.folding(log)` pays the fixed point twice** (`in(log)` inside it, then
  `retractedStandIns(log)` beside it). That is the single largest duplicated cost per boot.

`Equivalences.java:568-572` already admits the multiplier in prose:

> That count is per *invocation* of this method, not per boot: `GraphProjector.project` invokes the
> fold — and so this loop — more than once while replaying a single log …

That sentence becomes false with this change and is corrected here.

### Where the issue's framing needs one correction

The issue says "and whatever `ingest` class the pre-flight lives in" as if the pre-flight were a
separate class. It is not: `refuseRowsNamingAnEntityNoNodeStandsFor` is a private static method of
`GraphProjector` itself. So the boot path is **one class**, and the fence below names one class
rather than a package. That matters, because `IngestService` — same package — calls
`Equivalences.nodesTheFoldHolds` and `Equivalences.folding` twice on the **live** path
(`IngestService.java:218`, `:233`, `:234`), where there is no boot and no fold to reuse. A
package-scoped fence would be wrong.

## 2. Decision

**One value, built once, handed to every reader.**

A new record `Fold` in `domain`:

```java
public record Fold(
    Retractions retractions,
    Equivalences equivalences,
    Map<String, NodeRecord> standIns,
    Set<String> nodesHeld)
```

with one static factory, `Fold.of(List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive)`.
No behaviour beyond accessors: it answers no question, it only carries four answers somebody else
computed. The `rederive` parameter is required rather than defaulted for `localsOfMerges`' own
stated reason — an overload quietly restoring identity is how a third fold would arrive with the
#222 kind lag and nothing saying so.

`Fold.of` computes the emptied set once and hands it to everything that needs it:

```java
Set<String> emptied = Equivalences.retractedStandIns(log);      // the fixed point, ONCE
Equivalences merges = Equivalences.in(log, emptied);            // no fixed point
Equivalences equivalences = Equivalences.folding(merges, emptied);
Map<String, NodeRecord> standIns = Equivalences.standIns(log, rederive, merges);
Set<String> held = Equivalences.nodesTheFoldHolds(log, standIns.keySet());
return new Fold(Retractions.in(log), equivalences, standIns, held);
```

### The four overloads this needs, and exactly what each is

Each is the existing method with the part the caller already has taken as a parameter; each
existing log-taking static keeps its signature and becomes a one-line delegation, so **every tool
keeps the API it has** (ADR 41's exporter, `census`, `own`, `rate`, `ratings`, `recommend`,
`retract` are untouched by this issue and stay that way).

| new overload | body | old method becomes |
| --- | --- | --- |
| `public static Equivalences in(List<LoggedAssertion> log, Set<String> emptied)` | `new Equivalences(mergesIn(log), referencedEndpoints(log, emptied))` | `in(log)` → `in(log, emptiedCanonicalIds(log))` |
| `public static Equivalences folding(Equivalences merges, Set<String> retractedStandIns)` | `new Equivalences(merges.canonicalByLocal(), merges.referencedEndpoints(), retractedStandIns)` | `folding(log)` → `folding(in(log), retractedStandIns(log))` |
| `public static Map<String, NodeRecord> standIns(List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive, Equivalences merges)` | today's body without its first line | `standIns(log, rederive)` → `standIns(log, rederive, in(log))` |
| `public static Set<String> nodesTheFoldHolds(List<LoggedAssertion> log, Set<String> standInIds)` | `unmodifiableSet(nodesHeld(log, standInIds))` | `nodesTheFoldHolds(log)` → `nodesTheFoldHolds(log, standIns(log, identity).keySet())` |

**These overloads are trust-the-caller, and their javadoc has to say so.** `in(log, emptied)` is
correct only for `emptied = retractedStandIns(log)` *of that same log*; `folding(merges, …)` is
correct only for merges built from that log. That is why they exist and why nothing widens further:
the one caller that can honour the contract is `Fold.of`, and the ArchUnit fence below is what keeps
it the only boot-side caller. Each overload is pinned to its log-taking twin by a test that asserts
the two answers are equal on a log carrying a merge, a retraction that empties a canonical id, and
an edge naming it — so the pin can actually fail.

**`nodesTheFoldHolds(log, standInIds)` is safe with the real `rederive`'s key set.** The log-taking
form uses `standIns(log, identity).keySet()`; `Fold.of` passes `standIns(log, rederive).keySet()`.
Those are the same set, and that is not assumed —
`EquivalencesTest.shouldNameTheSameCanonicalIdsWhateverKindTheFoldDerives` already pins it under two
re-derivations that disagree about every kind.

### `GraphProjector.project` after the change

```java
List<LoggedAssertion> assertions = log.readAll();
Fold fold = Fold.of(assertions, KindMapper::rederive);
refuseRowsNamingAnEntityNoNodeStandsFor(assertions, fold);
for (NodeRecord standIn : fold.standIns().values()) { store.upsertNode(standIn); }
… fold.retractions().survives(i, assertion) … fold.equivalences() …
```

and the pre-flight takes `Fold` instead of `(retractions, equivalences)` and reads
`fold.nodesHeld()` instead of calling `Equivalences.nodesTheFoldHolds`.

**Statics the boot no longer calls, which STAY for the tools:** `Retractions.in`,
`Equivalences.folding(List)`, `Equivalences.standIns(List, UnaryOperator)`,
`Equivalences.nodesTheFoldHolds(List)`. `Equivalences.in(List)`,
`Equivalences.retractedStandIns(List)` and `Equivalences.localsOfMerges(List, UnaryOperator)` were
never called from `GraphProjector` directly and are unchanged.

### The pin: an ArchUnit fence, not a call counter

The issue asks for "a count of fold invocations per boot pinned by a test". A counter needs a seam
in `domain` that exists only to be counted — a static mutable counter, or an injected recorder — and
that seam is itself a way for the count to go wrong. The fence states the same property
structurally and cannot be fooled by a reader that folds without incrementing:

`ArchitectureTest.theBootFoldsOnce` — `GraphProjector` may not call `Equivalences.in`, `folding`,
`standIns`, `nodesTheFoldHolds`, `retractedStandIns`, `localsOfMerges`, or `Retractions.in`. The
boot's fold arrives through `Fold.of` and nowhere else. `Retractions.in` is in the list although the
issue names only the `Equivalences` six: after the migration `GraphProjector` genuinely does not
call it, `Fold` holds the index, and leaving it out would let one whole-log walk return with the
fence green.

Positive control, written out as plan steps: put `Equivalences.in(assertions);` back into
`GraphProjector.project`, run `ArchitectureTest`, quote the violation, remove it.

### The measurement

A `@EnabledIfEnvironmentVariable`-gated benchmark, **not** a `@Disabled` test and **not** a new
Gradle task:

- `@Disabled` cannot be run by `--tests` at all, so the figure would need a source edit to reproduce.
- A new `benchmark` Gradle task is a build change for one number; `SEGUE_REQUIRE_BROWSER` and
  `SEGUE_REQUIRE_GRAPHVIZ` already establish "an env var decides whether this runs" as this
  project's idiom, and it costs nothing.
- It still **compiles** under `./gradlew check`, so it cannot rot silently the way an unbuilt
  script would.

`FoldOnceBenchmark` builds a synthetic log at the real log's published scale — 318,116 assertions
([ADR 57](../../adr/0057-the-floor-reports-itself.md) holds the figure; an aggregate is publishable
under ADR 51) — into a `SqliteAssertionLog` under a JUnit `@TempDir`, and times
`GraphProjector.project` into a fresh `TinkerGraphStore`. Never `~/.segue`. Every generated
identifier carries a leading zero (ADR 58), so it denotes nothing and can never denote anything.

**No wall-clock assertion anywhere in the suite** (the machine is loaded). The benchmark asserts
only that the fixture is the size it asked for and that the replay applied a non-zero count — so it
is not timing an empty list — and prints the elapsed milliseconds. The dated before/after figures
live **once**, in ADR 0064; `Fold`'s javadoc cites the ADR rather than restating a number.

Both ends are measured in one sitting on one machine: run it on `HEAD` for the *after*, temporarily
restore the pre-migration body of `GraphProjector.project` for the *before*, revert the plant. Two
numbers taken minutes apart on the same box are comparable in a way that two numbers from two
sessions are not.

## 3. Alternatives rejected

| alternative | why it lost |
| --- | --- |
| **Leave it.** | The cost is a multiple of the whole-log work and the log grows with every expansion. ADR 44's 2026-09-04 amendment already measures 8.6 s inside `Equivalences.in` for a pathological fixture at 318k rows; paying `in` three extra times per boot is a cost with nothing to say for itself. |
| **Cache the fold across boots, or keep an on-disk projection.** | [ADR 24](../../adr/0024-sqlite-assertion-log.md) rebuilds the graph from the log at every boot, and that decision stands — a cached fold is derived state that can disagree with the log, which is the failure the whole append-only design exists to make impossible. The issue rules it out explicitly. |
| **Widen every reader's signature** so `census`, `export`, `own`, `rate`, `ratings`, `recommend` and `retract` also take a prebuilt fold. | YAGNI, and out of scope by the issue's own boundary. Each of those is a dev tool that runs once and exits; the boot is the path that runs on every start of the server. A follow-up issue can take the tools if a measurement ever asks for it. |
| **Make `Fold` compute its own answers** (methods rather than accessors). | A second home for rules that live in `Equivalences`, which is the drift `BothFoldsAgreeTest` exists to catch. `Fold` carries; `Equivalences` decides. |
| **Count fold invocations with a static counter in `domain`.** | A test-only mutable static in the layer ADR 18 keeps dependency-free, and a reader that folds without incrementing passes it. The ArchUnit fence states the property structurally. |
| **Change the fixed point** (memoise `emptiedCanonicalIds`, cap the rounds). | Explicitly not this issue. ADR 44's amendment argues why no cap is imposed. Threading the already-computed set through is not a change to the rule. |

## 4. What must not change

No fold rule and no fixed point. No reader's answer. `BothFoldsAgreeTest`'s applied count stays at
30. `StandInAgreesInEveryHomeTest` is not edited. Every existing log-taking static keeps its
signature and its behaviour.

## 5. Residual, stated rather than hidden

`Retractions.in(log)` is still called from inside `Equivalences.mergesIn`, `referencedEndpoints`,
`nodesHeld`, `emptiedGiven` and `localsOfMerges` — once per invocation of each. This issue removes
the *fold* multiplier, not that one, and threading a `Retractions` through those private methods is a
separate change with its own risk. Whether it is worth doing is a question for the measurement in
ADR 0064 to inform, not for this issue to answer.
