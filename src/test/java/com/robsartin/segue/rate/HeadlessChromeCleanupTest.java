package com.robsartin.segue.rate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Removing the throwaway profile must never fail a test.
 *
 * <p>It did. Chrome is still removing its own profile files while it exits, so an entry can vanish
 * between {@link Files#walk} listing a directory and visiting it — and the walk is lazy, so it
 * raises {@link java.io.UncheckedIOException}, which is not an {@link IOException} and so escaped a
 * {@code catch (IOException)} written to swallow exactly this. It surfaced as a green test failing
 * in {@code @AfterEach} on CI while passing locally.
 *
 * <p>The race itself cannot be staged deterministically, so these drive the same lazy-traversal
 * failure through a directory the walk is not allowed to read. The mechanism under test is the one
 * that broke: a listing that fails partway through a walk.
 */
class HeadlessChromeCleanupTest {

  @Test
  @DisplayName("a tree the walk cannot fully read is still removed, without throwing")
  void survivesAListingThatFailsPartwayThrough() throws IOException {
    Path root = Files.createTempDirectory("segue-cleanup");
    Path unreadable = Files.createDirectory(root.resolve("unreadable"));
    Files.writeString(unreadable.resolve("cookies"), "x");
    Files.writeString(root.resolve("readable"), "x");
    assumeTrue(denyListing(unreadable), "this filesystem or user can read the directory anyway");

    try {
      assertThatCode(() -> HeadlessChrome.deleteTree(root)).doesNotThrowAnyException();
    } finally {
      restore(root, unreadable);
    }
  }

  @Test
  @DisplayName("what it can reach is removed, so tolerating the failure is not tolerating a no-op")
  void stillRemovesTheReachablePart() throws IOException {
    Path root = Files.createTempDirectory("segue-cleanup");
    Path unreadable = Files.createDirectory(root.resolve("unreadable"));
    Files.writeString(unreadable.resolve("cookies"), "x");
    Path reachable = Files.writeString(root.resolve("readable"), "x");
    assumeTrue(denyListing(unreadable), "this filesystem or user can read the directory anyway");

    try {
      HeadlessChrome.deleteTree(root);
      assertThat(reachable).doesNotExist();
    } finally {
      restore(root, unreadable);
    }
  }

  @Test
  @DisplayName("a profile that is already gone is not an error")
  void toleratesAnAbsentRoot() throws IOException {
    Path root = Files.createTempDirectory("segue-cleanup");
    Files.delete(root);

    assertThatCode(() -> HeadlessChrome.deleteTree(root)).doesNotThrowAnyException();
  }

  /**
   * Makes {@code dir} unlistable and confirms it took effect, so the test cannot pass by not
   * applying — running as root, or on a filesystem without POSIX permissions, it reports false and
   * the caller skips visibly rather than asserting against a walk that never failed.
   */
  private static boolean denyListing(Path dir) throws IOException {
    if (!dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return false;
    }
    Files.setPosixFilePermissions(dir, java.util.Set.of());
    try (var ignored = Files.list(dir)) {
      return false; // still readable, so there is nothing to tolerate
    } catch (IOException expected) {
      return true;
    }
  }

  private static void restore(Path root, Path unreadable) throws IOException {
    Files.setPosixFilePermissions(
        unreadable, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(HeadlessChromeCleanupTest::deleteQuietly);
    } catch (IOException ignored) {
      // best effort in a temp directory
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // best effort
    }
  }
}
