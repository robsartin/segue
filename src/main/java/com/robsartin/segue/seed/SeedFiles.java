package com.robsartin.segue.seed;

import com.robsartin.segue.support.CsvFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The input list this tool reads.
 *
 * <p><b>None of it is in this repository, and none of it may be.</b> A list of who someone listens
 * to, reads and watches is exactly the personal data ADR 33 governs, and issue #37 settled that the
 * protection is the filesystem rather than repository visibility — this repository is public. The
 * tool is committed; its input is not, {@code *.csv} is gitignored alongside {@code *.db}, and
 * every name in a test, a fixture, a document or a commit message in this project is invented.
 *
 * <p>The output half — the mapping and review files, reading, appending and the resume ledger —
 * moved to {@link com.robsartin.segue.support.ResolutionFiles} in #342, so the owner-claim tool can
 * read it too.
 */
public final class SeedFiles {

  private static final String INPUT_HEADER = "name,kind,status";

  private SeedFiles() {}

  /** The input list. */
  public static List<SeedRow> readList(Path path) {
    List<List<String>> lines = CsvFile.read(path);
    if (lines.isEmpty() || !String.join(",", lines.get(0)).equalsIgnoreCase(INPUT_HEADER)) {
      throw new IllegalArgumentException(path + " does not start with the header " + INPUT_HEADER);
    }
    List<SeedRow> rows = new ArrayList<>();
    for (List<String> fields : lines.subList(1, lines.size())) {
      if (fields.size() < 3) {
        throw new IllegalArgumentException(path + " has a row with fewer than three fields");
      }
      rows.add(new SeedRow(fields.get(0), fields.get(1), fields.get(2)));
    }
    return List.copyOf(rows);
  }
}
