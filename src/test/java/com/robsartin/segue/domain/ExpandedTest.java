package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one rule that says whether an entity has been expanded, and the two ways a real run says so.
 *
 * <p>Both shapes are the adapters' own, confirmed against them rather than assumed: a forward claim
 * carries the Wikidata statement id, which begins with the subject's qid and a {@code $}; a
 * reverse-discovered edge carries {@code wdqs:<other>:<property>:<seed>}. The two controls at the
 * foot are what stop a looser reading of the second shape: the reverse pass also records each
 * discovered neighbour as a node claim whose reference is that neighbour's own bare qid, and a
 * suffix test would read every one of them as an expansion of itself.
 */
class ExpandedTest {

  private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

  private static final String SEED = "Q0901001";
  private static final String OTHER = "Q0901002";

  /** {@link #SEED} with its last digit removed — a prefix of it, and a different entity. */
  private static final String SHORTER = "Q090100";

  /**
   * A local entity's own shape — two leading zeros (ADR 59) — because {@link LocalEntity#minted}
   * and {@link SameAs#declared}'s local side refuse the plan's single-leading-zero family; see the
   * task report for the brief/code mismatch this corrects.
   */
  private static final String LOCAL = "Q00901003";

  /**
   * ADR 62's eleven-digit reserved shape, for the merge's canonical side — same reason as above.
   */
  private static final String CANONICAL = "Q10000901004";

  private static AssertionRecord edge(String from, String to, String sourceRef) {
    return new AssertionRecord(
        from, to, "INFLUENCED_BY", null, null, new Provenance("wikidata", sourceRef, WHEN, 1.0));
  }

  private static NodeAssertion node(String qid, String sourceRef) {
    return new NodeAssertion(
        qid, NodeKind.PERSON, "An Invented Name", new Provenance("wikidata", sourceRef, WHEN, 1.0));
  }

  @Test
  @DisplayName("a forward claim citing the entity as the statement's subject is an expansion")
  void shouldCoverTheSeedWhenAForwardStatementNamesItAsTheSubject() {
    Expanded expanded = Expanded.in(List.of(edge(SEED, OTHER, SEED + "$4f1a-invented")));

    assertThat(expanded.covers(SEED)).isTrue();
    assertThat(expanded.covers(OTHER)).isFalse();
  }

  @Test
  @DisplayName("a reverse-discovered edge whose reference ends in the entity is an expansion")
  void shouldCoverTheSeedWhenAReverseReferenceEndsWithIt() {
    Expanded expanded = Expanded.in(List.of(edge(OTHER, SEED, "wdqs:" + OTHER + ":P737:" + SEED)));

    assertThat(expanded.covers(SEED)).isTrue();
  }

  @Test
  @DisplayName("an entity that only appears as another expansion's neighbour is not expanded")
  void shouldNotCoverANeighbourWhenItOnlyAppearsInAnotherExpansionsRows() {
    // Exactly what one reverse pass writes: the edge, whose reference names the neighbour in the
    // middle, and the neighbour's own node claim, whose reference is its bare qid.
    Expanded expanded =
        Expanded.in(
            List.of(edge(OTHER, SEED, "wdqs:" + OTHER + ":P737:" + SEED), node(OTHER, OTHER)));

    assertThat(expanded.covers(OTHER))
        .as("the neighbour is the subject of the discovered triple, not the seed of the call")
        .isFalse();
    assertThat(expanded.covers(SEED)).isTrue();
  }

  /**
   * A real statement id, confirmed against live Wikidata for issue #311's review. For Nick Cave
   * (Q192668): {@code q192668$35463C9F-FBDC-4657-9DE0-55B1D9602067} — lowercase {@code q}, then
   * digits, then {@code $}, then an uppercase UUID. Invented here with a leading zero so it denotes
   * nothing (ADR 58); {@link #LOWERCASE_SEED} keeps that shape rather than {@link #SEED}'s
   * uppercase one, because the whole point is the case Wikidata actually sends.
   */
  private static final String LOWERCASE_SEED = "Q0900001";

  @Test
  @DisplayName(
      "a forward claim whose statement id carries Wikidata's real lowercase qid prefix is an"
          + " expansion")
  void shouldCoverTheSeedWhenAForwardStatementCarriesTheLowercaseQidWikidataActuallyUses() {
    Expanded expanded =
        Expanded.in(
            List.of(edge(LOWERCASE_SEED, OTHER, "q0900001$35463C9F-FBDC-4657-9DE0-55B1D9602067")));

    assertThat(expanded.covers(LOWERCASE_SEED))
        .as(
            "real Wikidata statement ids carry a lowercase qid prefix; requiring uppercase drops"
                + " every one of them and over-counts \"never expanded\"")
        .isTrue();
  }

  @Test
  @DisplayName(
      "a prefix of the seed is still not expanded when the forward reference uses the lowercase"
          + " prefix")
  void shouldNotCoverAPrefixOfTheSeedWhenTheForwardReferenceUsesTheLowercasePrefix() {
    Expanded expanded =
        Expanded.in(
            List.of(edge(LOWERCASE_SEED, OTHER, "q0900001$35463C9F-FBDC-4657-9DE0-55B1D9602067")));

    assertThat(expanded.covers("Q090000"))
        .as("Q090000 is a prefix of " + LOWERCASE_SEED + " and a different entity")
        .isFalse();
    assertThat(expanded.covers(LOWERCASE_SEED)).isTrue();
  }

  @Test
  @DisplayName("an entity whose id is a prefix of the seed's is not expanded by the seed's rows")
  void shouldNotCoverAPrefixOfTheSeedWhenOnlyTheLongerIdWasExpanded() {
    Expanded expanded =
        Expanded.in(
            List.of(
                edge(SEED, OTHER, SEED + "$4f1a-invented"),
                edge(OTHER, SEED, "wdqs:" + OTHER + ":P737:" + SEED)));

    assertThat(expanded.covers(SHORTER))
        .as(SHORTER + " is a prefix of " + SEED + " and a different entity")
        .isFalse();
    assertThat(expanded.covers(SEED)).isTrue();
  }

  @Test
  @DisplayName("rows carrying no source reference contribute no seed and throw nothing")
  void shouldCoverNothingWhenTheLogHoldsOnlyFirstPersonClaimsAndUnshapedReferences() {
    Expanded expanded =
        Expanded.in(
            List.of(
                LocalEntity.minted(LOCAL, NodeKind.WORK, "A Minted Thing", WHEN),
                OwnerEdge.claimed(LOCAL, SEED, "INFLUENCED_BY", WHEN),
                SameAs.declared(LOCAL, CANONICAL, WHEN),
                new Retraction("Q0901005", "an invented reason", WHEN),
                edge(SEED, OTHER, "P737:" + OTHER),
                edge(SEED, OTHER, "artist/invented#member of band:invented")));

    assertThat(expanded.seeds()).isEmpty();
  }

  @Test
  @DisplayName("a seed recorded on a merge's retired side covers the canonical id after the fold")
  void shouldCoverTheCanonicalIdWhenTheSeedWasRecordedOnTheRetiredSide() {
    Expanded expanded = Expanded.in(List.of(edge(LOCAL, OTHER, LOCAL + "$4f1a-invented")));

    assertThat(expanded.covers(CANONICAL))
        .as("the raw rows cite the id the owner has since retired")
        .isFalse();
    assertThat(
            expanded
                .onTheCanonicalSide(new Equivalences(Map.of(LOCAL, CANONICAL)))
                .covers(CANONICAL))
        .as("a population already on its canonical side must find the work that was really done")
        .isTrue();
  }
}
