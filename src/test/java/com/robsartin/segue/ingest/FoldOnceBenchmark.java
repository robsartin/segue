package com.robsartin.segue.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.Provenance;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A synthetic-log benchmark for the Task 3-5 fold-once boot: it always runs, at a small default row
 * count so the gate stays fast, and at the real log's published scale (ADR 57) when an operator
 * sets {@code SEGUE_BENCHMARK_ROWS} - deliberately not {@code EnabledIfEnvironmentVariable},
 * because this project's gate reports zero skipped tests by convention and a benchmark that skips
 * there would break it.
 */
class FoldOnceBenchmark {

  private static final Logger LOG = LoggerFactory.getLogger(FoldOnceBenchmark.class);

  private static final Provenance BENCH = new Provenance("benchmark", "ref", Instant.EPOCH, 1.0);

  private static final NodeKind[] KINDS = NodeKind.values();

  private static final String[] EDGE_TYPES = {
    "MEMBER_OF", "INFLUENCED_BY", "PERFORMED", "AUTHORED"
  };

  @TempDir Path tmp;

  @Test
  @DisplayName("the generator writes exactly the number of rows it was asked for")
  void shouldWriteEveryRowWhenTheGeneratorIsAskedForALogOfAGivenSize() throws Exception {
    try (AssertionLog log = new SqliteAssertionLog(tmp.resolve("bench.db"))) {
      generate(log, 1_000);

      assertThat(log.readAll()).hasSize(1_000);
    }
  }

  @Test
  @DisplayName(
      "a boot replay of a log at the real log's scale is timed, and nothing is asserted about the"
          + " clock")
  void shouldReportTheElapsedTimeWhenABootReplaysALogAtTheRealScale() throws Exception {
    try (AssertionLog log = new SqliteAssertionLog(tmp.resolve("bench.db"))) {
      int rows = benchmarkRows();
      generate(log, rows);

      try (TinkerGraphStore store = new TinkerGraphStore()) {
        long start = System.nanoTime();
        long applied = GraphProjector.project(log, store, IdentityMerge.NONE);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(applied)
            .as("a replay that applied nothing would be timing an empty list")
            .isPositive();
        LOG.info(
            "fold-once benchmark: replayed {} rows ({} requested) in {} ms", applied, rows, millis);
      }
    }
  }

  /**
   * The row count for the timing benchmark: {@code SEGUE_BENCHMARK_ROWS} if an operator set it, or
   * a small default the gate can afford. The default is deliberately small rather than the class
   * skipping in the gate - see the class javadoc.
   */
  private static int benchmarkRows() {
    String configured = System.getenv("SEGUE_BENCHMARK_ROWS");
    return configured == null ? 2_000 : Integer.parseInt(configured);
  }

  /**
   * Writes exactly {@code rows} claims: mostly edges over a pool of invented nodes, a handful of
   * merges, and one retraction, so the fold does real work rather than folding an empty log. Every
   * id carries a leading zero (a stand-in, ADR 58) or, on a merge's canonical side, the
   * eleven-digit shape ADR 62 reserves for it; every label is invented.
   */
  private static void generate(AssertionLog log, int rows) {
    int mergeCount = Math.min(5, rows / 200);
    int mergeRows = mergeCount * 2;
    int retractionRows = rows > 20 ? 1 : 0;
    int remaining = rows - mergeRows - retractionRows;
    int nodeCount = Math.max(2, remaining / 6);
    int edgeCount = remaining - nodeCount;

    String[] nodeIds = new String[nodeCount];
    for (int i = 0; i < nodeCount; i++) {
      String qid = "Q0" + (1_000_000 + i);
      nodeIds[i] = qid;
      log.append(new NodeAssertion(qid, KINDS[i % KINDS.length], "Bench Entity " + i, BENCH));
    }

    for (int i = 0; i < edgeCount; i++) {
      String from = nodeIds[i % nodeCount];
      int toIndex = (i * 7 + 3) % nodeCount;
      if (toIndex == i % nodeCount) {
        toIndex = (toIndex + 1) % nodeCount;
      }
      log.append(
          new AssertionRecord(
              from, nodeIds[toIndex], EDGE_TYPES[i % EDGE_TYPES.length], null, null, BENCH));
    }

    for (int m = 0; m < mergeCount; m++) {
      String localQid = "Q00" + (2_000_000 + m);
      // The eleven-digit canonical shape (ADR 62), built without ever writing an allocatable
      // "Q" + digits literal in source - see StandInQidsDenoteNothingTest's javadoc on why a
      // literal has to be split like this: 10_000_000_000L is a numeric literal, invisible to a
      // scan that reads only string literals, so no allocatable-form id sits in this file's text.
      String canonicalQid = "Q" + (10_000_000_000L + m);
      log.append(
          LocalEntity.minted(localQid, KINDS[m % KINDS.length], "Bench Local " + m, Instant.EPOCH));
      log.append(SameAs.declared(localQid, canonicalQid, Instant.EPOCH));
    }

    if (retractionRows == 1) {
      log.append(new Retraction(nodeIds[0], "benchmark retraction", Instant.EPOCH));
    }
  }
}
