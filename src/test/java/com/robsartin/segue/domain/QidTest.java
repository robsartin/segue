package com.robsartin.segue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where {@link Qid}'s allocatable grammar ends, which is a fact about Wikibase rather than a taste
 * of this project's (ADR 58).
 *
 * <p>{@code looksLikeAQid} and {@code check} are exercised wherever a domain record is built, and
 * the first-digit half of the grammar is pinned by every leading-zero id in the suite. What has no
 * other home is the upper end: {@code Q[1-9]\d{0,9}} admits at most ten digits, every id this
 * repository names is orders of magnitude below that, and ADR 62 reserves the shape immediately
 * above it — so the bound going missing would be invisible everywhere else.
 */
class QidTest {

  @Test
  @DisplayName("should allocate a ten-digit id, the longest Wikibase's item-id grammar admits")
  void shouldCallAnIdAllocatableWhenTheGrammarStillAdmitsItsLength() {
    assertThat(Qid.isAllocatable("Q1000000000")).isTrue();
  }

  @Test
  @DisplayName("should refuse an eleven-digit id, which Wikibase's item-id grammar cannot express")
  void shouldCallAnIdUnallocatableWhenItIsLongerThanTheGrammarAdmits() {
    assertThat(Qid.isAllocatable("Q10000000000")).isFalse();
  }
}
