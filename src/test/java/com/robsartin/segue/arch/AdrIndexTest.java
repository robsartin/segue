package com.robsartin.segue.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/adr/README.md} is the entry point to every decision this project has made, it is
 * maintained by hand, and until this class nothing in the build read it — issue #170.
 *
 * <p>In one week three branches appended to the index at once and an entry was silently dropped
 * three separate times, by three different mechanisms, each caught only by a person counting
 * afterwards. An ADR that exists on disk but not in the index is a decision nobody finds, and the
 * failure is silent in both directions: a dropped row breaks no link.
 *
 * <p><b>The index's shape is load-bearing here</b>, and each test names the shape it parses: a row
 * is {@code - [N. Title](NNNN-slug.md) — _Status_} and a section is a {@code ## } heading. An index
 * edit that keeps the facts but changes the shape fails these tests rather than passing them
 * silently, which is the safe direction.
 *
 * <p>What is deliberately NOT checked, so nobody reads more assurance into this class than it
 * gives: the indented description line under each row and its {@code Related:} line. Prose — the
 * same reason {@link DeveloperGuideEnumerationsTest} declines to check the guide's prose.
 */
class AdrIndexTest {

  private static final Path ADR = RepositoryTree.root().resolve("docs/adr");
  private static final String INDEX = RepositoryTree.read(ADR.resolve("README.md"));

  /**
   * {@code - [41. Export bounded views…](0041-graph-exporter-views-and-formats.md) — _Accepted_}
   */
  private static final Pattern ROW =
      Pattern.compile("^- \\[(\\d+)\\. (.*)]\\((\\d{4}-[a-z0-9-]+\\.md)\\) — _(.+)_$");

  /** {@code ## Universal} — the heading a run of rows belongs to. */
  private static final Pattern SECTION = Pattern.compile("^## (.+)$");

  /** {@code # 41. Export bounded views…}, the ADR's own heading. */
  private static final Pattern HEADING = Pattern.compile("^# (\\d+)\\. (.+)$", Pattern.MULTILINE);

  /** {@code status: Accepted} or {@code status: "Superseded by 0034"}, in the YAML front matter. */
  private static final Pattern STATUS =
      Pattern.compile("^status: *\"?(.*?)\"? *$", Pattern.MULTILINE);

  /** Every file in {@code docs/adr/} except the index itself. */
  private static final Pattern ADR_FILE = Pattern.compile("^(\\d{4})-[a-z0-9-]+\\.md$");

  private static final List<Row> ROWS = parseRows();

  /**
   * The section names the adr-toolkit's {@code build_index} can produce: the values of {@code
   * _AXIS_DISPLAY_NAMES} in {@code adr_toolkit/index.py}, plus {@code "Uncategorized"} — the
   * heading {@code build_index} renders for entries whose axis it does not recognize. Copied here
   * by hand, not read from the toolkit, because this repository does not depend on the toolkit's
   * Python package at build time.
   */
  private static final List<String> ALLOWED_SECTIONS =
      List.of(
          "Project",
          "Universal",
          "Language",
          "Framework",
          "App shape",
          "UI tech",
          "Library",
          "Concern",
          "Interaction",
          "Uncategorized");

  /** One index row. The description and {@code Related:} lines beneath it are not parsed. */
  private record Row(String section, int number, String title, String file, String status) {}

  @Test
  @DisplayName("Every ADR file on disk has exactly one row in the index")
  void shouldGiveEveryAdrFileExactlyOneRowWhenTheIndexIsParsed() {
    List<String> named = ROWS.stream().map(Row::file).toList();

    List<String> missing = adrFileNames().stream().filter(f -> !named.contains(f)).toList();
    assertThat(missing)
        .as("ADR files with no row in docs/adr/README.md — a dropped row is a lost decision")
        .isEmpty();

    List<String> twice =
        adrFileNames().stream().filter(f -> named.stream().filter(f::equals).count() > 1).toList();
    assertThat(twice).as("ADR files named by more than one row in docs/adr/README.md").isEmpty();
  }

  @Test
  @DisplayName("Every row in the index names an ADR file that exists")
  void shouldNameOnlyExistingFilesWhenTheIndexIsParsed() {
    List<String> orphans =
        ROWS.stream().map(Row::file).filter(f -> !Files.exists(ADR.resolve(f))).toList();

    assertThat(orphans)
        .as("index rows naming a file that is not in docs/adr/ — a row that outlived its file")
        .isEmpty();
  }

  @Test
  @DisplayName("No ADR number is claimed twice, in the index or on disk")
  void shouldClaimEachNumberOnceWhenTheIndexAndTheDirectoryAreRead() {
    assertThat(claimedMoreThanOnce(ROWS.stream().map(r -> "%04d".formatted(r.number())).toList()))
        .as("ADR numbers claimed by more than one row in docs/adr/README.md")
        .isEmpty();

    assertThat(claimedMoreThanOnce(adrFileNames().stream().map(f -> f.substring(0, 4)).toList()))
        .as("ADR numbers claimed by more than one file in docs/adr/")
        .isEmpty();
  }

  private static List<String> claimedMoreThanOnce(List<String> numbers) {
    return numbers.stream()
        .distinct()
        .filter(n -> numbers.stream().filter(n::equals).count() > 1)
        .toList();
  }

  @Test
  @DisplayName("Within each section of the index, rows ascend by ADR number")
  void shouldAscendByNumberWithinASectionWhenTheRowsAreGrouped() {
    List<String> descents = new ArrayList<>();
    for (int i = 1; i < ROWS.size(); i++) {
      Row previous = ROWS.get(i - 1);
      Row current = ROWS.get(i);
      if (current.section().equals(previous.section()) && current.number() <= previous.number()) {
        descents.add(
            "%s: %d comes after %d"
                .formatted(current.section(), current.number(), previous.number()));
      }
    }

    assertThat(descents)
        .as(
            "rows out of ascending order within their own section of docs/adr/README.md — a"
                + " conflict resolved in marker order interleaves numbers here. The index is"
                + " sectioned and the order is per section, so a section may end at any number"
                + " and the next section may start below it; only two rows sharing a section are"
                + " compared.")
        .isEmpty();
  }

  /**
   * {@link #shouldAscendByNumberWithinASectionWhenTheRowsAreGrouped} checks ordering only within a
   * section, comparing consecutive rows that share a {@code section} string — so a typo'd {@code ##
   * } heading opens a second, unrelated section whose rows ascend on their own, and that test never
   * sees the split. This test catches the split at the heading itself: every {@code ## } line in
   * {@code docs/adr/README.md} must be one of {@link #ALLOWED_SECTIONS}, and no heading may appear
   * twice (a repeated heading is the same split by a different route — two runs of rows that belong
   * together, told apart).
   *
   * <p>{@link #ALLOWED_SECTIONS} is hand-held rather than read from the toolkit: the names are
   * owned by {@code adr_toolkit/index.py}'s {@code _AXIS_DISPLAY_NAMES}, not by this repository,
   * and this repository has no dependency through which to read that Python dict at build time. The
   * accepted cost is that a rename or a new axis in the toolkit does not fail this test on its own
   * — it surfaces only when {@code build_index} is next run against this repo and writes a heading
   * {@link #ALLOWED_SECTIONS} does not recognize, at which point this list needs the same edit.
   */
  @Test
  @DisplayName("Every section heading in the index is one the toolkit can produce, and none twice")
  void shouldRejectAnUnknownOrDuplicatedSectionHeadingWhenTheIndexIsRead() {
    List<String> headings = sectionHeadings();

    List<String> unknown = headings.stream().filter(h -> !ALLOWED_SECTIONS.contains(h)).toList();
    assertThat(unknown)
        .as(
            "docs/adr/README.md has `## ` heading(s) not among adr_toolkit/index.py"
                + " _AXIS_DISPLAY_NAMES (plus \"Uncategorized\"), the toolkit's authority for"
                + " section names")
        .isEmpty();

    assertThat(claimedMoreThanOnce(headings))
        .as("docs/adr/README.md has the same `## ` section heading more than once")
        .isEmpty();
  }

  /** Every {@code ## } heading in the index, in file order, duplicates included. */
  private static List<String> sectionHeadings() {
    List<String> headings = new ArrayList<>();
    for (String line : INDEX.lines().toList()) {
      Matcher heading = SECTION.matcher(line);
      if (heading.matches()) {
        headings.add(heading.group(1));
      }
    }
    return headings;
  }

  @Test
  @DisplayName("Each row agrees with its file on number, title and status")
  void shouldAgreeWithTheFileOnEveryFieldWhenARowIsComparedToIt() {
    List<String> disagreements = new ArrayList<>();
    for (Row row : ROWS) {
      Path file = ADR.resolve(row.file());
      if (!Files.exists(file)) {
        continue; // already reported, by name, as a row that outlived its file
      }
      String text = RepositoryTree.read(file);
      int inFilename = Integer.parseInt(row.file().substring(0, 4));
      if (inFilename != row.number()) {
        disagreements.add(
            "%s: number %d in the filename, %d in the row"
                .formatted(row.file(), inFilename, row.number()));
      }
      Matcher heading = HEADING.matcher(text);
      if (!heading.find()) {
        disagreements.add(
            "%s: no `# N. Title` heading to compare the row to".formatted(row.file()));
      } else {
        if (Integer.parseInt(heading.group(1)) != row.number()) {
          disagreements.add(
              "%s: number %s in the heading, %d in the row"
                  .formatted(row.file(), heading.group(1), row.number()));
        }
        if (!heading.group(2).equals(row.title())) {
          disagreements.add(
              "%s: title \"%s\" in the heading, \"%s\" in the row"
                  .formatted(row.file(), heading.group(2), row.title()));
        }
      }
      Matcher status = STATUS.matcher(text);
      if (!status.find()) {
        disagreements.add("%s: no `status:` in the front matter".formatted(row.file()));
      } else if (!status.group(1).equals(row.status())) {
        disagreements.add(
            "%s: status \"%s\" in the front matter, \"%s\" in the row"
                .formatted(row.file(), status.group(1), row.status()));
      }
    }

    assertThat(disagreements)
        .as(
            "index rows disagreeing with the file they point at. The file is the source and the"
                + " row is the projection, so the row is what gets corrected. Titles are compared"
                + " exactly, backticks included: a retyped title is the drift this catches.")
        .isEmpty();
  }

  private static List<String> adrFileNames() {
    try (Stream<Path> entries = Files.list(ADR)) {
      return entries
          .map(p -> p.getFileName().toString())
          .filter(name -> ADR_FILE.matcher(name).matches())
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Row> parseRows() {
    List<Row> rows = new ArrayList<>();
    String section = "(before any heading)";
    for (String line : INDEX.lines().toList()) {
      Matcher heading = SECTION.matcher(line);
      if (heading.matches()) {
        section = heading.group(1);
        continue;
      }
      Matcher row = ROW.matcher(line);
      if (row.matches()) {
        rows.add(
            new Row(
                section, Integer.parseInt(row.group(1)), row.group(2), row.group(3), row.group(4)));
      }
    }
    return List.copyOf(rows);
  }
}
