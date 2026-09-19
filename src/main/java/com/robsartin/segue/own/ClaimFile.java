package com.robsartin.segue.own;

import com.robsartin.segue.domain.EdgeTypes;
import com.robsartin.segue.domain.Qid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The owner's claims file: the header {@code from,to,type}, then one edge per row.
 *
 * <p><b>In {@code own} because nothing else reads this shape.</b> {@code support.QidList} and
 * {@code support.ResolutionFiles} live where they do because two tools read one file; this one has
 * a single reader, and moving it would put a shape in {@code support} that nothing there justifies.
 *
 * <p><b>No quoting, and the parser is {@code String.split}.</b> Every field is a qid or an {@code
 * EdgeTypes} code, so there is nothing a comma could be inside - which is why this does not go
 * through {@code support.CsvFile}. Reading the file a line at a time is also what keeps the line
 * NUMBER, and every refusal below names it: a file the owner typed by hand is a file they have to
 * open again.
 *
 * <p><b>Every refusal is the whole file's.</b> A malformed row, an id that is not qid-shaped and a
 * code outside the vocabulary each throw here, before {@code OwnRun} has appended anything - there
 * is no edge-level retraction, so a wrong edge is undone only by retracting one of its endpoints,
 * which takes that entity's other edges with it (ADR 59).
 *
 * <p><b>Lines beginning {@code #} are comments</b>, so the owner can annotate a file that is
 * personal data. Blank lines are skipped too.
 *
 * <p><b>The file is personal data and never enters this repository</b> (ADR 40's rule). It lives
 * outside the working tree and this class reads it where it is.
 */
public final class ClaimFile {

  private static final String HEADER = "from,to,type";

  /** One edge the owner wrote down, and the line it is on so a refusal can be opened. */
  public record Row(int line, String fromQid, String toQid, String typeCode) {

    public Row {
      Objects.requireNonNull(fromQid, "fromQid");
      Objects.requireNonNull(toQid, "toQid");
      Objects.requireNonNull(typeCode, "typeCode");
    }
  }

  private ClaimFile() {}

  /** Every edge the file names, in file order. */
  public static List<Row> read(Path path) {
    Objects.requireNonNull(path, "path");
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("no claims file at " + path + " — nothing to claim");
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + path, e);
    }

    List<Row> rows = new ArrayList<>();
    boolean headerSeen = false;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).trim();
      int number = i + 1;
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      if (!headerSeen) {
        if (!line.replace(" ", "").equalsIgnoreCase(HEADER)) {
          throw refusal(
              path, number, "the first row must be the header " + HEADER + ", got: " + line);
        }
        headerSeen = true;
        continue;
      }
      String[] fields = line.split(",", -1);
      if (fields.length < 3) {
        throw refusal(path, number, "a claim needs three fields, " + HEADER);
      }
      String from = fields[0].trim();
      String to = fields[1].trim();
      String code = fields[2].trim();
      if (!Qid.looksLikeAQid(from)) {
        throw refusal(path, number, "from must look like Q12345, got: " + from);
      }
      if (!Qid.looksLikeAQid(to)) {
        throw refusal(path, number, "to must look like Q12345, got: " + to);
      }
      if (EdgeTypes.byCode(code).isEmpty()) {
        throw refusal(path, number, "no registered edge type for code: " + code);
      }
      rows.add(new Row(number, from, to, code));
    }
    if (!headerSeen) {
      throw new IllegalArgumentException(path + " does not start with the header " + HEADER);
    }
    return List.copyOf(rows);
  }

  private static IllegalArgumentException refusal(Path path, int line, String problem) {
    return new IllegalArgumentException(
        path + " line " + line + ": " + problem + " — nothing was appended");
  }
}
