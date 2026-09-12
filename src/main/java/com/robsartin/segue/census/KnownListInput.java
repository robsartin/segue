package com.robsartin.segue.census;

import com.robsartin.segue.support.QidList;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** The known-list file, as the census is allowed to hold it. */
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
   */
  public static KnownListInput read(Path file) {
    Objects.requireNonNull(file, "file");
    return new KnownListInput(file.getFileName().toString(), QidList.read(file));
  }
}
