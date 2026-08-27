package com.robsartin.segue.ratings;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Objects;

/**
 * Rows in, one aligned text table out. A pure function of its arguments, and the only class here
 * that decides what a person sees.
 *
 * <p><b>It sorts as well as renders, on purpose.</b> The header states the ordering, and a writer
 * that announced an ordering somebody else had applied could be made to lie by one refactor. The
 * two are one concern - how this reads - and they are in one class so they cannot drift apart.
 *
 * <p><b>The file names itself as personal data on its first line.</b> {@code *.txt} is gitignored
 * beside {@code *.db}, {@code *.csv}, {@code *.dot} and {@code *.graphml}, and that is the second
 * lock (ADR 41's phrase). This header is a third one, aimed at the case the other two miss: a file
 * copied somewhere else, pasted into a chat, or attached to an issue still says what it is. It
 * costs two lines.
 *
 * <p>Plain text rather than CSV, because the question is one a person asks and reads the answer to.
 * The columns are padded to the widest value so the ratings line up in a column when sorted by
 * rating, and the note comes last, unpadded, because it is the only field with no bound on its
 * length.
 */
public final class RatingsTable {

  /** Said on the first line of every file this writes. */
  public static final String PERSONAL_DATA_HEADER =
      "# segue ratings — personal data under ADR 33 and issue #37. Keep this file outside the"
          + " working tree and out of version control: this repository is public.";

  private static final String NOTHING_RATED = "# no ratings recorded yet.";

  private static final String RATING = "rating";
  private static final String LABEL = "label";
  private static final String UPDATED = "updated";
  private static final String QID = "qid";
  private static final String NOTE = "note";

  /** Two spaces, so a column boundary is unambiguous even when a value is exactly the width. */
  private static final String GAP = "  ";

  private RatingsTable() {}

  /** Sort {@code rows} by {@code sort} and write the whole file, header included. */
  public static void write(List<AffinityRow> rows, SortOrder sort, Writer out) throws IOException {
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(sort, "sort");
    Objects.requireNonNull(out, "out");

    out.write(PERSONAL_DATA_HEADER);
    out.write("\n# ");
    out.write(rows.size() + " rating(s), sorted by " + sort.describe() + ".");
    out.write("\n\n");

    if (rows.isEmpty()) {
      out.write(NOTHING_RATED);
      out.write("\n");
      return;
    }

    List<AffinityRow> ordered = rows.stream().sorted(sort.comparator()).toList();
    int ratingWidth = RATING.length();
    int labelWidth = widest(LABEL, ordered.stream().map(AffinityRow::displayLabel).toList());
    int updatedWidth =
        widest(UPDATED, ordered.stream().map(row -> row.updatedAt().toString()).toList());
    int qidWidth = widest(QID, ordered.stream().map(AffinityRow::qid).toList());

    out.write(
        row(
            pad(RATING, ratingWidth),
            pad(LABEL, labelWidth),
            pad(UPDATED, updatedWidth),
            pad(QID, qidWidth),
            NOTE));
    out.write(
        row(
            dashes(ratingWidth),
            dashes(labelWidth),
            dashes(updatedWidth),
            dashes(qidWidth),
            dashes(NOTE.length())));

    for (AffinityRow rated : ordered) {
      out.write(
          row(
              pad(Integer.toString(rated.rating()), ratingWidth),
              pad(rated.displayLabel(), labelWidth),
              pad(rated.updatedAt().toString(), updatedWidth),
              pad(rated.qid(), qidWidth),
              rated.displayNote()));
    }
  }

  private static String row(String rating, String label, String updated, String qid, String note) {
    return (rating + GAP + label + GAP + updated + GAP + qid + GAP + note).stripTrailing() + "\n";
  }

  private static int widest(String header, List<String> values) {
    return values.stream().mapToInt(String::length).reduce(header.length(), Math::max);
  }

  private static String pad(String value, int width) {
    return value + " ".repeat(width - value.length());
  }

  private static String dashes(int width) {
    return "-".repeat(width);
  }
}
