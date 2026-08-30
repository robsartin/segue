package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The context wires the real stack, and the graph is rebuilt from the log at startup. */
@SpringBootTest
class SegueConfigurationTest {

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void seedDatabase(DynamicPropertyRegistry registry) {
    Path db = tempDir.resolve("boot.db");
    try (SqliteAssertionLog log = new SqliteAssertionLog(db)) {
      log.append(
          new NodeAssertion(
              "Q192668",
              NodeKind.PERSON,
              "Nick Cave",
              new Provenance("wikidata", "Q192668", Instant.parse("2026-08-24T09:00:00Z"), 1.0)));
    }
    registry.add("segue.database", db::toString);
  }

  @Autowired GraphStore graphStore;

  @Autowired SourceAdapters sourceAdapters;

  @Test
  @DisplayName("should reach both sources from the expand path when the context is built")
  void shouldReachBothSourcesFromTheExpandPathWhenTheContextIsBuilt() {
    // The order is asserted, not just the membership. SegueService bounds the concatenation of
    // what the adapters return rather than bounding each one, so the list's order decides
    // which source spends a tight budget — see CorroborationAcrossSourcesTest, which pins that
    // behaviour from both ends. Wikidata stays first because it was first.
    assertThat(sourceAdapters.all())
        .extracting(SourceAdapter::id)
        .containsExactly("wikidata", "musicbrainz");
  }

  @Test
  @DisplayName("a claim written before startup is in the graph after it")
  void replaysTheLogAtBoot() {
    assertThat(graphStore.node("Q192668")).isPresent();
    assertThat(graphStore.node("Q192668").orElseThrow().label()).isEqualTo("Nick Cave");
  }
}
