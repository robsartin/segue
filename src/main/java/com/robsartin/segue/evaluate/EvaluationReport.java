package com.robsartin.segue.evaluate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Readings in, one aligned block of text out. A pure function, and the only class here that decides
 * what a person sees (ADR 65).
 *
 * <p><b>Every value is an integer, a fixed one-decimal, or the literal {@link #NO_MEAN}, and every
 * label is a literal in this file or a {@code Scorer} spelling.</b> That is what makes the whole
 * output safe to paste and what {@code EvaluationIsSafeToPasteTest} asserts — the same property
 * {@code CensusReport} has and ADR 63 argues for. No qid, label, note or rating value reaches this
 * method at all — and that is true of the whole signature, not just {@link Reading}'s shape: {@link
 * #lines} takes two plain counts and a top instead of the {@code HeldOut} that produced them,
 * deliberately narrower than the plan first drafted, because a type that carries a qid list and a
 * qid-keyed map has somewhere to put one even when this method never reads it.
 *
 * <p><b>A mean over nothing is a dash rather than zero.</b> No hits and a mean rank of zero are
 * different facts, and a table that renders them the same is a table that misleads. One decimal
 * rather than a whole number because the point of the block is comparing its rows: at a top of 25 a
 * mean of 8 and a mean of 8.4 are a real difference.
 *
 * <p><b>The widths are derived from the cells</b>, exactly as {@code CensusReport} derives its
 * column, so a five-figure pool moves the column rather than jutting out of it and no number here
 * is a constant somebody has to keep.
 */
public final class EvaluationReport {

  /** Said on the first line, every time — what this is, and what it is not. */
  public static final String HEADER =
      "# segue recommender evaluation — aggregates only: no labels, no ids, no notes, no ratings"
          + " (ADR 51, ADR 63, ADR 65).";

  /** Printed where a mean has nothing to average. */
  public static final String NO_MEAN = "-";

  private static final List<String> COLUMNS =
      List.of(
          "scorer", "floor", "pool", "held out", "hits", "mean rank", "negatives", "neg mean rank");

  private static final String GAP = "  ";

  private EvaluationReport() {}

  /**
   * Render the whole block, header included.
   *
   * @param eligible how many entities could have been held out — the split's denominator, the only
   *     fact about the split this method needs
   * @param heldOutCount how many of those were actually held out
   * @param top how many candidates each setting was read over
   * @param readings one per setting, in the order they should be read
   */
  public static List<String> lines(
      int eligible, int heldOutCount, int top, List<Reading> readings) {
    Objects.requireNonNull(readings, "readings");

    List<List<String>> rows = new ArrayList<>();
    rows.add(COLUMNS);
    readings.forEach(reading -> rows.add(cells(reading)));
    int[] widths = widths(rows);

    List<String> rendered = new ArrayList<>();
    rendered.add(HEADER);
    rendered.add(
        "# held out every "
            + HeldOut.EVERY
            + " of "
            + eligible
            + " eligible entity(ies): "
            + heldOutCount
            + " held out, "
            + (eligible - heldOutCount)
            + " left on the known-list.");
    rendered.add("# top " + top + " per setting, over " + readings.size() + " setting(s).");
    rows.forEach(row -> rendered.add(render(row, widths)));
    return List.copyOf(rendered);
  }

  private static List<String> cells(Reading reading) {
    return List.of(
        reading.setting().scorer().spelling(),
        String.valueOf(reading.setting().floor()),
        String.valueOf(reading.pool()),
        String.valueOf(reading.heldOutInPool()),
        String.valueOf(reading.hits()),
        mean(reading.meanHitRank()),
        String.valueOf(reading.negativesOffered()),
        mean(reading.meanNegativeRank()));
  }

  private static String mean(OptionalDouble value) {
    return value.isPresent() ? String.format(Locale.ROOT, "%.1f", value.getAsDouble()) : NO_MEAN;
  }

  private static int[] widths(List<List<String>> rows) {
    int[] widths = new int[COLUMNS.size()];
    for (List<String> row : rows) {
      for (int column = 0; column < widths.length; column++) {
        widths[column] = Math.max(widths[column], row.get(column).length());
      }
    }
    return widths;
  }

  /** The first column is a word and is left-aligned; every other is a number and is not. */
  private static String render(List<String> row, int[] widths) {
    StringBuilder line = new StringBuilder();
    for (int column = 0; column < widths.length; column++) {
      String cell = row.get(column);
      String padding = " ".repeat(widths[column] - cell.length());
      if (column > 0) {
        line.append(GAP);
      }
      line.append(column == 0 ? cell + padding : padding + cell);
    }
    return line.toString();
  }
}
