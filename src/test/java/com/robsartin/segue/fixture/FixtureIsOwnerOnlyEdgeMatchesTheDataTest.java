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
 * asserting the same triple (#217). #176 itself began with an entry commented owner-only that a
 * real source also asserted, and {@code isOwnerOnlyEdge} — which only ever looks at the pair —
 * would have returned {@code true} for that entry too.
 *
 * <p>This test derives the owner-only set from {@link Fixture#assertions()} directly: a triple
 * (from, type, to) with exactly one assertion, made by the owner. It then checks {@code
 * isOwnerOnlyEdge} against that derivation instead of trusting the name. Deliberately independent
 * of {@code GraphStore} and both engines — this is a property of the fixture's raw data, not of
 * either engine's projection of it, and {@code GraphStoreContract}'s {@code
 * shouldReturnTheOwnerOnlyEdgeWhenTheCorroborationFloorIsZero} stays the place that pins the
 * engines' behaviour.
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

    assertThat(derivedOwnerOnly)
        .as("the fixture must hold at least one triple asserted only by the owner")
        .isNotEmpty();
    assertThat(derivedOwnerOnly)
        .as("isOwnerOnlyEdge must accept exactly the triples the data says are owner-only")
        .isEqualTo(acceptedByPredicate);
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
