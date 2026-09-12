package com.robsartin.segue.support;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The known-list file, as a dev tool is allowed to hold it.
 *
 * <p><b>In {@code support} for the same reason {@code QidList} is (ADR 45).</b> Two dev tools read
 * one file: the census counts coverage and the promotion expander chooses its population from it
 * (#311, #313). {@code ArchitectureTest.theExpanderOpensNothingElse} forbids the expander every
 * sibling dev tool with no exception, so a shared reader neither of them owns is the only way they
 * can read one file by one rule.
 */
public record KnownListInput(String name, List<String> qids) {

  public KnownListInput {
    Objects.requireNonNull(name, "name");
    qids = List.copyOf(Objects.requireNonNull(qids, "qids"));
  }

  /**
   * The file's distinct qids, and its basename.
   *
   * <p><b>The basename, never the path</b>, and that is the only reason this type exists rather
   * than a {@code Path} reaching the section. The block is meant to be pasted, and a path names a
   * directory on the owner's machine (ADR 51, ADR 63).
   *
   * <p>The reading is {@code QidList}'s, unchanged and unwrapped: the same file {@code recommend},
   * {@code rate} and {@code evaluate} take, read by the same rule — the first comma-separated field
   * on a line that is exactly a qid. A field that is not one is passed over rather than refused,
   * and a file with no qid anywhere in it is refused by {@code QidList} itself.
   *
   * <p><b>The ids are read before the basename is taken</b>, so that every bad value is refused in
   * the reader's own words. A root directory has no file name component at all, and taking the
   * basename first turned {@code --known /} into a null dereference rather than the sentence naming
   * the path (issue #311).
   */
  public static KnownListInput read(Path file) {
    Objects.requireNonNull(file, "file");
    List<String> qids = QidList.read(file);
    return new KnownListInput(file.getFileName().toString(), qids);
  }
}
