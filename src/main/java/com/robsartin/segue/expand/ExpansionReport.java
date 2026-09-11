package com.robsartin.segue.expand;

import com.robsartin.segue.expansion.ExpansionOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A tally in, one aligned block of text out. A pure function, and the only class here that decides
 * what a person sees (ADR 51, ADR 63) — {@code CensusReport}'s and {@code EvaluationReport}'s
 * shape, held to it for the same reason: {@link #lines} takes an {@link ExpansionTally} whose every
 * component is an {@code int} or a map keyed by {@code SourceAdapter#id()} or {@link
 * ExpansionOutcome.Reason}, and {@link #dryRunLines} takes a {@link Preflight} of three {@code
 * int}s. Both arities also take an {@code Optional<RatedSince>}, whose own two components are an
 * {@code Instant} and an {@code int}. There is nowhere in any of it to put an identifier, which is
 * a stronger guarantee than a body that merely happens not to print one.
 *
 * <p><b>Every section prints its heading, whether or not there is a row to show under it.</b> An
 * empty {@code edge assertions by source} means no edge assertion was recorded from any source, and
 * a reader has to be able to tell that from the section simply being gone — the same distinction
 * {@code CensusReport} does not have to draw, because its sections are never empty on a real graph.
 * Applied uniformly here rather than only where the issue names it, so the rule is one rule and not
 * a per-section judgement call.
 *
 * <p><b>Widths are derived from the whole block</b>, {@code CensusReport}'s rule: labels padded to
 * the widest counted label anywhere in the document, counts right-aligned to the widest count
 * anywhere in it — one column each, so a six-figure count moves the column rather than jutting out
 * of it, and neither width is a constant somebody has to keep in step with the labels.
 */
public final class ExpansionReport {

  /** Said on the first line of a real run, every time — what this is, and what it is not. */
  public static final String HEADER =
      "# segue promotion expansion — aggregates only: no labels, no notes, no entity ids (ADR 51,"
          + " ADR 63).";

  /**
   * Said on the first line of a dry run — nothing below it was appended. Not "nothing was written":
   * both {@code SqliteAssertionLog} and {@code SqliteAffinityStore} run {@code CREATE TABLE IF NOT
   * EXISTS} on open, dry run or not, so a database missing a table gets one and the file's journal
   * is touched either way (ADR 66). What a dry run guarantees is the append — no assertion, no
   * rating — and that is what this line says.
   */
  public static final String DRY_RUN_HEADER =
      "# segue promotion expansion — dry run: appends nothing. Aggregates only"
          + " (ADR 51, ADR 63).";

  /**
   * The {@code graph} section's second label, and the one the runbook cites (#293).
   *
   * <p><b>It counts assertions, not edges.</b> {@code EntityExpansion} increments once per edge
   * assertion it records, and an edge the graph already holds is recorded again — corroboration and
   * freshness are what that is for (ADR 19). So this row stands above the graph's net gain, which
   * {@code graphCensus} is the authority on and this tool never sees.
   *
   * <p>{@code nodes added} beside it <i>is</i> a net count. After #293 these two labels are the
   * only thing that says which of the two the reader is looking at.
   *
   * <p><b>A constant rather than a second literal.</b> This label is said outside this block: the
   * developer guide's runbook row for {@code claims} / log rows cites it, and so does ADR 66's
   * amendment for #293.
   *
   * <p>{@code DeveloperGuideExpandPromotionsExamplesTest} reads it from here, so the row and the
   * printed line cannot drift apart. {@code ExpansionReportTest}'s golden block still pins the text
   * itself as a literal, exactly as it pins {@link #HEADER}: reading the pin off the constant it is
   * meant to pin would prove nothing.
   */
  public static final String EDGE_ASSERTIONS_RECORDED = "edge assertions recorded";

  private static final String GAP = "  ";

  private ExpansionReport() {}

  /** One entry in the block: a section heading, an indented sub-heading, or a counted row. */
  private sealed interface Entry {}

  /** A top-level section — printed after a blank line, never indented, never counted. */
  private record Section(String heading) implements Entry {}

  /** A heading nested one level under a section — indented two spaces, never counted. */
  private record SubHeading(String heading) implements Entry {}

  /** A counted line. {@code label} already carries its own indentation. */
  private record Row(String label, int count) implements Entry {}

  /** Render the whole block, header included. */
  public static List<String> lines(ExpansionTally tally) {
    return lines(tally, Optional.empty());
  }

  /** Render the whole block, header included, saying which population it covered. */
  public static List<String> lines(ExpansionTally tally, Optional<RatedSince> filter) {
    Objects.requireNonNull(filter, "filter");
    return render(HEADER, filter, body(tally));
  }

  /** Render what a dry run would visit — nothing else, because nothing else happened. */
  public static List<String> dryRunLines(Preflight preflight) {
    return dryRunLines(preflight, Optional.empty());
  }

  /** Render what a dry run would visit, saying which population it would have covered. */
  public static List<String> dryRunLines(Preflight preflight, Optional<RatedSince> filter) {
    Objects.requireNonNull(filter, "filter");
    return render(DRY_RUN_HEADER, filter, dryRunBody(preflight));
  }

  private static List<Entry> dryRunBody(Preflight preflight) {
    return List.of(
        new Section("promotions"),
        new Row("  considered", preflight.considered()),
        new Row("  in the graph", preflight.inTheGraph()),
        new Row("  minted", preflight.minted()));
  }

  private static List<Entry> body(ExpansionTally tally) {
    List<Entry> body = new ArrayList<>();

    body.add(new Section("promotions"));
    body.add(new Row("  considered", tally.considered()));
    body.add(new Row("  expanded", tally.expanded()));
    body.add(new Row("  added nothing", tally.addedNothing()));
    body.add(new Row("  refused", refusedTotal(tally)));
    body.add(new Row("  failed", tally.failed()));

    body.add(new Section("graph"));
    body.add(new Row("  nodes added", tally.nodesAdded()));
    body.add(new Row("  " + EDGE_ASSERTIONS_RECORDED, tally.edgesAdded()));

    body.add(new Section("edge assertions by source"));
    tally.edgesBySource().forEach((source, n) -> body.add(new Row("  " + source, n)));

    body.add(new Section("shortfalls"));
    body.add(new Row("  neighbours skipped", tally.skippedNeighbors()));
    body.add(new Row("  endpoints refused", tally.refusedEndpoints()));
    body.add(new Row("  bound cut the result", tally.boundCut()));
    body.add(new SubHeading("unavailable"));
    tally.unavailableBySource().forEach((source, n) -> body.add(new Row("    " + source, n)));
    body.add(new SubHeading("truncated"));
    tally.truncatedBySource().forEach((source, n) -> body.add(new Row("    " + source, n)));

    body.add(new Section("refused, by reason"));
    tally.refusalsByReason().forEach((reason, n) -> body.add(new Row("  " + label(reason), n)));

    return body;
  }

  /**
   * Sums {@link ExpansionTally#refusalsByReason()} rather than carrying a separate field — {@code
   * considered == expanded + refused + failed} is then an identity the tally's own shape enforces
   * rather than a second count that could drift from the map it is a sum of.
   */
  private static int refusedTotal(ExpansionTally tally) {
    return tally.refusalsByReason().values().stream().mapToInt(Integer::intValue).sum();
  }

  private static String label(ExpansionOutcome.Reason reason) {
    return switch (reason) {
      case UNKNOWN_ENTITY -> "unknown entity";
      case LOCAL_ENTITY -> "local entity";
      case BOUND_NOT_POSITIVE -> "bound not positive";
    };
  }

  /**
   * Said under the header when a filter was applied, and not at all when none was.
   *
   * <p><b>A clause rather than a counted row.</b> A row would print on every run, and on a run with
   * no instant it would read {@code excluded 0} — a count of a filter nobody applied. It would also
   * widen the label column of every block by two characters for a number that is usually zero.
   * Keeping the block with no instant byte-identical is what ADR 65's 2026-09-06 amendment does for
   * its own report, and for the same reason: every block already on record stays comparable.
   *
   * <p>The last-write clause is here rather than in the guide alone because the number beside it is
   * misread without it: {@code updated_at} is when the rating last changed, so a promotion rated
   * years ago and re-rated after the instant is expanded again (ADR 39).
   */
  private static String sinceLine(RatedSince filter) {
    return "# only promotions rated on or after "
        + filter.since()
        + ": "
        + filter.excluded()
        + " excluded (rated before it) — a rating's timestamp is its last write, so a re-rated"
        + " old promotion counts as new.";
  }

  private static List<String> render(String header, Optional<RatedSince> filter, List<Entry> body) {
    int labelWidth = 0;
    int countWidth = 0;
    for (Entry entry : body) {
      if (entry instanceof Row row) {
        labelWidth = Math.max(labelWidth, row.label().length());
        countWidth = Math.max(countWidth, String.valueOf(row.count()).length());
      }
    }

    List<String> rendered = new ArrayList<>();
    rendered.add(header);
    filter.ifPresent(f -> rendered.add(sinceLine(f)));
    for (Entry entry : body) {
      switch (entry) {
        case Section section -> {
          rendered.add("");
          rendered.add(section.heading());
        }
        case SubHeading subHeading -> rendered.add("  " + subHeading.heading());
        case Row row -> {
          String count = String.valueOf(row.count());
          rendered.add(
              row.label()
                  + " ".repeat(labelWidth - row.label().length())
                  + GAP
                  + " ".repeat(countWidth - count.length())
                  + count);
        }
      }
    }
    return List.copyOf(rendered);
  }
}
