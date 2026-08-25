package com.robsartin.segue.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CorrelationIdTest {

  @AfterEach
  void clear() {
    CorrelationId.clear();
  }

  @Test
  @DisplayName("begin mints an id and puts it in MDC")
  void beginPutsIdInMdc() {
    String id = CorrelationId.begin();

    assertThat(id).isNotBlank();
    assertThat(MDC.get(CorrelationId.KEY)).isEqualTo(id);
    assertThat(CorrelationId.current()).isEqualTo(id);
  }

  @Test
  @DisplayName("two requests get different ids")
  void idsAreDistinct() {
    String first = CorrelationId.begin();
    CorrelationId.clear();
    String second = CorrelationId.begin();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  @DisplayName("ids sort in the order they were minted")
  void idsSortChronologically() {
    // Time-ordered so a log tail reads chronologically. This is why UUIDv7 and not v4.
    String first = CorrelationId.begin();
    CorrelationId.clear();
    try {
      Thread.sleep(2);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    String second = CorrelationId.begin();

    assertThat(first).isLessThan(second);
  }

  @Test
  @DisplayName("clear removes it, and current is empty outside a request")
  void clearRemovesIt() {
    CorrelationId.begin();
    CorrelationId.clear();

    assertThat(MDC.get(CorrelationId.KEY)).isNull();
    assertThat(CorrelationId.current()).isEmpty();
  }
}
