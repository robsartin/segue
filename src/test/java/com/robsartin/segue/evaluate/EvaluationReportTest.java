package com.robsartin.segue.evaluate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.atIndex;

import com.robsartin.segue.domain.Scorer;
import java.util.List;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EvaluationReportTest {

  /** Anything qid-shaped at all, wherever it appears. */
  private static final Pattern A_QID = Pattern.compile("\\bQ\\d+\\b");

  /** What the split reported: 10 eligible, 2 held out — the counts {@link #lines} now takes. */
  private static final int ELIGIBLE = 10;

  private static final int HELD_OUT_COUNT = 2;

  @Test
  @DisplayName("the header names the split and the top, and the table has one row per reading")
  void shouldStateTheSplitAndOneRowPerReadingWhenTheReportIsRendered() {
    List<String> lines =
        EvaluationReport.lines(ELIGIBLE, HELD_OUT_COUNT, 25, List.of(reading(), sparse()));

    assertThat(lines.get(0)).isEqualTo(EvaluationReport.HEADER);
    assertThat(lines.get(1))
        .contains("10 eligible")
        .contains("2 held out")
        .contains("8 left on the known-list");
    assertThat(lines.get(2)).contains("top 25").contains("2 setting(s)");
    assertThat(lines).hasSize(3 + 1 + 2);
    assertThat(lines.get(3)).startsWith("scorer").contains("neg mean rank");
  }

  @Test
  @DisplayName("a mean is one decimal, and a mean over nothing is a literal dash")
  void shouldRenderADashWhenAMeanHasNothingToAverage() {
    List<String> lines =
        EvaluationReport.lines(ELIGIBLE, HELD_OUT_COUNT, 25, List.of(reading(), sparse()));

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
        new Reading(
            new Setting(Scorer.RAW, 2),
            123456,
            40,
            12,
            OptionalDouble.of(9.25),
            0,
            OptionalDouble.empty());

    List<String> lines =
        EvaluationReport.lines(ELIGIBLE, HELD_OUT_COUNT, 25, List.of(wide, sparse()));

    assertThat(lines.get(3).length())
        .as("the heading row is padded to the same width as every body row")
        .isEqualTo(lines.get(4).length())
        .isEqualTo(lines.get(5).length());
  }

  @Test
  @DisplayName("nothing qid-shaped reaches the report, whatever the split held")
  void shouldCarryNoIdentifierWhenTheSplitNamesEntities() {
    assertThat(EvaluationReport.lines(ELIGIBLE, HELD_OUT_COUNT, 25, List.of(reading())))
        .noneMatch(line -> A_QID.matcher(line).find());
  }

  /** The rendered row's cells, in column order — split on the multi-space gap between them. */
  private static List<String> cellsOf(String line) {
    return List.of(line.trim().split("\\s{2,}"));
  }

  private static Reading reading() {
    return new Reading(
        new Setting(Scorer.LIFT, 5), 900, 40, 4, OptionalDouble.of(7.5), 2, OptionalDouble.of(4.0));
  }

  private static Reading sparse() {
    return new Reading(
        new Setting(Scorer.RAW, 12), 40, 3, 0, OptionalDouble.empty(), 0, OptionalDouble.empty());
  }
}
