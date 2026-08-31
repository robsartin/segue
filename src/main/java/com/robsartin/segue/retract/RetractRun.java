package com.robsartin.segue.retract;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.OwnerEdge;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.domain.Retractions;
import com.robsartin.segue.domain.SameAs;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AssertionLog;
import com.robsartin.segue.retract.RetractCli.Options;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Read, report, then append - in that order, and the order is the design.
 *
 * <p><b>What it does not do is the point.</b> Nothing is deleted, nothing is edited, and the log
 * this class was handed is exactly one row longer when it returns (ADR 19, ADR 44). The claims the
 * retraction reaches are still in the log afterwards; they simply stop reaching the projection,
 * which both {@code GraphProjector} and {@code LogProjection} work out for themselves from the
 * shared rule in {@link Retractions}.
 *
 * <p><b>The report comes before the append.</b> {@code ExportRun} established that, and the reason
 * is stronger here: an export leaves a file to inspect, and this leaves a permanent row in a log
 * that is never edited. So the operator learns the entity's <em>label</em> and how many claims will
 * stop projecting while the log is still untouched. Naming the label is the safety feature that
 * matters: issue #68 exists because a QID turned out not to be the entity somebody thought it was,
 * and retracting the wrong QID is that same mistake one level up.
 *
 * <p><b>Nothing to retract is refused, not recorded.</b> A mistyped QID would otherwise append a
 * retraction that does nothing, permanently, in a log nobody can edit - a row that reads like a
 * decision somebody made. The same refusal covers retracting something already retracted.
 *
 * <p>Notes go to a {@link Consumer} rather than to a logger of this class's own, so their ordering
 * against the append is observable from a test rather than inferred from the reading order of this
 * method. {@link RetractCli} supplies {@code log::info}.
 */
public final class RetractRun {

  /** What a retraction will take out of the projection, reported before it is appended. */
  public record Effect(String qid, String label, int nodeClaims, int edgeClaims) {

    public Effect {
      Objects.requireNonNull(qid, "qid");
      Objects.requireNonNull(label, "label");
    }

    int total() {
      return nodeClaims + edgeClaims;
    }
  }

  private final AssertionLog log;
  private final Clock clock;

  public RetractRun(AssertionLog log, Clock clock) {
    this.log = Objects.requireNonNull(log, "log");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Retract one entity.
   *
   * @return what the retraction reached, so a caller can assert on it without re-reading the log
   */
  public Effect run(Options options, Consumer<String> notes) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(notes, "notes");

    Effect effect = measure(options.qid());
    if (effect.total() == 0) {
      throw new IllegalArgumentException(
          "nothing about "
              + options.qid()
              + " is in the projection — check the qid, or it may already be retracted");
    }

    notes.accept(
        "retracting "
            + effect.qid()
            + " \""
            + effect.label()
            + "\": "
            + effect.nodeClaims()
            + " node claim(s) and "
            + effect.edgeClaims()
            + " edge claim(s) will stop projecting");
    notes.accept(
        "the log keeps every one of them — a retraction is a new claim, not a deletion (ADR 44)");

    if (options.dryRun()) {
      notes.accept("dry run: nothing was appended");
      return effect;
    }

    IngestService.retract(log, new Retraction(options.qid(), options.reason(), clock.instant()));
    notes.accept(
        "appended. The running graph is rebuilt from the log at the next boot (ADR 24), so a"
            + " server that is up still holds the old edges until it restarts");
    return effect;
  }

  /**
   * What the projection currently holds about {@code qid}.
   *
   * <p>Counted through the same {@link Retractions} both projections use, so the numbers reported
   * here are the numbers that will actually change - a claim an earlier retraction already reached
   * is not counted twice.
   */
  private Effect measure(String qid) {
    List<LoggedAssertion> logged = log.readAll();
    Retractions retractions = Retractions.in(logged);

    // Last claim wins, matching upsertNode and both folds: the label reported is the one the
    // projection is showing right now, not the first one anybody recorded.
    String label = "(no node claim in the projection)";
    int nodeClaims = 0;
    int edgeClaims = 0;

    for (int i = 0; i < logged.size(); i++) {
      LoggedAssertion assertion = logged.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      switch (assertion) {
        case NodeAssertion node -> {
          if (node.qid().equals(qid)) {
            label = node.label();
            nodeClaims++;
          }
        }
        case AssertionRecord edge -> {
          if (edge.fromQid().equals(qid) || edge.toQid().equals(qid)) {
            edgeClaims++;
          }
        }
        // Retractions never survive: they describe the fold rather than appear in it.
        case Retraction ignored -> {}
        // #92 Task 1 only adds these three types to LoggedAssertion's permits; nothing today can
        // put one in a log this method reads (IngestService.apply and SqliteAssertionLog.append
        // both refuse them until Task 2/Task 4 land), and how a retraction-effect count should
        // treat them is undecided. Left throwing rather than silently under- or over-counting.
        case LocalEntity local ->
            throw new UnsupportedOperationException(
                "#92: retraction effect counting does not yet handle LocalEntity: " + local.qid());
        case OwnerEdge edge ->
            throw new UnsupportedOperationException(
                "#92: retraction effect counting does not yet handle OwnerEdge: "
                    + edge.fromQid()
                    + " "
                    + edge.typeCode()
                    + " "
                    + edge.toQid());
        case SameAs sameAs ->
            throw new UnsupportedOperationException(
                "#92: retraction effect counting does not yet handle SameAs: "
                    + sameAs.localQid()
                    + " -> "
                    + sameAs.canonicalQid());
      }
    }
    return new Effect(qid, label, nodeClaims, edgeClaims);
  }
}
