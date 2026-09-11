# `expandPromotions --rated-since`: expand only what was rated since an instant

Issue #307. Written 2026-09-11 against `307-ready`, which is `origin/main`.
Everything below was read from the code in this worktree; no database was opened, no dev task was
run, and every identifier named here is invented and carries ADR 58's leading zero.

The expander (ADR 66, #284) expands every promotion every run. The harness already has the
instrument for "only what changed since" — `evaluate --rated-since <instant>` (#276, ADR 65). This
issue gives the expander the same flag with the same meaning, reusing the harness's machinery
rather than copying it.

## What the issue assumes, and what the code actually is

The issue's substance holds. Seven places where it is looser than the code, or says something the
code does not, are recorded here rather than left for the implementer to trip over.

1. **`Equivalences.resolveUpdatedAt` does not move: it already lives in a shared home.** It is a
   method on `domain/Equivalences.java:1151`, beside `resolve`, and its javadoc already argues why
   it belongs there ("note-free and score-free by construction, which is what keeps this method in
   `domain`"). `expand` already depends on `domain` and already calls `merges.resolve(...)`. The
   only thing that moves is `RatingAge`.

2. **`RatingAge` moves from `evaluate` to `domain`, not to `support`.** `support` is "cross-cutting
   plain-Java helpers with no project dependencies" — `UuidV7`, `QidList`, `ClassLabels`,
   `DefaultDatabase`, `RequiredDatabase`. `RatingAge` is not a helper; it is a pure rule over the
   ratings map, which is exactly what the guide's package table says `domain` is for and exactly
   what `KnownList` is. It satisfies both `domain` rules without change:
   `domainValueTypesAreRecordsOrEnums` (it is a record) and `domainHasNoThirdPartyDependencies` (it
   imports `java.time`, `java.util` and nothing else).

3. **One javadoc reference in `RatingAge` cannot survive the move as written.** Its class javadoc
   says `{@link HeldOut#every}`, and `HeldOut` is in `evaluate`. After the move that link resolves
   only through an import or a fully-qualified name, and `domain` may not depend on `evaluate` —
   `domainHasNoThirdPartyDependencies` allows `..domain..`, `java..` and `javax..` and nothing else.
   It becomes `{@code HeldOut.every}`, plain code text with no link, which is the repository's usual
   shape for naming a class it must not depend on.

4. **The set handed to `RatingAge.of` is the promotions, not every rated entity.** The harness
   passes `ratings.keySet()` because its population *is* every rated entity. The expander's
   population is `KnownList.promoted(List.of(), ratings)`. Passing the promotions is both the
   narrower read and the more honest refusal: a rated-but-not-promoted entity with a missing
   timestamp is not a reason to stop an expansion run, because that entity was never going to be
   visited. `RatingAge.of`'s `@param rated` javadoc says "every qid the run's ratings map names",
   which is a description of the harness's caller rather than of the method; it is reworded to say
   what the parameter is — the qids whose age the caller needs — in the move commit.

5. **A promotion with no timestamp is refused, and no new refusal is written.** This is the
   issue's open question, and the answer is: inherit `RatingAge`'s refusal unchanged. It is
   consistent with #276 and with ADR 65's own rejected alternative ("treating a rated entity with no
   timestamp as old — a lenient read feeding a guard turns 'cannot tell' into 'old'"), and here the
   lenient reading would be worse still: excluding a promotion silently means an entity the owner
   asked to expand is never visited and nothing says so. `RatingAgeTest`'s
   `shouldRefuseThePopulationWhenARatedEntityHasNoTimestamp` already holds it, and the sentence it
   throws names no qid and no count. **No second copy of that test is written**, and no
   `expand`-level test of it either: there is no way to build the condition through `ExpandCli`
   without a store whose two selects over one table disagree, which is the state the sentence says
   is impossible.

6. **The step-5 runbook table has no `before` column.** Its columns are `line | direction | why`.
   What the issue means by "the `before` column" is the comparison the whole table is: step 5 reads
   step 4's census against step 1's. The variant section says what that comparison means when only
   part of the population ran, in prose, rather than editing the table.

7. **The golden block is not duplicated for the with-instant form.** `GOLDEN_BLOCK` is 28 lines and
   the only difference the instant makes is one added line. A second 29-line golden block would pin
   28 lines twice and red twice on every future change to any of them. Both forms are pinned as
   literals, but the with-instant form is pinned as the **two header lines** of a block whose body
   is already pinned, in its own test. The no-instant `GOLDEN_BLOCK` and the dry-run block in
   `shouldRenderTheDryRunBlockWhenNothingIsAppended` are **byte-identical to today's**.

## Decisions

### The flag, parsed exactly as the harness parses it

`--rated-since <ISO-8601 instant>`, optional. Absent is today's behaviour in every respect,
including that no timestamp is read at all.

`ExpandCli.parse` already collects flag/value pairs into a `LinkedHashMap` and refuses a repeated
flag, so `--rated-since` given twice is refused by machinery that exists. The parse itself is
`EvaluateCli.instant`'s, character for character:

    --rated-since takes an ISO-8601 instant like 2026-09-06T15:00:00Z, got <value>.

`ExpandCli.USAGE` gains `[--rated-since <ISO-8601 instant, e.g. 2026-09-06T15:00:00Z>]`, the same
clause `EvaluateCli.USAGE` carries. `ExpandCli.Options` gains an `Optional<Instant> ratedSince`
component; nothing constructs `Options` outside `parse`, so the component is additive.

### The filter, at the one place that already composes the population

`ExpandCli.run` builds `promotions` today as
`KnownList.promoted(List.of(), merges.resolve(affinity.readRatings()))`. With an instant it keeps
the promotions `RatingAge.isNew` answers true for:

```java
List<String> promoted = KnownList.promoted(List.of(), ratings);
Optional<RatingAge> age =
    options
        .ratedSince()
        .map(
            since ->
                RatingAge.of(
                    since,
                    merges.resolveUpdatedAt(affinity.readUpdatedAt()),
                    Set.copyOf(promoted)));
List<String> promotions = age.map(it -> promoted.stream().filter(it::isNew).toList()).orElse(promoted);
```

No new filtering method is written. `RatingAge.isNew` is the predicate, `Stream.filter` is the
loop, and the order `KnownList.promoted` established survives because `filter` preserves it.
`readUpdatedAt` is called only inside the `map`, so **a run with no `--rated-since` reads no
timestamp at all** — ADR 16's data minimisation falling out of the shape, exactly as it does in
`EvaluateCli`.

A re-rated old promotion is included, because `updated_at` is the last write (ADR 39). That is the
limit, and the block states it.

### `RatedSince`: one value carrying the filter into the report

A new record in `expand`, beside `Preflight`:

```java
public record RatedSince(Instant since, int excluded) {}
```

The compact constructor requires a non-null instant and `excluded >= 0`. Both blocks take
`Optional<RatedSince>`.

**Why a record and not two parameters.** `EvaluationReport.lines` takes the instant and its two
counts as three positional arguments and then carries a runtime guard for the combination that
cannot happen ("no instant was given, so there are no halves to state"). `Optional<RatedSince>`
makes that combination unrepresentable instead of guarded, and it keeps `excluded` from sitting
next to `maxNewEdges` as a second bare `int` two positions along a call — which is a swap a compiler
cannot see. The type-level claim ADR 66's output contract makes is unchanged and is now worth
restating: `Instant` and `int` are the only two things in the new parameter, and `Instant.toString`
can emit only digits, `-`, `:`, `.`, `T` and `Z`.

### The output: a header clause, not a row

The instant is named on a second `#` line under the block's own header, in both blocks, rendered by
one private method so the two cannot drift:

    # only promotions rated on or after 2026-09-08T00:00:00Z: 7 excluded (rated before it) — a rating's timestamp is its last write, so a re-rated old promotion counts as new.

**Why a clause rather than a `  excluded` row.** A row would have to be printed on every run,
including runs with no instant, where it would read `  excluded  0` — a count of a filter nobody
applied. ADR 65 refused exactly that shape in exactly those words: "a split line naming a division
nothing made is a line a reader would believe". It would also widen the label column of every
existing block by two characters and re-pad every count in the golden block, for a line that is
zero on most runs. The clause appears only when there is something to say, and the no-instant blocks
stay byte-identical.

**One line, two blocks.** The dry run says how many the instant excluded through the same clause
the real run uses. That is what makes step 2's arithmetic still readable in the variant: `considered`
is the filtered count, `excluded` is what the instant removed, and `considered + excluded` is the
whole promoted population.

The instant is rendered from the parsed `Instant`, never from the string the operator typed.

### `considered` counts promotions after the filter

`ExpansionTally.considered` and `Preflight.considered` are both `promotions.size()` already, so the
filter lands on them with no new field and no new arithmetic. The identity
`considered == expanded + refused + failed` is untouched. `Preflight`'s javadoc for `considered`
gains the clause that says which population "was handed" means under a filter.

### Both arities stay, and both have a production caller

`ExpansionReport.lines(tally)` / `dryRunLines(preflight)` and `ExpandRun.run(promotions, max, lines)`
/ `dryRun(promotions, lines)` keep their signatures and delegate to the new
`Optional<RatedSince>`-taking forms with `Optional.empty()`. `ExpandCli` calls the short form when
`--rated-since` is absent and the long form when it is given, so neither arity is a test-only
convenience.

**Why not edit the call sites.** `ExpandRun.run` has thirteen call sites in `ExpandRunTest` and
`ExpansionIsSafeToPasteTest` and `dryRun` has two, and not one of them is about the filter.
Rewriting fifteen call sites to say `Optional.empty()` is a fifteen-line diff with no red behind it
and fifteen chances to change an argument nobody is looking at. The delegating form states "no
filter was applied" once, in the class that owns the default.

### The fence widens and is renamed

`ArchitectureTest.onlyTheEvaluationHarnessReadsWhenARatingChanged` becomes
**`onlyTheHarnessAndTheExpanderReadWhenARatingChanged`**, admitting `..evaluate..` **or**
`..expand..`.

ADR 65's 2026-09-06 amendment says of this rule: "It is a new rule rather than a widening of either
sibling ... a rule named for one tool and quoted in an immutable ADR does not get stretched to cover
a second." #307 is that second tool, and the resolution is the rename rather than a second rule: the
sentence's objection is to a rule whose **name** becomes false, and the fix for a false name is a
true one. The precedent is in the same file — `onlyTheRecommenderReadsEveryRating` now admits five
packages and keeps its name, and its javadoc says why: "only the recommender" had already become
shorthand for "a dev-side tool and nothing on the MCP surface", so the name did not go false. This
name would have. A second rule of identical shape under a second name is what ADR 64's
`theReplayingToolsTakeTheBootsFold` row in the guide already calls out as how `evaluate` grew a
defect: "a copy under a new name".

**Why data minimisation still holds.** The expander already reads the whole ratings map
(`onlyTheRecommenderReadsEveryRating` has admitted `..expand..` since #284), so it already holds
every qid the owner has rated. The keyset is what that fence protects, and this read adds no entity
the tool did not already have in memory one line earlier. What it adds is one `Instant` per entity,
which is neither a note nor a score. The reason that stays true is structural rather than a promise:
`readUpdatedAt` returns `Map<String, Instant>` and there is nowhere in it to put either.

The read stays inside `ExpandCli`, the only class in `expand` that touches the store, exactly as it
stays inside `EvaluateCli` in `evaluate`.

**The planted control is a call from a third package.** `census.CensusCli` holds an `AffinityStore`
in its try-with-resources (`CensusCli.java:126`), so one added line there is a real call from a
package the widened rule still excludes. It must fire, and it is reverted.

### Documents

- **The rule table row** in `docs/developer-guide.md` is renamed and its "What it forbids" cell
  widened. This reds `DeveloperGuideEnumerationsTest.shouldNameEveryArchUnitRuleWhenTheGuideTabulatesThem`,
  which derives the declared rule names by reflection and compares the sets both ways.
- **`docs/developer-guide.md:2355`**, in the evaluation-harness chapter, says the rule "keeps that
  read inside this package". That sentence goes false on the rename and is corrected. Nothing reads
  it, so it is gate-verified prose.
- **The `domain` row** of the package table gains `RatingAge`; **`CLAUDE.md`'s `domain/` block**
  gains the same clause. `CLAUDE.md` is not a declared input of `test` (only `docs` and `README.md`
  are, `build.gradle.kts:150`), so that edit is verified by the gate alone, said out loud here.
- **The runbook chapter** gains an unnumbered section after step 5, before "What to file from what
  you saw", showing the dry run with the instant first and then the run. This reds
  `DeveloperGuideExpandPromotionsExamplesTest.shouldRunEveryStepInOrderWhenTheChapterIsRead`, whose
  `steps()` reduction is extended to distinguish `--rated-since`, so the expected sequence reads
  `graphCensus, expandPromotions --dry-run, expandPromotions, graphCensus,
  expandPromotions --dry-run --rated-since, expandPromotions --rated-since`. Both new commands also
  go through `ExpandCli.parse` in `shouldParseEveryExampleWhenTheGuideShowsTheTool`, which is what
  makes the flag in the document and the flag in the parser one thing.
- **ADR 65** gains a dated amendment (2026-09-11, #307): the second reader, the rename, and why
  data minimisation still holds.
- **ADR 66** gains a dated amendment (2026-09-11, #307): the flag, what `considered` means under it,
  and the header clause.

Both ADRs are append-only. No commit hash enters `docs/adr`, no `.superpowers/` path enters any
committed file, and no figure from the owner's graph is quoted anywhere.

## What is not this issue

Recording which promotions have been expanded; the known file; the eleventh reading; any change to
`evaluate`'s behaviour, its report or its flag; `CensusReport`; retry policy; and the
`--max-new-edges` default.

## Verification that is not a unit test, said out loud

- The **move** (`RatingAge` to `domain`) changes no behaviour, so it has no red of its own. It is
  verified by the moved `RatingAgeTest` still passing at its new package, by the two `domain`
  ArchUnit rules, and by the full gate.
- **`ExpansionIsSafeToPasteTest`'s since-form case is a pin, not a control.** Adding a dry run with
  `--rated-since` to that class asserts the block still carries no label, note or qid. It passes the
  moment it compiles and **cannot be seen red on this change**, because the clause it exercises can
  only emit an `Instant` and an `int`. It is worth having as a regression pin and it is not
  evidence; that is stated in the plan rather than left to look like a control.
- **The `CLAUDE.md` clause and the two prose repairs in the developer guide** are verified by
  `./gradlew check` (which runs `AdrIndexTest`, `AdrCitationsTest`, `DocumentationLinksTest` and
  `javadoc -Werror`) and by reading, not by an assertion.

## Invented identifiers

`Q0900703`, `Q0900705`, `Q0900706` — none appears anywhere in `src/test` today (`Q0900701`,
`Q0900702`, `Q0900704` and `Q0900790` do, in this tool's own tests). All carry ADR 58's leading
zero, so `StandInQidsDenoteNothingTest` needs no allowlist entry for any of them.
