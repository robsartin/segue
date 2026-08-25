package com.robsartin.segue.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.SourceAdapter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The SPI's second implementation, which is what makes it a seam rather than a formality. */
class FixtureSourceAdapterTest {

  private final SourceAdapter adapter = new FixtureSourceAdapter();

  @Test
  @DisplayName("it expands a seed to the claims the fixture makes about it")
  void expandsSeed() {
    List<AssertionRecord> claims =
        adapter.expand(
            new NodeRecord(Fixture.CAVE, NodeKind.PERSON, "Nick Cave"), ExpandContext.defaults());

    assertThat(claims).isNotEmpty();
    assertThat(claims)
        .allSatisfy(
            c ->
                assertThat(c.fromQid().equals(Fixture.CAVE) || c.toQid().equals(Fixture.CAVE))
                    .isTrue());
  }

  @Test
  @DisplayName("it honours maxNewEdges rather than returning everything")
  void honoursBound() {
    List<AssertionRecord> claims =
        adapter.expand(
            new NodeRecord(Fixture.CAVE, NodeKind.PERSON, "Nick Cave"), new ExpandContext(2));

    assertThat(claims).hasSize(2);
  }

  @Test
  @DisplayName("an unknown seed yields nothing, and is not an error")
  void unknownSeedIsEmpty() {
    assertThat(
            adapter.expand(
                new NodeRecord("Q999999", NodeKind.PERSON, "Nobody"), ExpandContext.defaults()))
        .isEmpty();
  }

  @Test
  @DisplayName("it declares what it supports and identifies itself")
  void declaresItself() {
    assertThat(adapter.id()).isEqualTo("fixture");
    assertThat(adapter.supports(NodeKind.PERSON)).isTrue();
  }
}
