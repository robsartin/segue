package com.robsartin.segue.census;

import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.sqlite.SqliteAffinityStore;
import com.robsartin.segue.sqlite.SqliteAssertionLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run, over a real database rather than the two fakes.
 *
 * <p>The fixture log and eight invented ratings are written into one SQLite file under a
 * {@code @TempDir} through the same two ports {@code CensusCli} opens — one {@code AssertionLog}
 * and one {@code AffinityStore} over one file, which is the only part of this tool the section
 * tests never exercise. Nothing here is the owner's: every id and score is {@code InventedCensus}'s
 * (ADR 40, issue #37), and the file is gone with the directory.
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
      Census census =
          new CensusRun(log, ratings).run(emitted::add, Optional.empty(), Optional.empty());

      assertThat(census.nodes().total())
          .as("the fixture's thirteen nodes, read back off the database rather than a fake")
          .isEqualTo(13);
      assertThat(census.taste().total())
          .as("the eight invented ratings, read back off the affinity table rather than a fake")
          .isEqualTo(8);
      assertThat(emitted).isEqualTo(CensusReport.lines(census));
    }
  }

  @Test
  @DisplayName("a --known file is read here, and the block gains the section that counts it")
  void shouldCountTheKnownListWhenAFileIsNamed() throws Exception {
    Path database = home.resolve("known.db");
    Path file =
        Files.writeString(
            home.resolve("known.csv"), InventedCensus.WREN + "\n" + InventedCensus.HOLLOW + "\n");
    try (AssertionLog log = new SqliteAssertionLog(database);
        AffinityStore ratings = new SqliteAffinityStore(database)) {
      for (LoggedAssertion assertion : InventedCensus.log()) {
        log.append(assertion);
      }

      List<String> emitted = new ArrayList<>();
      Census census =
          new CensusRun(log, ratings).run(emitted::add, Optional.of(file), Optional.empty());

      assertThat(census.knownList()).isPresent();
      assertThat(census.knownList().orElseThrow().fromFile().named())
          .as("the two ids the file names, read through KnownListInput rather than a Path")
          .isEqualTo(2);
      assertThat(emitted).anyMatch(line -> line.startsWith("known list — "));
    }
  }

  @Test
  @DisplayName("no --known file leaves the census carrying none and the block without the section")
  void shouldPrintNoKnownListSectionWhenNoFileIsNamed() {
    Path database = home.resolve("unknown.db");
    try (AssertionLog log = new SqliteAssertionLog(database);
        AffinityStore ratings = new SqliteAffinityStore(database)) {
      for (LoggedAssertion assertion : InventedCensus.log()) {
        log.append(assertion);
      }

      List<String> emitted = new ArrayList<>();
      Census census =
          new CensusRun(log, ratings).run(emitted::add, Optional.empty(), Optional.empty());

      assertThat(census.knownList()).isEmpty();
      assertThat(emitted).noneMatch(line -> line.startsWith("known list"));
    }
  }

  @Test
  @DisplayName("--isolated writes the file, and a second run overwrites it rather than appending")
  void shouldWriteTheIsolatedFileWhenBothFlagsAreGiven() throws Exception {
    Path database = home.resolve("isolated.db");
    Path file =
        Files.writeString(
            home.resolve("known.csv"), InventedCensus.WREN + "\n" + InventedCensus.HOLLOW + "\n");
    Path out = home.resolve("isolated.txt");
    try (AssertionLog log = new SqliteAssertionLog(database);
        AffinityStore ratings = new SqliteAffinityStore(database)) {
      for (LoggedAssertion assertion : InventedCensus.log()) {
        log.append(assertion);
      }

      List<String> emitted = new ArrayList<>();
      new CensusRun(log, ratings).run(emitted::add, Optional.of(file), Optional.of(out));

      assertThat(out).exists();
      List<String> firstRun = Files.readAllLines(out);
      assertThat(firstRun.get(0)).startsWith("# segue known-list acts the graph cannot place");

      List<String> emittedAgain = new ArrayList<>();
      new CensusRun(log, ratings).run(emittedAgain::add, Optional.of(file), Optional.of(out));

      List<String> secondRun = Files.readAllLines(out);
      assertThat(secondRun)
          .as("a second run overwrites rather than appends, exactly as NamesFile overwrites")
          .filteredOn(line -> line.startsWith("# segue known-list acts the graph cannot place"))
          .hasSize(1);
    }
  }
}
