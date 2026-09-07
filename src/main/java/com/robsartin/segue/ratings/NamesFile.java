package com.robsartin.segue.ratings;

import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The promotions as a plain list of names, which is the shape the upload wants (#285).
 *
 * <p><b>It sorts as well as renders, for {@link RatingsTable}'s reason:</b> the header states what
 * the file is and how much of it there is, and a writer that announced an ordering somebody else
 * had applied could be made to lie by one refactor.
 *
 * <p><b>Labels and nothing else.</b> No qid column, no rating, no note. The upload wants names; a
 * second column would make this a different kind of file, and a note in it would be free text
 * leaving the machine (ADR 33 as amended by issue #85).
 *
 * <p><b>A promotion the graph cannot name is written as its qid</b>, not skipped and not written as
 * {@code AffinityRow.NO_LABEL}. Skipping it would drop something the owner said yes to out of the
 * file that exists to carry it, leaving a count as the only trace. {@code (not in the graph)} is a
 * phrase for a person reading a table; this file is pasted into a search box, so it would be
 * searched for as an artist. The qid identifies the row and is wrong in an obvious way rather than
 * a quiet one — ADR 43's "honest rather than helpful", reached from the same direction. {@link
 * #write} returns how many there were so the caller can say so in a log line; ADR 39 requires an
 * entity to be in the graph before it can be rated, so the number should be zero, and it is
 * reported rather than assumed.
 *
 * <p><b>Absent means null, exactly as {@link AffinityRow#displayLabel} decides it.</b> A label the
 * log claimed as null is the same "the graph cannot name this" as a qid the log never mentioned,
 * and the listing and this file must not disagree about which rows those are.
 *
 * <p><b>The one header line is a comment by convention only, and it stays.</b> Setlist Scout's bulk
 * uploader skips lines beginning with {@code #} ({@code ArtistImportService} and {@code
 * ArtistSeedService}, issue #177 there), so the runbook documents that the upload ignores it rather
 * than telling the owner to strip it. It stays because {@code *.txt} being gitignored is the second
 * lock and this is the third (ADR 43), and a file of names with no provenance is exactly the one
 * that gets attached to an issue.
 *
 * <p><b>{@code CensusIsSafeToPasteTest}'s discipline does not apply here and must not be added by
 * analogy.</b> That property exists because the census and the evaluation report are meant to be
 * pasted into a public issue. This file is personal data that goes to one private upload form and
 * nowhere else; "safe to paste" is not a property it has or wants.
 */
final class NamesFile {

  /** Said on the first line of every file this writes, followed by the count on the same line. */
  static final String PERSONAL_DATA_HEADER =
      "# segue promotions off your known list — personal data under ADR 33 and issue #37. Keep this"
          + " file outside the working tree and out of version control: this repository is public.";

  /** One line of the file, and the qid behind it so the ordering has a total tiebreak. */
  private record Named(String name, String qid, boolean fromTheGraph) {}

  private NamesFile() {}

  /**
   * Write the header and one name per line, sorted.
   *
   * @return how many of {@code promotions} the graph could not name, and so were written as their
   *     qid — the count the caller logs, since no log line may carry the name itself (ADR 33)
   */
  static int write(List<String> promotions, Map<String, String> labels, Writer out)
      throws IOException {
    Objects.requireNonNull(promotions, "promotions");
    Objects.requireNonNull(labels, "labels");
    Objects.requireNonNull(out, "out");

    // compareTo, not a Collator: SortOrder's comparators end in qid so that two runs over an
    // unchanged table produce byte-identical files, and the same argument applies to this file. A
    // case-insensitive order is not total on its own, so it would need the code-point comparison as
    // a second tiebreak anyway, and the audience for this file is an uploader rather than a reader.
    List<Named> named =
        promotions.stream()
            .map(qid -> nameOf(qid, labels))
            .sorted(Comparator.comparing(Named::name).thenComparing(Named::qid))
            .toList();

    out.write(PERSONAL_DATA_HEADER);
    out.write(" " + named.size() + " name(s), one per line.\n");
    for (Named one : named) {
      out.write(one.name());
      out.write("\n");
    }
    return (int) named.stream().filter(one -> !one.fromTheGraph()).count();
  }

  private static Named nameOf(String qid, Map<String, String> labels) {
    String label = labels.get(qid);
    return label == null ? new Named(qid, qid, false) : new Named(label, qid, true);
  }
}
