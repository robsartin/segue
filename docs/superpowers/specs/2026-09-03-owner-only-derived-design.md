# The owner-only edge is named, not verified — derive it from the data instead

Issue #217. Written 2026-09-03, against `217-ready` at `07d8e2f` (`main`).

## The defect, as it stands in the code

`Fixture.isOwnerOnlyEdge` (`src/test/java/com/robsartin/segue/fixture/Fixture.java:173-175`, added by
#176) is a name-based predicate:

```java
public static boolean isOwnerOnlyEdge(EdgeRecord edge) {
  return edge.fromQid().equals(LOCAL_NOVELIST) && edge.toQid().equals(LOCAL_NOVEL);
}
```

It answers "is this the `LOCAL_NOVELIST`→`LOCAL_NOVEL` pair", not "does this edge actually have the
property its name claims" — asserted exactly once, by the owner, with no real source ever having
asserted the same triple. Nothing in the fixture checks that the second question is also true of the
entry the predicate names. #176 itself began with an entry commented `owner-only` that a real source
also asserted; `isOwnerOnlyEdge`, read literally, would have returned `true` for that entry precisely
because it only ever looks at the pair, never at how many assertions back it or who made them.

**What currently stands between the fixture and that mistake is a proxy, one step removed.**
`GraphStoreContract.shouldReturnTheOwnerOnlyEdgeWhenTheCorroborationFloorIsZero`
(`src/test/java/com/robsartin/segue/port/GraphStoreContract.java:191-203`) asserts
`store.corroborated(1).noneMatch(Fixture::isOwnerOnlyEdge)`. If the fixture regressed — a second,
real-source assertion of the `LOCAL_NOVELIST`→`LOCAL_NOVEL` triple appeared — that engine-level test
would fail. But it fires through `GraphStore.corroborated`, so a failure there reads as an engine
defect (did `TinkerGraphStore`/`JenaGraphStore` collapse assertions or count corroboration wrong?)
rather than what it would actually be: the fixture no longer has the property its own predicate's
name promises. The two are entangled in one assertion, and whichever one is actually broken, the
failure message points at the engine.

## What the fix has to do

Add a fixture self-test, beside `FixtureQidsDenoteNothingTest`, that:

1. **Derives** the owner-only set directly from `Fixture.assertions()` — no `GraphStore`, no engine —
   as the triples `(fromQid, typeCode, toQid)` that are asserted **exactly once**, by an assertion
   whose `provenance().isOwner()` is `true`.
2. Asserts that derived set is **non-empty** (the fixture is supposed to have at least one; an empty
   derived set with a passing test would be a vacuous guard).
3. Asserts the derived set is **exactly** the set of triples `Fixture.isOwnerOnlyEdge` accepts —
   neither predicate having triples the other lacks.

This is a **fixture** self-test in the same sense `FixtureQidsDenoteNothingTest` is: it reads
`Fixture`'s own static data and nothing else. It does not open a `GraphStore`, so a future defect in
either engine's corroboration counting cannot make it pass or fail — that stays
`GraphStoreContract`'s and `TinkerGraphStoreContractTest`'s job, unchanged. A regression in the
fixture itself now fails at the fixture layer, naming the fixture; a regression in an engine's
corroboration logic still fails at the port-contract layer, naming the engine. The two failure modes
stop being one entangled assertion.

## The derivation, precisely

`AssertionRecord.edgeKey()` already returns `fromQid + " " + typeCode + " " + toQid`
(`src/main/java/com/robsartin/segue/domain/AssertionRecord.java:41-43`), and `EdgeRecord.key()`
(`src/main/java/com/robsartin/segue/domain/EdgeRecord.java:61-63`) returns the identical string —
same field order, same separator — so a triple key computed either way is directly comparable.

Group `Fixture.assertions()` by `AssertionRecord::edgeKey`. A group of size 1 whose sole member's
`provenance().isOwner()` is `true` is a derived owner-only triple; collect those keys into a set.

To compare against what `isOwnerOnlyEdge` accepts, build one representative `EdgeRecord` per distinct
triple (any one `AssertionRecord` in the group supplies `fromQid`/`toQid`/`typeCode`; the predicate
reads neither `validFrom`/`validTo` nor `sources`, so those fields can be `null`/`List.of()`), filter
by `Fixture::isOwnerOnlyEdge`, and collect `EdgeRecord::key`. Compare the two `Set<String>`s.

Today, over the fixture's fifteen assertions, exactly one triple is a size-1 group whose sole
assertion is `Provenance.owner(...)`: `owner(LOCAL_NOVELIST, "AUTHORED", LOCAL_NOVEL)`
(`Fixture.java:164`). The layered claim, `owner(CAVE, "AUTHORED", ASS_SAW_ANGEL)` alongside
`wikidata(CAVE, "AUTHORED", ASS_SAW_ANGEL, ...)`, groups to size 2 and is correctly excluded — it is
asserted twice, once by a real source, which is exactly the case #176 needed the predicate to *not*
accept and the reason `isOwnerOnlyEdge` cannot be satisfied by "any owner claim", only by one with no
other witness.

### A residual worth recording, not fixing here

`isOwnerOnlyEdge` tests `fromQid`/`toQid` only — it does not look at `typeCode` at all. The derived
set is keyed on the full triple. The two sets happen to agree today because the fixture has exactly
one edge type between `LOCAL_NOVELIST` and `LOCAL_NOVEL`. If a second edge type were ever added
between that same pair (e.g. a second owner claim `LOCAL_NOVELIST ILLUSTRATED LOCAL_NOVEL`, still
uncorroborated), the two predicates would diverge: the derived, triple-keyed set would gain a second
member while the name-based, pair-keyed `isOwnerOnlyEdge` would (correctly, by its own definition)
still accept both, since it never asked about type. That divergence is not a defect this issue asks
to close — it is exactly the kind of drift this new test exists to catch, and it would fail loudly
(non-empty-and-equal) rather than silently, the day it happened. No production change is warranted to
pre-empt a case the fixture does not yet have.

## Decision: `isOwnerOnlyEdge` stays name-based, and this test pins it

**Recommended: keep `isOwnerOnlyEdge` exactly as it is — a name-based predicate over `fromQid`/
`toQid` — and let this new test be the thing that keeps its name honest.**

The alternative is making `isOwnerOnlyEdge` itself derive its answer from `Fixture.assertions()` on
every call — group by `edgeKey`, check group size and ownership, then compare the pair. That would
make the predicate self-verifying with no separate test needed.

**Rejected.** Two consumers call `isOwnerOnlyEdge` directly against a `GraphStore`-returned
`EdgeRecord` (`GraphStoreContract` and `TinkerGraphStoreContractTest`'s
`assertCorroborationShape`/`shouldPlaceOwnerClaimsByCorroborationWhenEitherEngineAnswersTheRange`),
inside assertions like `assertThat(store.corroborated(1)).noneMatch(Fixture::isOwnerOnlyEdge)`. If
`isOwnerOnlyEdge` failed — because the fixture regressed, or because of a bug in the derivation logic
itself — every one of those call sites would report *"noneMatch/anyMatch failed"* against a predicate
whose own internals nobody can see from the assertion, on data that already went through one
`GraphStore` projection. Debugging would mean stepping into the predicate to find out whether the
*data*, the *derivation*, or the *engine* was at fault — exactly the "reads as an engine defect"
problem this issue exists to fix, just moved one level deeper rather than removed. A name-based
predicate keeps the contract test's failure message readable — "this specific pair, by name, showed
up somewhere the fixture says it shouldn't" — and pushes the "does the fixture actually have this
property" question to one test built to answer only that, with a message that names the fixture.

## The test

New file, beside `FixtureQidsDenoteNothingTest`:
`src/test/java/com/robsartin/segue/fixture/FixtureIsOwnerOnlyEdgeMatchesTheDataTest.java`.

```java
package com.robsartin.segue.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.EdgeRecord;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Fixture#isOwnerOnlyEdge} identifies its edge by NAME — the pair {@link
 * Fixture#LOCAL_NOVELIST} to {@link Fixture#LOCAL_NOVEL}. Nothing pinned that this triple actually
 * HAS the property the name claims: asserted exactly once, by the owner, with no real source ever
 * asserting the same triple (#217). #176 itself began with an entry commented owner-only that a real
 * source also asserted, and {@code isOwnerOnlyEdge} — which only ever looks at the pair — would have
 * returned {@code true} for that entry too.
 *
 * <p>This test derives the owner-only set from {@link Fixture#assertions()} directly: a triple
 * (from, type, to) with exactly one assertion, made by the owner. It then checks {@code
 * isOwnerOnlyEdge} against that derivation instead of trusting the name. Deliberately independent of
 * {@code GraphStore} and both engines — this is a property of the fixture's raw data, not of either
 * engine's projection of it, and {@code GraphStoreContract}'s {@code
 * shouldReturnTheOwnerOnlyEdgeWhenTheCorroborationFloorIsZero} stays the place that pins the engines'
 * behaviour.
 */
class FixtureIsOwnerOnlyEdgeMatchesTheDataTest {

  @Test
  @DisplayName(
      "should equal the derived owner-only set when isOwnerOnlyEdge is checked against the fixture's"
          + " own data")
  void shouldEqualDerivedOwnerOnlySetWhenIsOwnerOnlyEdgeIsCheckedAgainstTheData() {
    Map<String, List<AssertionRecord>> byTriple =
        Fixture.assertions().stream().collect(Collectors.groupingBy(AssertionRecord::edgeKey));

    Set<String> derivedOwnerOnly =
        byTriple.entrySet().stream()
            .filter(entry -> entry.getValue().size() == 1)
            .filter(entry -> entry.getValue().get(0).provenance().isOwner())
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());

    Set<String> acceptedByPredicate =
        byTriple.values().stream()
            .map(FixtureIsOwnerOnlyEdgeMatchesTheDataTest::representativeEdge)
            .filter(Fixture::isOwnerOnlyEdge)
            .map(EdgeRecord::key)
            .collect(Collectors.toSet());

    assertThat(derivedOwnerOnly).isNotEmpty();
    assertThat(derivedOwnerOnly).isEqualTo(acceptedByPredicate);
  }

  /**
   * {@code isOwnerOnlyEdge} reads only {@code fromQid}/{@code toQid}, so validity dates and sources
   * are irrelevant to it — a bare representative built from any one assertion in the triple's group
   * is enough to ask the predicate the question.
   */
  private static EdgeRecord representativeEdge(List<AssertionRecord> triple) {
    AssertionRecord any = triple.get(0);
    return new EdgeRecord(
        any.fromQid(), any.toQid(), any.typeCode(), any.validFrom(), any.validTo(), List.of());
  }
}
```

## The positive control

This is a guard, not a bug fix — there is no production code to make red-then-green over, and the
property under test is already true of the fixture today, so the test's first run against the
unmodified fixture is expected to **pass**. The proof this guard can actually fail is a positive
control, done as a temporary, unstaged edit and reverted before commit (per the standing rule: every
guard gets one):

1. Temporarily add one line to `Fixture.assertions()`, right after the real
   `owner(LOCAL_NOVELIST, "AUTHORED", LOCAL_NOVEL)` entry:

   ```java
   owner(LOCAL_NOVELIST, "AUTHORED", LOCAL_NOVEL),
   wikidata(LOCAL_NOVELIST, "AUTHORED", LOCAL_NOVEL, null, null, "S-owner-only-plant"));
   ```

   (adjusting the trailing `);` since this becomes the new last element of the `List.of(...)` call).
   This is exactly the shape of the defect #176 found: a real source now also asserts the triple the
   fixture's own comment and predicate call owner-only.

2. Run only the new test. It must go red **for the derivation, not the predicate**:
   `derivedOwnerOnly` loses its only member (the triple's group size is now 2), so it becomes empty
   and `assertThat(derivedOwnerOnly).isNotEmpty()` fails first — quote the actual failure. (If the
   equality assertion fired instead, that would mean the vacuity check itself is not wired correctly,
   and would need fixing before proceeding — not expected here, but worth confirming which assertion
   trips.)

3. Revert the planted line exactly, confirm the test file's target commit diff excludes any change to
   `Fixture.java`, and re-run to confirm green.

Note the ordering here is *pass → red (planted) → pass (reverted)*, not the usual *red → green* of a
behavioural change, because nothing is being implemented — the test exists to prove a fact about
already-correct data is checked, not to drive new production behaviour into existence. This is the
same shape `FixtureQidsDenoteNothingTest` and `StandInQidsDenoteNothingTest` already take in this
repository: a guard's "red" is demonstrated by a positive control, not by a pre-implementation
failure.

## What this does not touch

- No production code changes. `Fixture.isOwnerOnlyEdge`, `EdgeRecord`, `AssertionRecord`, and
  `Provenance` are all read-only inputs to the new test.
- No ADR amendment. ADR 59 already describes owner claims and their exclusion from corroboration;
  this issue is about a missing *test*, not a changed decision. Nothing here contradicts or narrows
  anything ADR 59 says.
- `GraphStoreContract` and `TinkerGraphStoreContractTest` are unchanged — they remain the tests that
  pin the engines' behaviour against the fixture; this issue adds the test that pins the fixture's
  own data against its own naming.
- `FixtureQidsDenoteNothingTest` is unchanged; the new class sits beside it as a sibling fixture
  self-test, not a modification of it.

## Rejected

- **Make `isOwnerOnlyEdge` derive its answer live, on every call.** Rejected above under "Decision":
  it would make every `GraphStoreContract`/`TinkerGraphStoreContractTest` assertion that calls it
  fail unreadably on a fixture regression, folding the derivation's own correctness into every
  consumer's failure message instead of isolating it in one test built to explain it.
- **Extend `FixtureQidsDenoteNothingTest` instead of adding a sibling.** That class asserts one thing
  about the shape of `Fixture`'s public `String` constants (Wikibase's item-id grammar); this test
  asserts a different thing about the shape of `Fixture`'s assertion list. Folding them together
  would give one class two unrelated oracles, the same reason the standin-qids design gave for not
  extending it there.
- **Assert the derived set by comparing full `EdgeRecord`s rather than triple-key strings.** Rejected
  for the same reason `TinkerGraphStoreContractTest.keys(...)` already compares by `EdgeRecord::key`
  rather than by list equality: two `EdgeRecord`s built from different-order `sources` lists are not
  `.equals()` even when they describe the same triple, and this test's representative edges are built
  with an intentionally empty `sources` list precisely because the predicate does not read it — key
  comparison sidesteps an equality question the test has no reason to ask.
