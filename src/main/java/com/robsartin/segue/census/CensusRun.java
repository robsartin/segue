package com.robsartin.segue.census;

import com.robsartin.segue.domain.SecondHop;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.support.KnownListInput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * <p><b>There is no warning to say first</b>, unlike {@code ExportRun} and {@code RatingsRun}.
 * Those warn because what the operator does next is decide where to put a file of personal data.
 * The census block itself produces no file and no personal data; the header line says what the
 * output is, and that is the whole of it. {@code --isolated} is the one exception, written by
 * {@link #write}, and it names no path in the block for the same reason.
 *
 * <p><b>It reads and cannot write.</b> {@code theCensusOnlyReads} forbids this package the three
 * world-fact writes, both taste-layer writes and {@code IngestService}. {@code --isolated}'s file
 * is a plain-text write outside all three, on {@code ratings.NamesFile}'s own precedent.
 */
public final class CensusRun {

  private final AssertionLog log;
  private final AffinityStore ratings;

  public CensusRun(AssertionLog log, AffinityStore ratings) {
    this.log = Objects.requireNonNull(log, "log");
    this.ratings = Objects.requireNonNull(ratings, "ratings");
  }

  /**
   * Count the graph, emit the report, and — when named — write the isolated-acts file last.
   *
   * @param known the known-list file, or empty. <b>Read here rather than in the CLI</b>, so the one
   *     place a path becomes a basename and a list of ids is {@link KnownListInput} and a test can
   *     exercise it without a command line
   * @param isolated the file to write the isolated acts to, or empty. {@code parse} refuses it
   *     without {@code known}
   * @return the census that was printed, so a caller can assert on the numbers without parsing the
   *     text back
   */
  public Census run(Consumer<String> lines, Optional<Path> known, Optional<Path> isolated) {
    Objects.requireNonNull(lines, "lines");
    Objects.requireNonNull(known, "known");
    Objects.requireNonNull(isolated, "isolated");
    Census.Reading reading = Census.reading(log, ratings, known.map(KnownListInput::read));
    CensusReport.lines(reading.census()).forEach(lines);
    // After the report, so a run that could not produce one writes nothing — and an existing
    // file is overwritten, as NamesFile overwrites. CensusCli.parse has already refused
    // --isolated without --known, so the rule is present whenever the path is.
    isolated.ifPresent(file -> write(file, reading, lines));
    return reading.census();
  }

  /**
   * <b>The note is counts and never the path.</b> ADR 63's whole point is that nothing this tool
   * emits locates the owner's file; {@code RatingsRun} says "wrote &lt;path&gt;" because its own
   * block already warns about a file of personal data, and this block does not.
   */
  private void write(Path file, Census.Reading reading, Consumer<String> lines) {
    SecondHop rule =
        reading
            .isolation()
            .orElseThrow(() -> new IllegalStateException("--isolated without --known"));
    int unnamed;
    try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      unnamed = IsolatedFile.write(rule, reading.projection().nodes(), out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    lines.accept(
        "wrote "
            + rule.isolated().size()
            + " isolated act(s), "
            + unnamed
            + " of them named only by qid");
  }
}
