package com.robsartin.segue.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The wiring itself, because one word in it is the difference between a rating surviving a merge
 * and being gone.
 *
 * <p><b>Why this test exists.</b> {@code MergeCarriesEverythingTest} builds its own {@code
 * IngestService} and proves the mechanism; it says nothing about what the running application is
 * given. Substituting {@link IdentityMerge#NONE} in {@link SegueConfiguration} left all 934 tests
 * green while making every merge in the real app orphan the owner's rating - unrecoverably, since
 * affinity has no history table and no un-rate (ADR 39, ADR 46). Removing the convenience
 * constructor had only moved the failure from forgetting an argument to writing the wrong one.
 *
 * <p>So this drives the bean methods themselves rather than a hand-built graph. It is deliberately
 * not a Spring context test: the beans are plain methods on a plain object, and running them
 * directly is what keeps the assertion about the wiring rather than about the framework.
 */
class MergeWiringTest {

  private static final Instant NOW = Instant.parse("2026-08-31T09:00:00Z");
  private static final String MINTED = "Q00900042";
  private static final String CANONICAL = "Q10000000900";

  private final SegueConfiguration configuration = new SegueConfiguration();

  private AssertionLog log;
  private AffinityStore affinity;

  @BeforeEach
  void setUp() {
    log = SqliteAssertionLog.inMemory();
    affinity = SqliteAffinityStore.inMemory();
  }

  @AfterEach
  void tearDown() {
    affinity.close();
    log.close();
  }

  @Test
  @DisplayName("the application's own ingest service carries a rating through a merge")
  void shouldCarryARatingThroughAMergeWhenTheApplicationWiresItsOwnIngestService() {
    IdentityMerge merges = configuration.identityMerge(affinity);
    try (GraphStore graph = configuration.graphStore(log, merges)) {
      IngestService ingest = configuration.ingestService(log, graph, merges);
      ingest.record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
      affinity.put(new AffinityRecord(MINTED, 5, null, NOW));

      ingest.record(SameAs.declared(MINTED, CANONICAL, NOW));

      assertThat(affinity.find(CANONICAL))
          .as("IdentityMerge.NONE here would orphan every rating the owner ever merges")
          .isPresent();
    }
  }

  @Test
  @DisplayName("the application's boot replay repairs a merge nothing carried at the time")
  void shouldRepairAMergeNothingCarriedWhenTheApplicationBootReplaysTheLog() {
    IdentityMerge merges = configuration.identityMerge(affinity);
    try (GraphStore first = configuration.graphStore(log, merges)) {
      new IngestService(log, first, IdentityMerge.NONE)
          .record(LocalEntity.minted(MINTED, NodeKind.PERSON, "a minted person", NOW));
      affinity.put(new AffinityRecord(MINTED, 5, null, NOW));
      new IngestService(log, first, IdentityMerge.NONE)
          .record(SameAs.declared(MINTED, CANONICAL, NOW));
    }
    assertThat(affinity.find(CANONICAL)).as("the precondition: nothing carried it").isEmpty();

    try (GraphStore rebooted = configuration.graphStore(log, merges)) {
      assertThat(rebooted.node(CANONICAL)).isPresent();
    }

    assertThat(affinity.find(CANONICAL).orElseThrow().rating())
        .as("affinity is rebuilt by nothing, so boot is the only chance to repair a stranded carry")
        .isEqualTo(5);
  }
}
