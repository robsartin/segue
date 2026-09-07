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

  @Test
  @DisplayName("a refusal carries the entity and the reason, and no sentence for either caller")
  void shouldCarryTheReasonWhenAnExpansionIsRefused() {
    ExpansionOutcome.Refused refused =
        new ExpansionOutcome.Refused(SEED, ExpansionOutcome.Reason.LOCAL_ENTITY);

    assertThat(refused.qid()).isEqualTo(SEED);
    assertThat(refused.reason()).isEqualTo(ExpansionOutcome.Reason.LOCAL_ENTITY);
  }
}
