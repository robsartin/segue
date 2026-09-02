package com.robsartin.segue.arch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locating the repository root and reading a file out of it, for the tests in this package that
 * check committed documents against the tree.
 *
 * <p>These two methods were private to {@link DeveloperGuideEnumerationsTest} until {@link
 * AdrIndexTest} needed them too. They live here rather than being copied because the second copy of
 * a rule is the one a future editor misses.
 *
 * <p><b>Public because the fourth caller is not in this package</b> (#183). {@code
 * own.DeveloperGuideRunbooksTest} checks the guide's {@code ownClaim} examples against {@code
 * OwnCli.parse}, which is package-private in {@code own} - so that test cannot live here, and the
 * alternative was the copy this class exists to prevent. Widening a test helper is the cheaper of
 * the two, and it widens nothing in {@code src/main}.
 */
public final class RepositoryTree {

  private RepositoryTree() {}

  /**
   * The directory holding {@code settings.gradle.kts}, found by walking up from the working dir.
   */
  public static Path root() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
      candidate = candidate.getParent();
    }
    if (candidate == null) {
      throw new IllegalStateException(
          "no settings.gradle.kts above "
              + Path.of("").toAbsolutePath()
              + " — cannot find the repository root");
    }
    return candidate;
  }

  /** Reads a UTF-8 file, turning the checked exception into an unchecked one. */
  public static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
