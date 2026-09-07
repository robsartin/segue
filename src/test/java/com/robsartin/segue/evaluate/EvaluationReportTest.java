package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.atIndex;

import com.robsartin.segue.domain.Scorer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EvaluationReportTest {

  /** Anything qid-shaped at all, wherever it appears. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  /** What the split reported: 10 eligible, 5 folds, all 10 held out over them, 8 left at least. */
  private static final int ELIGIBLE = 10;

  private static final int FOLDS = 5;

  private static final int HELD_OUT_TOTAL = 10;

  private static final int LEAST_LEFT = 8;

  private static final Instant SINCE = Instant.parse("2026-09-06T15:00:00Z");

  @Test
  @DisplayName("the header names the split and the top, and the table has one row per reading")
  void shouldStateTheSplitAndOneRowPerReadingWhenTheReportIsRendered() {
    List<String> lines =
        EvaluationReport.lines(
            ELIGIBLE,
            FOLDS,
            HELD_OUT_TOTAL,
            LEAST_LEFT,
            25,
            Optional.empty(),
            0,
            0,
            List.of(reading(), sparse()));

    assertThat(lines.get(0)).isEqualTo(EvaluationReport.HEADER);
    assertThat(lines.get(1))
        .contains("10 eligible")
        .contains("in 5 fold(s)")
        .contains("10 held out over all folds")
        .contains("at least 8 left on the known-list");
    assertThat(lines.get(2)).contains("top 25").contains("2 setting(s)");
    assertThat(lines).hasSize(3 + 1 + 2);
    assertThat(lines.get(3)).startsWith("scorer").contains("neg mean rank");
  }

  @Test
  @DisplayName("a mean is one decimal, and a mean over nothing is a literal dash")
  void shouldRenderADashWhenAMeanHasNothingToAverage() {
    List<String> lines =
        EvaluationReport.lines(
            ELIGIBLE,
            FOLDS,
            HELD_OUT_TOTAL,
            LEAST_LEFT,
            25,
            Optional.empty(),
            0,
            0,
            List.of(reading(), sparse()));

    // Complete cells, not substrings — "7.50" would satisfy .contains("7.5") but must not satisfy
    // this. reading()'s columns are: scorer, floor, pool, in pool, hits, mean rank, negatives,
    // neg mean rank.
    assertThat(cellsOf(lines.get(4))).contains("7.5", atIndex(5)).contains("4.0", atIndex(7));
    assertThat(cellsOf(lines.get(5)))
        .as("no hits and no negatives — two dashes, never two zeroes")
        .contains(EvaluationReport.NO_MEAN, atIndex(5))
        .contains(EvaluationReport.NO_MEAN, atIndex(7));
  }

  @Test
  @DisplayName("every column lines up, because the widths come from the cells")
  void shouldAlignTheColumnsWhenACountIsWiderThanItsHeading() {
    Reading wide =
        new Reading(new Setting(Scorer.RAW, 2), 123456, 40, 12, 111, 0, 0, Halves.UNSPLIT);

    List<String> lines =
        EvaluationReport.lines(
            ELIGIBLE,
            FOLDS,
            HELD_OUT_TOTAL,
            LEAST_LEFT,
            25,
            Optional.empty(),
            0,
            0,
            List.of(wide, sparse()));

    assertThat(lines.get(3).length())
        .as("the heading row is padded to the same width as every body row")
        .isEqualTo(lines.get(4).length())
        .isEqualTo(lines.get(5).length());
  }

  @Test
  @DisplayName("nothing qid-shaped reaches the report, whatever the split held")
  void shouldCarryNoIdentifierWhenTheSplitNamesEntities() {
    assertThat(
            EvaluationReport.lines(
                ELIGIBLE,
                FOLDS,
                HELD_OUT_TOTAL,
                LEAST_LEFT,
                25,
                Optional.empty(),
                0,
                0,
                List.of(reading())))
        .noneMatch(line -> A_QID.matcher(line).find());
  }

  /**
   * Today's block, character for character (issue #276). The age split appends columns and inserts
   * one line, and this is what says the block is untouched when no instant is given — the property
   * every reading already on the record depends on.
   */
  private static final List<String> UNSPLIT_BLOCK =
      List.of(
          EvaluationReport.HEADER,
          "# held out every 5 of 10 eligible entity(ies), in 5 fold(s): 10 held out over all"
              + " folds, at least 8 left on the known-list in each.",
          "# top 25 per setting, over 2 setting(s).",
          "scorer  floor  pool  in pool  hits  mean rank  negatives  neg mean rank",
          "lift        5   900       40     4        7.5          2            4.0",
          "raw        12    40        3     0          -          0              -");

  @Test
  @DisplayName("the whole block renders exactly as it does today, character for character")
  void shouldRenderTheBlockUnchangedWhenNoInstantIsGiven() {
    assertThat(
            EvaluationReport.lines(
                ELIGIBLE,
                FOLDS,
                HELD_OUT_TOTAL,
                LEAST_LEFT,
                25,
                Optional.empty(),
                0,
                0,
                List.of(reading(), sparse())))
        .containsExactlyElementsOf(UNSPLIT_BLOCK);
  }

  @Test
  @DisplayName("the block states the instant and both halves, and every row gains four cells")
  void shouldStateTheHalvesWhenAnInstantIsGiven() {
    List<String> lines =
        EvaluationReport.lines(
            ELIGIBLE,
            FOLDS,
            HELD_OUT_TOTAL,
            LEAST_LEFT,
            25,
            Optional.of(SINCE),
            7,
            3,
            List.of(splitReading(), splitSparse()));

    assertThat(lines).hasSize(4 + 1 + 2);
    assertThat(lines.get(2))
        .isEqualTo(
            "# split by rating age at 2026-09-06T15:00:00Z: 7 old (rated before it), 3 new (rated"
                + " on or after it) — a rating's timestamp is its last write, so a re-rated old"
                + " promotion counts as new.");
    assertThat(lines.get(4)).endsWith("old in pool  old hits  new in pool  new hits");
    assertThat(cellsOf(lines.get(5)))
        .containsExactly("lift", "5", "900", "40", "4", "7.5", "2", "4.0", "30", "3", "10", "1");
  }

  @Test
  @DisplayName("a split row's first eight columns render exactly as the same row does unsplit")
  void shouldLeaveTheExistingColumnsWhereTheyAreWhenTheHalvesAreAppended() {
    // Appended rather than interleaved, so every reading already on the record keeps its shape.
    List<String> split =
        EvaluationReport.lines(
            ELIGIBLE,
            FOLDS,
            HELD_OUT_TOTAL,
            LEAST_LEFT,
            25,
            Optional.of(SINCE),
            7,
            3,
            List.of(splitReading(), splitSparse()));

    assertThat(split.get(5)).startsWith(UNSPLIT_BLOCK.get(4));
    assertThat(split.get(6)).startsWith(UNSPLIT_BLOCK.get(5));
  }

  @Test
  @DisplayName("nothing qid-shaped reaches the split line, whatever instant was given")
  void shouldCarryNoIdentifierWhenTheBlockStatesTheInstant() {
    // The instant is rendered from the parsed value, never from the string the operator typed,
    // so an Instant's own alphabet — digits, '-', ':', '.', 'T', 'Z' — is all this line can hold.
    assertThat(
            EvaluationReport.lines(
                ELIGIBLE,
                FOLDS,
                HELD_OUT_TOTAL,
                LEAST_LEFT,
                25,
                Optional.of(SINCE),
                7,
                3,
                List.of(splitReading())))
        .noneMatch(line -> A_QID.matcher(line).find());
  }

  @Test
  @DisplayName("an instant with an unsplit reading is refused, because one run is split or is not")
  void shouldRefuseTheBlockWhenTheInstantAndTheReadingsDisagree() {
    assertThatThrownBy(
            () ->
                EvaluationReport.lines(
                    ELIGIBLE,
                    FOLDS,
                    HELD_OUT_TOTAL,
                    LEAST_LEFT,
                    25,
                    Optional.of(SINCE),
                    7,
                    3,
                    List.of(reading())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disagree");
  }

  @Test
  @DisplayName("halves with no instant are refused, because there is no line to state them on")
  void shouldRefuseTheBlockWhenThereAreHalvesButNoInstant() {
    assertThatThrownBy(
            () ->
                EvaluationReport.lines(
                    ELIGIBLE,
                    FOLDS,
                    HELD_OUT_TOTAL,
                    LEAST_LEFT,
                    25,
                    Optional.empty(),
                    7,
                    3,
                    List.of(reading())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no instant was given");
  }

  private static Reading splitReading() {
    return new Reading(
        new Setting(Scorer.LIFT, 5), 900, 40, 4, 30, 2, 8, new Halves(true, 30, 3, 10, 1));
  }

  private static Reading splitSparse() {
    return new Reading(
        new Setting(Scorer.RAW, 12), 40, 3, 0, 0, 0, 0, new Halves(true, 2, 0, 1, 0));
  }

  /** The rendered row's cells, in column order — split on the multi-space gap between them. */
  private static List<String> cellsOf(String line) {
    return List.of(line.trim().split("\\s{2,}"));
  }

  private static Reading reading() {
    return new Reading(new Setting(Scorer.LIFT, 5), 900, 40, 4, 30, 2, 8, Halves.UNSPLIT);
  }

  private static Reading sparse() {
    return new Reading(new Setting(Scorer.RAW, 12), 40, 3, 0, 0, 0, 0, Halves.UNSPLIT);
  }
}
