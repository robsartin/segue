package com.robsartin.segue.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No commit hash may be cited in {@code docs/adr/} unless this class names it and the file it is
 * cited in — issue #274.
 *
 * <p>This repository squash-merges. A branch commit is rewritten into a single commit on {@code
 * main} and the original object is never in {@code main}'s history, so a hash an amendment cited as
 * evidence of ordering — "the rule was committed as … before the reading existed" — resolves to
 * nothing in a fresh clone. ADR 1's 2026-09-06 amendment records the reading that found this and
 * resolves every hash it found unreachable; this class does not restate that reading.
 *
 * <p><b>The list is (file, hash) pairs, and the assertion is exact in both directions.</b> A new
 * citation reds, which is the guard. A pair that is allowlisted but no longer in the tree reds too,
 * so an entry cannot rot here describing a citation somebody deleted. The cost is that every new
 * citation is an edit somebody made on purpose — which is the rule ADR 1's 2026-09-06 amendment
 * states.
 *
 * <p><b>What is deliberately NOT checked</b>, so nobody reads more assurance into this class than
 * it gives: whether a hash still resolves anywhere. That needs the network and a remote, and the
 * answer changes without this repository changing. The amendment records the answer as it was read
 * on 2026-09-06; this class only controls what may be written.
 */
class AdrCitationsTest {

  /**
   * A commit hash cited in prose: seven to forty hex characters, inside a code span. The backticks
   * are part of the pattern rather than a word boundary — a hash written as bare prose in a shell
   * example is not a citation, and requiring the code span is what keeps issue numbers and ADR
   * numbers out. Case-insensitive: git writes lowercase, and a citation pasted in uppercase must
   * not slip past unseen. See {@code docs/adr/0001-record-architecture-decisions.md}'s 2026-09-06
   * amendment.
   */
  private static final Pattern CITATION = Pattern.compile("`([0-9a-fA-F]{7,40})`");

  /** Every commit citation in one document, in the order it appears, duplicates included. */
  static List<String> hashesIn(String markdown) {
    List<String> found = new ArrayList<>();
    Matcher matcher = CITATION.matcher(markdown);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }

  @Test
  @DisplayName("a backticked hex run of seven to forty characters is read as a commit citation")
  void shouldFindAPlantedHashWhenAdrProseIsScanned() {
    assertThat(hashesIn("the index row was fixed in `deadbeef`, before the reading existed"))
        .containsExactly("deadbeef");
  }

  @Test
  @DisplayName("issue numbers, ADR numbers, filenames and bare hashes are not commit citations")
  void shouldFindNothingWhenTheProseIsNotAHashCitation() {
    assertThat(
            hashesIn(
                """
                ADR `45`, issue #274, `main` at 7e2651c, `seed`, `NodeKind`, `INFLUENCED_BY`,
                `0045-recommend-by-normalised-lift-with-routes.md`, `123456`,
                `12345678901234567890123456789012345678901`
                """))
        .isEmpty();
  }

  private static final Path ADR = RepositoryTree.root().resolve("docs/adr");

  /** One citation: the hash, and the ADR file it is written in. */
  record Citation(String file, String hash) {}

  private static final Comparator<Citation> BY_FILE_THEN_HASH =
      Comparator.comparing(Citation::file).thenComparing(Citation::hash);

  /**
   * Cited hashes {@code main} can reach: the citing branch's work was squashed onto {@code main},
   * but these particular objects are on {@code main} themselves.
   */
  private static final List<Citation> REACHABLE_FROM_MAIN =
      List.of(
          new Citation("0024-sqlite-assertion-log.md", "a79c6ca"),
          new Citation("0024-sqlite-assertion-log.md", "fd88813"),
          new Citation("0044-retraction-as-a-new-claim.md", "0783492"),
          new Citation("0044-retraction-as-a-new-claim.md", "a7c3455"),
          new Citation("0058-stand-in-identifiers-cannot-be-allocatable.md", "cd1d8dc"),
          new Citation("0059-owner-claims-as-a-third-layer.md", "2e01341"),
          new Citation("0059-owner-claims-as-a-third-layer.md", "a7c3455"));

  /**
   * Cited hashes {@code main} cannot reach, because a squash merge left the branch commit out of
   * its history. Each is resolved to the pull request that carried it, and the time it was pushed,
   * in {@code docs/adr/0001-record-architecture-decisions.md}'s 2026-09-06 amendment (issue #274) —
   * which is where those two facts live. This list holds only hash and file; the amendment holds
   * only hash, pull request and push time. Neither restates the other.
   */
  private static final List<Citation> UNREACHABLE_RESOLVED_BY_ADR_1 =
      List.of(
          new Citation("0001-record-architecture-decisions.md", "0a29f45"),
          new Citation(
              "0045-recommend-by-normalised-lift-with-routes.md",
              "33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba"),
          new Citation("0045-recommend-by-normalised-lift-with-routes.md", "3c2171e"),
          new Citation("0045-recommend-by-normalised-lift-with-routes.md", "74e757f"),
          new Citation("0045-recommend-by-normalised-lift-with-routes.md", "9937f86"),
          new Citation("0045-recommend-by-normalised-lift-with-routes.md", "e0e398b"),
          new Citation("0059-owner-claims-as-a-third-layer.md", "fdd420d"));

