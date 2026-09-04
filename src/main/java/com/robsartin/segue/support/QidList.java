package com.robsartin.segue.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The entity list the {@code subgraph} view keeps.
 *
 * <p>Designed so that the seeding tool's mapping file (ADR 40) works unchanged, because that is the
 * case the exporter's {@code subgraph} view exists for: fed the file that turned a list of names
 * into QIDs, the subgraph shows the acts actually on the list and how they connect, with the
 * discovered intermediates stripped out.
 *
 * <p><b>It lives in {@code support} because more than one dev-side tool reads the same file (ADR
 * 45).</b> The recommender's known-list is the same shape and answers a different question of it -
 * which entities are already known, so that a well-connected entity absent from it is a
 * recommendation. The tools may not depend on each other: each carries its own ArchUnit fence, and
 * a dependency on a sibling would let one inherit the other's. A shared reader none of them owns is
 * the way they all read the file identically without any of them learning the others exist.
 *
 * <p><b>The rule is: the first comma-separated field on a line that is exactly a QID.</b> That
 * reads a bare one-per-line list and the mapping file with the same code and no header handling. It
 * also does the one thing a looser rule gets wrong: a <em>review</em> row carries no QID of its own
 * but its {@code reason} column quotes the candidates it could not choose between, and a "first
 * {@code Q\d+} anywhere on the line" rule would happily export a rejected candidate. A field that
 * is exactly a QID is a QID; a QID inside a sentence is prose.
 *
 * <p>This class does not depend on {@code seed} and must not: it recognises a shape, not a schema,
 * so the tools stay independent and a bare list of QIDs typed by hand works just as well.
 *
 * <p><b>The file is personal data.</b> A list of who someone listens to, reads and watches is
 * exactly what ADR 33 governs, and it lives outside this repository (issue #37, ADR 40). This tool
 * reads it where it is and never copies it anywhere.
 */
public final class QidList {

  private static final Pattern QID = Pattern.compile("Q\\d+");

  private QidList() {}

  /** Every distinct QID in the file, in the order the file gives them. */
  public static List<String> read(Path path) {
    Objects.requireNonNull(path, "path");
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("no entity list at " + path);
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + path, e);
    }

    Set<String> qids = new LinkedHashSet<>();
    for (String line : lines) {
      for (String field : line.split(",")) {
        String candidate = field.trim().replace("\"", "");
        if (QID.matcher(candidate).matches()) {
          qids.add(candidate);
          break;
        }
      }
    }
    if (qids.isEmpty()) {
      throw new IllegalArgumentException(
          "no QID in " + path + " — expected one per line, or the qid column of a mapping file");
    }
    return List.copyOf(qids);
  }
}
