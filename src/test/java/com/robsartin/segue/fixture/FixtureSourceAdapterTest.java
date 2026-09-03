package com.robsartin.segue.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.SourceAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The SPI's second implementation, which is what makes it a seam rather than a formality. */
class FixtureSourceAdapterTest {

  private final SourceAdapter adapter = new FixtureSourceAdapter();

  @Test
  @DisplayName("it expands a seed to the claims the fixture makes about it")
  void expandsSeed() {
    ExpandResult result =
        adapter.expand(
            new NodeRecord(Fixture.CAVE, NodeKind.PERSON, "Nick Cave"), ExpandContext.defaults());

    assertThat(result.assertions()).isNotEmpty();
    assertThat(result.assertions())
        .allSatisfy(
            c ->
                assertThat(c.fromQid().equals(Fixture.CAVE) || c.toQid().equals(Fixture.CAVE))
                    .isTrue());
    assertThat(result.sourceUnavailable()).isFalse();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName("it honours maxNewEdges rather than returning everything, and reports truncation")
  void honoursBound() {
    ExpandResult result =
        adapter.expand(
            new NodeRecord(Fixture.CAVE, NodeKind.PERSON, "Nick Cave"), new ExpandContext(2));

    assertThat(result.assertions()).hasSize(2);
    assertThat(result.truncated()).isTrue();
  }

  @Test
  @DisplayName("an unknown seed yields nothing, and is not an error")
  void unknownSeedIsEmpty() {
    ExpandResult result =
        adapter.expand(
            new NodeRecord("Q0999999", NodeKind.PERSON, "Nobody"), ExpandContext.defaults());

    assertThat(result.assertions()).isEmpty();
    assertThat(result.sourceUnavailable()).isFalse();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @DisplayName("it declares what it supports and identifies itself")
  void declaresItself() {
    assertThat(adapter.id()).isEqualTo("fixture");
    assertThat(adapter.supports(NodeKind.PERSON)).isTrue();
  }
}