  /**
   * The citations the 2026-09-06 amendment to ADR 1 makes while resolving the others. Six of the
   * table's seven hashes are new here — {@code 0a29f45} is not, since this ADR already cited it in
   * the 2026-09-01 amendment and it stays in {@link #UNREACHABLE_RESOLVED_BY_ADR_1}, uncounted
   * twice. {@code 0783492} is where {@code fdd420d}'s work actually landed on {@code main}, and
   * {@code 7e2651c} is the commit the survey was read against — both on {@code main}, both cited
   * beside a pull request number and a time, the shape the amendment's own rule asks for. The
   * remaining five ({@code a7c3455}, {@code 2e01341}, {@code a79c6ca}, {@code fd88813}, {@code
   * cd1d8dc}) are restated in the "why the earlier citations are left as they are" paragraph as
   * evidence they are still reachable from {@code main} today; each is already allowlisted above
   * for the ADR it was originally cited in, but citing it again in THIS file is a distinct (file,
   * hash) pair the exact-both-directions assertion requires listed here too.
   */
  private static final List<Citation> IN_THE_ADR_1_AMENDMENT =
      List.of(
          new Citation("0001-record-architecture-decisions.md", "0783492"),
          new Citation("0001-record-architecture-decisions.md", "2e01341"),
          new Citation(
              "0001-record-architecture-decisions.md", "33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba"),
          new Citation("0001-record-architecture-decisions.md", "3c2171e"),
          new Citation("0001-record-architecture-decisions.md", "74e757f"),
          new Citation("0001-record-architecture-decisions.md", "7e2651c"),
          new Citation("0001-record-architecture-decisions.md", "9937f86"),
          new Citation("0001-record-architecture-decisions.md", "a79c6ca"),
          new Citation("0001-record-architecture-decisions.md", "a7c3455"),
          new Citation("0001-record-architecture-decisions.md", "cd1d8dc"),
          new Citation("0001-record-architecture-decisions.md", "e0e398b"),
          new Citation("0001-record-architecture-decisions.md", "fd88813"),
          new Citation("0001-record-architecture-decisions.md", "fdd420d"));

  @Test
  @DisplayName("every commit hash cited in docs/adr is allowlisted, in the file it is cited in")
  void shouldAllowlistEveryCitationWhenTheAdrsAreScanned() {
    assertThat(citedInTheAdrs())
        .as(
            "every backticked hex run of 7-40 characters in docs/adr/*.md must be allowlisted here"
                + " as (file, hash), in both directions. A new bare commit hash is not an ordering"
                + " witness under a squash merge: cite the pull request and the push time, and add"
                + " the hash here only alongside them (ADR 1, amendment of 2026-09-06). A pair"
                + " listed here but no longer in the tree is a stale entry to remove")
        .containsExactlyElementsOf(allowlist());
  }

  /**
   * Exact equality, both directions. A hash cited in an ADR that is not here reds — that is the
   * point of the guard. An entry here that is no longer cited reds too, so a stale line cannot sit
   * in this list pretending to describe the tree.
   */
  private static List<Citation> allowlist() {
    List<Citation> all = new ArrayList<>(REACHABLE_FROM_MAIN);
    all.addAll(UNREACHABLE_RESOLVED_BY_ADR_1);
    all.addAll(IN_THE_ADR_1_AMENDMENT);
    all.sort(BY_FILE_THEN_HASH);
    return all;
  }

  /**
   * Every {@code *.md} in {@code docs/adr}, the index included — a hash pasted into {@code
   * README.md} is a citation too, which is why {@link AdrIndexTest}'s narrower filename filter is
   * not reused here: it exists there to exclude the index.
   */
  private static List<Citation> citedInTheAdrs() {
    List<Citation> found = new ArrayList<>();
    try (Stream<Path> entries = Files.list(ADR)) {
      for (Path file : entries.sorted().toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".md")) {
          continue;
        }
        for (String hash : hashesIn(RepositoryTree.read(file))) {
          Citation citation = new Citation(name, hash);
          if (!found.contains(citation)) {
            found.add(citation);
          }
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    found.sort(BY_FILE_THEN_HASH);
    return found;
  }
}
