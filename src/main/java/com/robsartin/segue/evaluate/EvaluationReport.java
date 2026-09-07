package com.robsartin.segue.evaluate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Readings in, one aligned block of text out. A pure function, and the only class here that decides
 * what a person sees (ADR 65).
 *
 * <p><b>Every value is an integer, a fixed one-decimal, or the literal {@link #NO_MEAN}, and every
 * label is a literal in this file or a {@code Scorer} spelling.</b> That is what makes the whole
 * output safe to paste and what {@code EvaluationIsSafeToPasteTest} asserts — the same property
 * {@code CensusReport} has and ADR 63 argues for. No qid, label, note or rating value reaches this
 * method at all — and that is true of the whole signature, not just {@link Reading}'s shape: {@link
 * #lines} takes four plain counts and a top instead of the {@code HeldOut} that produced them,
 * deliberately narrower than the plan first drafted, because a type that carries a qid list and a
 * qid-keyed map has somewhere to put one even when this method never reads it.
 *
 * <p><b>A mean over nothing is a dash rather than zero.</b> No hits and a mean rank of zero are
 * different facts, and a table that renders them the same is a table that misleads. One decimal
 * rather than a whole number because the point of the block is comparing its rows: at a top of 25 a
 * mean of 8 and a mean of 8.4 are a real difference. The division happens here and nowhere else:
 * {@link Reading} carries the rank sum and the count, so a row summed over the folds of the split
 * is meaned over every hit in the run rather than over a mean of means (issue #268).
 *
 * <p><b>The widths are derived from the cells</b>, exactly as {@code CensusReport} derives its
 * column, so a five-figure pool moves the column rather than jutting out of it and no number here
 * is a constant somebody has to keep.
 *
 * <p><b>The split line states folds</b> (issue #268). The folds partition the eligible population,
 * so the total held out equals the denominator beside it and a reader can see the two agree; what
 * each fold leaves on the known-list differs by one between folds, so the line states the smallest
 * — the worst case for what the recommender had to learn from — rather than a number that is right
 * for some folds only. The fold count is passed rather than read off {@code HeldOut.EVERY}, because
 * how many folds were read is a fact about the run rather than an assumption this class may make.
 *
 * <p><b>An instant given at all adds one line and four columns, never fewer</b> (issue #276). The
 * split line sits between the held-out line and the top line, stating the instant and both halves
 * of the eligible population; every row gains {@link #SPLIT_COLUMNS}' four cells, appended after
 * the eight above so a split row's first eight render exactly as the same row does unsplit. Two
 * guards keep the instant and the readings from disagreeing about whether this run was split at all
 * — see {@link #lines}'s own checks — because a table stating a division some rows do not carry
 * would be read as though every row carried it.
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
          "scorer", "floor", "pool", "in pool", "hits", "mean rank", "negatives", "neg mean rank");

  private static final String GAP = "  ";

  /**
   * The four cells the age split adds, <b>appended</b> so the eight above keep their positions and
   * their widths: a split row's first eight columns render byte for byte as the same row does
   * unsplit, which is what keeps every reading on the record comparable (issue #276).
   */
  private static final List<String> SPLIT_COLUMNS =
      Stream.concat(
              COLUMNS.stream(), Stream.of("old in pool", "old hits", "new in pool", "new hits"))
          .toList();

  private EvaluationReport() {}

  /**
   * Render the whole block, header included.
   *
   * @param eligible how many entities could have been held out — the split's denominator
   * @param folds how many folds of that split were read
   * @param heldOutTotal how many entities were held out over all of them
   * @param leastLeft the fewest left on the known-list in any one fold
   * @param top how many candidates each setting was read over
   * @param since the instant the halves were drawn at, or empty when none was given
   * @param oldHeldOut how many of the eligible population were rated before it, summed over the
   *     folds
   * @param newHeldOut how many of the eligible population were rated on or after it, summed over
   *     the folds
   * @param readings one per setting, in the order they should be read
   */
  public static List<String> lines(
      int eligible,
      int folds,
      int heldOutTotal,
      int leastLeft,
      int top,
      Optional<Instant> since,
      int oldHeldOut,
      int newHeldOut,
      List<Reading> readings) {
    Objects.requireNonNull(since, "since");
    Objects.requireNonNull(readings, "readings");

    boolean split = since.isPresent();
    if (!split && (oldHeldOut != 0 || newHeldOut != 0)) {
      throw new IllegalArgumentException(
          "no instant was given, so there are no halves to state: a split line naming a division"
              + " nothing made is a line a reader would believe");
    }
    // Both guards above read readings.stream(), so an EMPTY readings list makes anyMatch vacuously
    // false and neither can fire: a present since would then render a split header over zero data
    // rows, with nothing to disagree with it. Left alone rather than guarded, because it is
    // unreachable from either caller — EvaluateRun always passes one Reading per Setting.GRID
    // entry, and GRID is a fixed, non-empty cross product (Scorer.values() x Setting.FLOORS) — and
    // because reaching it renders a valid-looking, merely rowless block rather than crashing, which
    // is no worse than this method's pre-existing empty-readings behaviour on the unsplit path.
    if (readings.stream().anyMatch(reading -> reading.halves().split() != split)) {
      throw new IllegalArgumentException(
          "the instant and the readings disagree about whether this run was split by rating age:"
              + " one run is split or it is not, and every setting is read the same way");
    }

    List<String> columns = split ? SPLIT_COLUMNS : COLUMNS;
    List<List<String>> rows = new ArrayList<>();
    rows.add(columns);
    readings.forEach(reading -> rows.add(cells(reading)));
    int[] widths = widths(rows, columns.size());

    List<String> rendered = new ArrayList<>();
    rendered.add(HEADER);
    rendered.add(
        "# held out every "
            + HeldOut.EVERY
            + " of "
            + eligible
            + " eligible entity(ies), in "
            + folds
            + " fold(s): "
            + heldOutTotal
            + " held out over all folds, at least "
            + leastLeft
            + " left on the known-list in each.");
    since.ifPresent(instant -> rendered.add(ageLine(instant, oldHeldOut, newHeldOut)));
    rendered.add("# top " + top + " per setting, over " + readings.size() + " setting(s).");
    rows.forEach(row -> rendered.add(render(row, widths)));
    return List.copyOf(rendered);
  }

  /**
   * <b>The instant is rendered from the parsed value, never from the string the operator typed.</b>
   * {@code Instant.toString()} can only produce digits, {@code -}, {@code :}, {@code .}, {@code T}
   * and {@code Z}, so the one operator-supplied fact in the whole block cannot carry an identifier
   * into it however the flag was spelled — which is what keeps {@code
   * EvaluationIsSafeToPasteTest}'s claim true of this line as well as of the table.
   *
   * <p>The last-write clause is here rather than in the guide alone because the number beside it is
   * misread without it: {@code updatedAt} is when the rating last changed, so a promotion rated
   * years ago and re-rated after the instant is counted as new (ADR 39).
   */
  private static String ageLine(Instant since, int oldHeldOut, int newHeldOut) {
    return "# split by rating age at "
        + since
        + ": "
        + oldHeldOut
        + " old (rated before it), "
        + newHeldOut
        + " new (rated on or after it) — a rating's timestamp is its last write, so a re-rated old"
        + " promotion counts as new.";
  }

  private static List<String> cells(Reading reading) {
    List<String> whole =
        List.of(
            reading.setting().scorer().spelling(),
            String.valueOf(reading.setting().floor()),
            String.valueOf(reading.pool()),
            String.valueOf(reading.heldOutInPool()),
            String.valueOf(reading.hits()),
            mean(reading.hitRankSum(), reading.hits()),
            String.valueOf(reading.negativesOffered()),
            mean(reading.negativeRankSum(), reading.negativesOffered()));
    if (!reading.halves().split()) {
      return whole;
    }
    Halves halves = reading.halves();
    return Stream.concat(
            whole.stream(),
            Stream.of(
                String.valueOf(halves.oldInPool()),
                String.valueOf(halves.oldHits()),
                String.valueOf(halves.newInPool()),
                String.valueOf(halves.newHits())))
        .toList();
  }

  /** A mean over nothing is the dash rather than zero, and the count is what says which. */
  private static String mean(int rankSum, int count) {
    return count == 0 ? NO_MEAN : String.format(Locale.ROOT, "%.1f", (double) rankSum / count);
  }

  private static int[] widths(List<List<String>> rows, int columns) {
    int[] widths = new int[columns];
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
