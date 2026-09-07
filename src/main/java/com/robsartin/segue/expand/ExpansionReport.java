package com.robsartin.segue.expand;

import com.robsartin.segue.expansion.ExpansionOutcome;
import java.util.ArrayList;
import java.util.List;

/**
 * A tally in, one aligned block of text out. A pure function, and the only class here that decides
 * what a person sees (ADR 51, ADR 63) — {@code CensusReport}'s and {@code EvaluationReport}'s
 * shape, held to it for the same reason: {@link #lines} takes an {@link ExpansionTally} whose every
 * component is an {@code int} or a map keyed by {@code SourceAdapter#id()} or {@link
 * ExpansionOutcome.Reason}, and {@link #dryRunLines} takes a {@link Preflight} of three {@code
 * int}s. There is nowhere in either signature to put an identifier, which is a stronger guarantee
 * than a body that merely happens not to print one.
 *
 * <p><b>Every section prints its heading, whether or not it has a row to show.</b> An empty {@code
 * edges by source} means no edge was recorded from any source, and a reader has to be able to tell
 * that from the section simply being gone — the same distinction {@code CensusReport} does not have
 * to draw, because its sections are never empty on a real graph. Applied uniformly here rather than
 * only where the issue names it, so the rule is one rule and not a per-section judgement call.
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
    List<Entry> body = body(tally);
    return render(HEADER, body);
  }

  /** Render what a dry run would visit — nothing else, because nothing else happened. */
  public static List<String> dryRunLines(Preflight preflight) {
    List<Entry> body =
        List.of(
            new Section("promotions"),
            new Row("  considered", preflight.considered()),
            new Row("  in the graph", preflight.inTheGraph()),
            new Row("  minted", preflight.minted()));
    return render(DRY_RUN_HEADER, body);
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
    body.add(new Row("  edges added", tally.edgesAdded()));

    body.add(new Section("edges by source"));
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

  private static List<String> render(String header, List<Entry> body) {
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
