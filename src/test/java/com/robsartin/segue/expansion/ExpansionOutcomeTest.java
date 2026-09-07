package com.robsartin.segue.expansion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two derived flags and the defensive copies, over invented ids. Nothing here comes from
 * anybody's graph (ADR 33, issue #37), and every id carries ADR 58's leading zero.
 */
class ExpansionOutcomeTest {

  private static final String SEED = "Q0900601";

  private static ExpansionOutcome.Expanded expanded(
      List<String> unavailable, List<String> truncating, boolean boundCut) {
    return new ExpansionOutcome.Expanded(
        SEED, 0, 0, 0, 10, unavailable, truncating, boundCut, List.of(), Map.of());
  }

  @Test
  @DisplayName("a source that could not be reached makes the result say a source was unavailable")
  void shouldReportSourceUnavailableWhenASourceIsNamedUnreachable() {
    assertThat(expanded(List.of("musicbrainz"), List.of(), false).sourceUnavailable()).isTrue();
    assertThat(expanded(List.of(), List.of(), false).sourceUnavailable()).isFalse();
  }

  @Test
  @DisplayName("a source that cut its own result makes the result say it was truncated")
  void shouldReportTruncatedWhenASourceIsNamedTruncating() {
    assertThat(expanded(List.of(), List.of("wikidata"), false).truncated()).isTrue();
  }

  @Test
  @DisplayName("the shared budget cutting the concatenation truncates the result, naming nobody")
  void shouldReportTruncatedWhenOnlyTheSharedBudgetCutTheConcatenation() {
    ExpansionOutcome.Expanded outcome = expanded(List.of(), List.of(), true);

    assertThat(outcome.truncated())
        .as("ADR 56: the bound applied to the concatenation is a truncation no adapter made")
        .isTrue();
    assertThat(outcome.truncatingSources()).isEmpty();
  }

  @Test
  @DisplayName("nothing is truncated or unavailable when every source answered in full")
  void shouldReportNeitherFlagWhenEverySourceAnsweredInFull() {
    ExpansionOutcome.Expanded outcome = expanded(List.of(), List.of(), false);

    assertThat(outcome.truncated()).isFalse();
    assertThat(outcome.sourceUnavailable()).isFalse();
  }

  @Test
  @DisplayName("the lists and the map are copied, so a caller's later edit cannot reach the result")
  void shouldCopyEveryCollectionWhenTheOutcomeIsBuilt() {
    List<String> mutable = new ArrayList<>(List.of("wikidata"));
    Map<String, Integer> counts = new LinkedHashMap<>(Map.of("wikidata", 3));

    ExpansionOutcome.Expanded outcome =
        new ExpansionOutcome.Expanded(
            SEED, 0, 0, 0, 10, List.of(), mutable, false, List.of(), counts);
    mutable.add("musicbrainz");
    counts.put("musicbrainz", 9);

    assertThat(outcome.truncatingSources()).containsExactly("wikidata");
    assertThat(outcome.edgesBySource()).containsExactly(Map.entry("wikidata", 3));
    assertThatThrownBy(() -> outcome.truncatingSources().add("jena"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * <b>Five entries, and the number is the guard</b> (#284, fix round 1). Two entries let a {@code
   * Map.copyOf} regression pass roughly one run in five: {@code Map.copyOf} salts its iteration per
   * JVM, so with two keys it reproduces insertion order often enough that a single green run proves
   * nothing. Measured on this JDK, five keys reproduced insertion order 0 times in 100 fresh JVMs,
   * and the reverted copy was watched red three times running. The extra source names are invented
   * — {@code src/main} wires two adapters — because what is being pinned is the copy, and a tally
   * with two keys cannot fail a copy that reorders.
   */
  @Test
  @DisplayName("the tally keeps the order the sources were added in, which is adapter order")
  void shouldKeepTheSourceOrderWhenTheTallyIsCopied() {
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("wikidata", 2);
    counts.put("musicbrainz", 1);
    counts.put("discogs", 5);
    counts.put("openlibrary", 8);
    counts.put("tmdb", 13);

    ExpansionOutcome.Expanded outcome =
        new ExpansionOutcome.Expanded(
            SEED, 0, 29, 0, 10, List.of(), List.of(), false, List.of(), counts);

    assertThat(outcome.edgesBySource())
        .as(
            "insertion order is adapter order, which is the order a report prints — Map.copyOf's"
                + " own order is unspecified and salted per JVM, so it cannot be used here")
        .containsExactly(
            Map.entry("wikidata", 2),
            Map.entry("musicbrainz", 1),
            Map.entry("discogs", 5),
            Map.entry("openlibrary", 8),
            Map.entry("tmdb", 13));
  }

  @Test
  @DisplayName("a refusal carries the entity and the reason, and no sentence for either caller")
  void shouldCarryTheReasonWhenAnExpansionIsRefused() {
    ExpansionOutcome.Refused refused =
        new ExpansionOutcome.Refused(SEED, ExpansionOutcome.Reason.LOCAL_ENTITY);

    assertThat(refused.qid()).isEqualTo(SEED);
    assertThat(refused.reason()).isEqualTo(ExpansionOutcome.Reason.LOCAL_ENTITY);
  }
}
