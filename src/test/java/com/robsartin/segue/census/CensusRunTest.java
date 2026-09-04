package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run, over a real database rather than the two fakes.
 *
 * <p>The fixture log and eight invented ratings are written into one SQLite file under a
 * {@code @TempDir} through the same two ports {@code CensusCli} opens — one {@code AssertionLog}
 * and one {@code AffinityStore} over one file, which is the only part of this tool the section
 * tests never exercise. Nothing here is the owner's: every id and every score is {@code
 * InventedCensus}'s (ADR 40, issue #37), and the file is gone with the directory.
 *
 * <p>What it asserts is that the run emits {@link CensusReport#lines} for the census it returns, in
 * order and entire — not that the lines say any particular thing, which {@code CensusReportTest}
 * pins against text.
 */
class CensusRunTest {

  private static final Instant RATED_AT = Instant.parse("2026-02-02T00:00:00Z");

  private static final Map<String, Integer> RATINGS =
      Map.of(
          InventedCensus.WREN, 5,
          InventedCensus.SETTLED, 5,
          InventedCensus.HOLLOW, 4,
          InventedCensus.PRIZE, 4,
          InventedCensus.LEDGER, 3,
          InventedCensus.DOUBLE, 2,
          InventedCensus.NEIGHBOUR, 2,
          InventedCensus.GONE, 1);

  @TempDir private Path home;

  @Test
  @DisplayName("the run emits every line of the report for the census it returns")
  void shouldEmitTheWholeReportWhenTheDatabaseIsCounted() {
    Path database = home.resolve("census.db");
    try (AssertionLog log = new SqliteAssertionLog(database);
        AffinityStore ratings = new SqliteAffinityStore(database)) {
      for (LoggedAssertion assertion : InventedCensus.log()) {
        log.append(assertion);
      }
      RATINGS.forEach((qid, rating) -> ratings.updateRating(qid, rating, RATED_AT));

      List<String> emitted = new ArrayList<>();
      Census census = new CensusRun(log, ratings).run(emitted::add);

      assertThat(census.nodes().total())
          .as("the fixture's thirteen nodes, read back off the database rather than a fake")
          .isEqualTo(13);
      assertThat(census.taste().total())
          .as("the eight invented ratings, read back off the affinity table rather than a fake")
          .isEqualTo(8);
      assertThat(emitted).isEqualTo(CensusReport.lines(census));
    }
  }
}
