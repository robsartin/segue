package com.robsartin.segue.census;

import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.support.KnownListInput;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
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
   * @param known the known-list file, or empty. <b>Read here rather than in the CLI</b>, so the one
   *     place a path becomes a basename and a list of ids is {@link KnownListInput} and a test can
   *     exercise it without a command line
   * @return the census that was printed, so a caller can assert on the numbers without parsing the
   *     text back
   */
  public Census run(Consumer<String> lines, Optional<Path> known) {
    Objects.requireNonNull(lines, "lines");
    Objects.requireNonNull(known, "known");
    Census census = Census.of(log, ratings, known.map(KnownListInput::read));
    CensusReport.lines(census).forEach(lines);
    return census;
  }
}
