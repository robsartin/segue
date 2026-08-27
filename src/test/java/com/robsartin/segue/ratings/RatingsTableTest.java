package com.robsartin.segue.ratings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one thing a person actually reads, as a pure function: rows in, text out.
 *
 * <p>Every rating, note and label below is invented. ADR 33 (as amended by issue #37) names a test
 * fixture written from real ratings as one of the few ways this public repository could leak the
 * only personal data segue holds, and this is the test most tempting to write from a real run.
 */
class RatingsTableTest {

  private static final Instant EARLY = Instant.parse("2026-01-02T09:00:00Z");
  private static final Instant MIDDLE = Instant.parse("2026-03-04T10:00:00Z");
  private static final Instant LATE = Instant.parse("2026-05-06T11:00:00Z");

  private static final AffinityRow LOVED =
      new AffinityRow("Q900001", "The Invented Quartet", 5, "heard it in a made-up shop", MIDDLE);
  private static final AffinityRow FINE =
      new AffinityRow("Q900002", "A Placeholder Novel", 3, null, LATE);
  private static final AffinityRow NOT_FOR_ME =
      new AffinityRow("Q900003", "Imaginary Film", 1, "not for me, invented", EARLY);

  private static String render(SortOrder sort, AffinityRow... rows) {
    StringWriter out = new StringWriter();
    try {
      RatingsTable.write(List.of(rows), sort, out);
    } catch (IOException e) {
      throw new AssertionError("a StringWriter cannot fail", e);
    }
    return out.toString();
  }

  private static List<String> ratedLines(String rendered) {
    return rendered
        .lines()
        .filter(line -> line.startsWith("Q") || line.matches("^[1-5] .*"))
        .toList();
  }

  @Test
  @DisplayName("the file says what it is: personal data, ADR 33, not for version control")
  void namesItselfAsPersonalData() {
    String rendered = render(SortOrder.RATING, LOVED);

    assertThat(rendered.lines().findFirst()).isPresent();
    assertThat(rendered).contains("personal data").contains("ADR 33").contains("version control");
  }

  @Test
  @DisplayName("the header says which ordering was applied, so the file cannot misrepresent itself")
  void namesTheOrdering() {
    assertThat(render(SortOrder.RATING, LOVED)).contains(SortOrder.RATING.describe());
    assertThat(render(SortOrder.RECENT, LOVED)).contains(SortOrder.RECENT.describe());
  }

  @Test
  @DisplayName("one row carries the label, the rating, the note and when it last changed")
  void carriesEveryFieldOfARating() {
    String rendered = render(SortOrder.RATING, LOVED);

    assertThat(rendered)
        .contains("The Invented Quartet")
        .contains("heard it in a made-up shop")
        .contains("Q900001")
        .contains(MIDDLE.toString());
  }

  @Test
  @DisplayName("by rating: the highest first, because that is what the ordering claims")
  void sortsByRatingDescending() {
    String rendered = render(SortOrder.RATING, NOT_FOR_ME, FINE, LOVED);

    assertThat(ratedLines(rendered))
        .satisfiesExactly(
            first -> assertThat(first).contains("The Invented Quartet"),
            second -> assertThat(second).contains("A Placeholder Novel"),
            third -> assertThat(third).contains("Imaginary Film"));
  }

  @Test
  @DisplayName("by recency: the most recently changed first — what did I change my mind about")
  void sortsByRecencyDescending() {
    String rendered = render(SortOrder.RECENT, NOT_FOR_ME, LOVED, FINE);

    assertThat(ratedLines(rendered))
        .satisfiesExactly(
            first -> assertThat(first).contains("A Placeholder Novel"),
            second -> assertThat(second).contains("The Invented Quartet"),
            third -> assertThat(third).contains("Imaginary Film"));
  }

  @Test
  @DisplayName("equal ratings fall back to recency, then to qid, so two runs agree")
  void breaksTiesDeterministically() {
    AffinityRow older = new AffinityRow("Q900010", "Alpha Invention", 4, null, EARLY);
    AffinityRow newer = new AffinityRow("Q900011", "Beta Invention", 4, null, LATE);
    AffinityRow sameInstant = new AffinityRow("Q900009", "Gamma Invention", 4, null, LATE);

    assertThat(ratedLines(render(SortOrder.RATING, older, newer, sameInstant)))
        .satisfiesExactly(
            first -> assertThat(first).contains("Gamma Invention"),
            second -> assertThat(second).contains("Beta Invention"),
            third -> assertThat(third).contains("Alpha Invention"));
  }

  @Test
  @DisplayName("a missing note is blank, never the word null")
  void rendersAMissingNoteAsNothing() {
    String rendered = render(SortOrder.RATING, FINE);

    assertThat(rendered).doesNotContain("null");
  }

  @Test
  @DisplayName("a note with line breaks stays on its own row rather than breaking the table")
  void flattensANoteThatSpansLines() {
    AffinityRow multiline =
        new AffinityRow("Q900004", "Invented Play", 4, "first line\nsecond line", MIDDLE);

    String rendered = render(SortOrder.RATING, multiline);

    assertThat(ratedLines(rendered)).hasSize(1);
    assertThat(rendered).contains("first line second line");
  }

  @Test
  @DisplayName("a rating whose entity is not in the graph says so rather than showing an empty gap")
  void saysWhenTheGraphHasNoLabel() {
    AffinityRow unlabelled = new AffinityRow("Q900005", null, 2, "invented note", MIDDLE);

    String rendered = render(SortOrder.RATING, unlabelled);

    assertThat(rendered).contains(AffinityRow.NO_LABEL).contains("Q900005");
  }

  @Test
  @DisplayName("columns line up: the widest label sets the width for every row")
  void alignsTheColumns() {
    AffinityRow wide =
        new AffinityRow("Q900006", "A Considerably Longer Invented Title", 5, null, MIDDLE);

    List<String> rows = ratedLines(render(SortOrder.RATING, wide, NOT_FOR_ME));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).indexOf("Q900006")).isEqualTo(rows.get(1).indexOf("Q900003"));
  }

  @Test
  @DisplayName("nothing rated is still a readable file, not an empty one")
  void writesSomethingWhenNothingIsRated() {
    String rendered = render(SortOrder.RATING);

    assertThat(rendered).contains("no ratings");
    assertThat(ratedLines(rendered)).isEmpty();
  }
}
