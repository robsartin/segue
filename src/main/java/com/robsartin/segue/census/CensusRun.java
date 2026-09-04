package com.robsartin.segue.census;

import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Count, then say so — and nothing between the two.
 *
 * <p>Lines go to a {@link Consumer} rather than to a logger of this class's own, so the whole
 * report is observable from a test and so this class has no logger to misuse — the discipline
 * {@code RatingsRun} and {@code SqliteAffinityStore} both keep.
 *
 * <p><b>There is no warning to say first</b>, which is the one way this differs from {@code
 * ExportRun} and {@code RatingsRun}. Those warn because what the operator does next is decide where
 * to put a file of personal data. This produces no file and no personal data; the header line says
 * what the output is, and that is the whole of it.
 *
 * <p><b>It reads and cannot write.</b> {@code ArchitectureTest.theCensusOnlyReads} forbids this
 * package the three world-fact writes, both taste-layer writes and {@code IngestService}.
 */
public final class CensusRun {

  private final AssertionLog log;
  private final AffinityStore ratings;

  public CensusRun(AssertionLog log, AffinityStore ratings) {
    this.log = Objects.requireNonNull(log, "log");
    this.ratings = Objects.requireNonNull(ratings, "ratings");
  }

  /**
   * Count the graph and emit the report.
   *
   * @return the census that was printed, so a caller can assert on the numbers without parsing the
   *     text back
   */
  public Census run(Consumer<String> lines) {
    Objects.requireNonNull(lines, "lines");
    Census census = Census.of(log, ratings);
    CensusReport.lines(census).forEach(lines);
    return census;
  }
}
