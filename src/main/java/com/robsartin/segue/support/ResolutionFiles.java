package com.robsartin.segue.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The mapping and review files, read and appended.
 *
 * <p>{@code name,kind,status,qid,label,confidence,reason} is the seven-column shape.
 *
 * <p><b>None of these files is in this repository, and none of them may be.</b> A list of who
 * someone listens to, reads and watches is exactly the personal data this project treats as the
 * owner's, and the protection is the filesystem rather than repository visibility — this repository
 * is public. The tools are committed; their input and their output are not, {@code *.csv} is
 * gitignored alongside {@code *.db}, and every name in a test, a fixture, a document or a commit
 * message in this project is invented.
 *
 * <p>The output files are also the seed tool's resume ledger. There is no third file recording
 * progress, because a progress file that can disagree with the results is a bug waiting to happen:
 * a name is done when an answer for it has been written down. Since #342 the owner-claim tool
 * appends here too, under {@link Outcome#MINTED}, and {@link #alreadyResolved} reads one of its
 * rows as resolved like any other — so a second batch mint over the same review file mints nothing
 * twice.
 */
public final class ResolutionFiles {

  private static final String OUTPUT_HEADER = "name,kind,status,qid,label,confidence,reason";

  private ResolutionFiles() {}

  /** Rows already written to an output file. */
  public static List<ResolutionRow> readRows(Path path) {
    List<List<String>> lines = CsvFile.read(path);
    List<ResolutionRow> rows = new ArrayList<>();
    for (List<String> fields : lines) {
      if (fields.size() < 7 || fields.get(0).equals("name")) {
        continue;
      }
      rows.add(
          new ResolutionRow(
              fields.get(0),
              fields.get(1),
              fields.get(2),
              fields.get(3).isEmpty() ? null : fields.get(3),
              fields.get(4).isEmpty() ? null : fields.get(4),
              Outcome.valueOf(fields.get(5).toUpperCase(Locale.ROOT)),
              fields.get(6)));
    }
    return List.copyOf(rows);
  }

  /**
   * The folded keys any of these files already carries an answer for.
   *
   * <p>Folded, not literal, so a re-run does not resolve a name again just because the first run
   * happened to write it under a different spelling of the same act.
   */
  public static Set<String> alreadyResolved(Collection<Path> paths) {
    Objects.requireNonNull(paths, "paths");
    Set<String> done = new LinkedHashSet<>();
    for (Path path : paths) {
      for (ResolutionRow row : readRows(path)) {
        done.add(NameFold.fold(row.name()));
      }
    }
    return Set.copyOf(done);
  }

  /** Append rows, writing the header if the file is new. */
  public static void append(Path path, List<ResolutionRow> rows) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(rows, "rows");
    if (rows.isEmpty()) {
      return;
    }
    StringBuilder out = new StringBuilder();
    if (!Files.exists(path)) {
      out.append(OUTPUT_HEADER).append(System.lineSeparator());
    }
    for (ResolutionRow row : rows) {
      out.append(
              String.join(
                  ",",
                  CsvFile.quote(row.name()),
                  CsvFile.quote(row.kind()),
                  CsvFile.quote(row.status()),
                  CsvFile.quote(row.qid()),
                  CsvFile.quote(row.label()),
                  CsvFile.quote(row.confidence().name()),
                  CsvFile.quote(row.reason())))
          .append(System.lineSeparator());
    }
    try {
      Files.writeString(
          path, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + path, e);
    }
  }
}
