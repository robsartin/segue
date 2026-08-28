package com.robsartin.segue.recommend;

import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.Recommendation;
import com.robsartin.segue.domain.Scorer;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Candidates in, one readable file out. A pure function of its arguments, and the only class here
 * that decides what a person sees (ADR 45).
 *
 * <p><b>The file names itself as personal data on its first line.</b> The output is derived from
 * the known-list, which is exactly what ADR 33 governs and what ADR 40 keeps out of this public
 * repository; a recommendation list is that list plus what the graph makes of it. {@code *.txt} is
 * gitignored, and this header is the lock aimed at the case a gitignore cannot reach — a file
 * copied, pasted or attached somewhere else. It is the same three-lock argument ADR 43 makes for
 * the ratings listing, and the same first line.
 *
 * <p><b>Every candidate carries its routes, and one that has none says so.</b> A score with no
 * receipts is not a segue recommendation; a silent gap where an explanation should be would be
 * indistinguishable from a formatting bug. Honest rather than helpful, the way ADR 41's DOT tooltip
 * falls back to a bare QID.
 *
 * <p>The routes are rendered by {@code PathResult.render()} — the same rendering the rest of the
 * project uses for an explanation, citations included.
 */
public final class RecommendationReport {

  /** Said on the first line of every file this writes. */
  public static final String PERSONAL_DATA_HEADER =
      "# segue recommendations — derived from your known-list, and personal data under ADR 33 and"
          + " issue #37. Keep this file outside the working tree and out of version control: this"
          + " repository is public.";

  /** Printed under a candidate the traversal could not reach within the bound. */
  public static final String NO_ROUTE =
      "(no route within " + Routes.MAX_HOPS + " hops — the graph moved under the scan)";

  /** Printed instead of a list when nothing survived the filters. */
  public static final String NOTHING_FOUND =
      "# nothing to recommend: no entity outside your list survived the filters.";

  private RecommendationReport() {}

  /**
   * Write the whole file, header included.
   *
   * @param sweep what the pass over the graph looked at, for the header's counts
   * @param explained the ranked candidates, in the order they should be read
   * @param scorer where on the spectrum this run sat, named so a file can be compared with another
   * @param minDegree the floor this run applied, named for the same reason
   */
  public static void write(
      Sweep sweep, List<Explained> explained, Scorer scorer, int minDegree, Writer out)
      throws IOException {
    Objects.requireNonNull(sweep, "sweep");
    Objects.requireNonNull(explained, "explained");
    Objects.requireNonNull(scorer, "scorer");
    Objects.requireNonNull(out, "out");

    out.write(PERSONAL_DATA_HEADER);
    out.write("\n# ");
    out.write(
        explained.size()
            + " of "
            + sweep.candidates().size()
            + " candidate(s), from "
            + sweep.knownFound()
            + " entity(ies) you already know");
    if (sweep.knownMissing() > 0) {
      out.write(" (" + sweep.knownMissing() + " of your list is not in this graph)");
    }
    out.write(".\n# scored by ");
    out.write(scorer.spelling());
    out.write(" — ");
    out.write(scorer.describe());
    out.write(".\n# ");
    out.write(
        "candidates need at least "
            + minDegree
            + " edges; "
            + sweep.hubIntermediatesExcluded()
            + " hub intermediate(s) were excluded rather than discounted (issues #52 and #66).");
    out.write("\n\n");

    if (explained.isEmpty()) {
      out.write(NOTHING_FOUND);
      out.write("\n");
      return;
    }

    int rank = 1;
    for (Explained candidate : explained) {
      out.write(heading(rank++, candidate.candidate()));
      if (candidate.routes().isEmpty()) {
        out.write("      " + NO_ROUTE + "\n");
      }
      for (PathResult route : candidate.routes()) {
        out.write(startsFrom(route));
        out.write(route.render());
        out.write("\n");
      }
      out.write("\n");
    }
  }

  /**
   * Which of your entities this route runs from.
   *
   * <p>A rendered hop reads in whichever direction the source stated the relationship — "U2
   * &lt;-[INFLUENCED_BY]- the candidate" is a route that starts at U2 — so the one thing a reader
   * cannot work out from the hops alone is which end is theirs. The first hop's origin is it: a
   * route is built from a known entity outwards.
   */
  private static String startsFrom(PathResult route) {
    NodeRecord start = route.hops().get(0).from();
    return "      from " + start.label() + " (" + start.qid() + "):\n";
  }

  /** Rank, score, who it is, how big it is, and how much of your list reaches it. */
  private static String heading(int rank, Recommendation candidate) {
    return String.format(
        Locale.ROOT,
        "%3d. %.4f  %s (%s) — %s, %d edges, %d of yours through %d shared intermediate(s)%n",
        rank,
        candidate.score(),
        candidate.entity().label(),
        candidate.entity().qid(),
        candidate.entity().kind(),
        candidate.degree(),
        candidate.knownReached(),
        candidate.intermediates());
  }
}
