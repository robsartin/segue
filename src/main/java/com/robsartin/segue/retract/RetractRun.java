package com.robsartin.segue.retract;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Equivalences;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    for (String stranded : strandedByThisRetraction(options)) {
      notes.accept(stranded);
    }

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
        // The owner's claims (#92) are counted in the same two buckets they project into, so the
        // report still says what will stop projecting rather than what a source claimed. A minted
        // entity supplies the label too: without this, retracting something the owner minted
        // would print "(no node claim in the projection)" for an entity that is plainly in the
        // graph - and the label is the safety feature this report exists for.
        case LocalEntity minted -> {
          if (minted.qid().equals(qid)) {
            label = minted.label();
            nodeClaims++;
          }
        }
        case OwnerEdge owned -> {
          if (owned.fromQid().equals(qid) || owned.toQid().equals(qid)) {
            edgeClaims++;
          }
        }
        // Counted with the edges rather than ignored, matching Retractions.survives, which drops
        // a merge naming a retracted entity on the edge rule. Counting is what decides whether
        // "nothing to retract" is refused, so a merge the retraction WILL reach has to be
        // visible here or an entity known only through one would be unretractable.
        case SameAs merge -> {
          if (merge.localQid().equals(qid) || merge.canonicalQid().equals(qid)) {
            edgeClaims++;
          }
        }
      }
    }
    return new Effect(qid, label, nodeClaims, edgeClaims);
  }

  /**
   * What ELSE stops projecting: the canonical ids THIS retraction newly empties, and the distinct
   * edges that go with them (#224).
   *
   * <p>Retracting a local id the owner had merged drops the merge — {@link Retractions#survives}
   * drops a {@link com.robsartin.segue.domain.SameAs} when either of its ids is retracted — and
   * with it the only node the canonical id may ever have had. Both folds then drop the edges that
   * named it, so the report has to name them: {@link Effect}'s two counts are claims naming the qid
   * being retracted, and these name a different id. They are reported rather than added to those
   * counts for that reason, and because the counts decide whether "nothing to retract" is refused.
   *
   * <p><b>Only what THIS retraction newly strands, fix round 1.</b> {@link
   * Equivalences#retractedStandIns} answers "which canonical ids has the log, as given, emptied" -
   * asked of the log this retraction would produce, that includes every canonical id an earlier
   * retraction already emptied and already reported. The set difference against the log as it
   * stands is what is actually new here; without it, an unrelated later retraction repeated every
   * earlier one's stranded-edge notes forever.
   *
   * <p><b>Counted by edge key, matching {@code LogProjection.withdrawnEdges}, fix round 1.</b> That
   * count runs over the grouped claims, so two sources corroborating one withdrawn relationship are
   * one withdrawal there - and this report has to agree, or the same retraction would read as two
   * different sizes in two places. {@link Equivalences#namesARetractedStandIn} is the shared
   * predicate both readers ask, over {@link Equivalences#folding}, rather than a second,
   * hand-rolled idea of what "names this canonical id" means.
   *
   * <p><b>One pass, and one edge key can go under two ids, fix round 2.</b> A local id merged onto
   * one canonical id and later corrected onto another strands BOTH of them if a surviving edge
   * names either directly (see {@link Equivalences#stands}'s widening) — and an edge whose two ends
   * land on two ids this same retraction strands names both, honestly, so it belongs on both ids'
   * lines. Scanning the log once per canonical id used to decide that per line in isolation;
   * scanning it ONCE instead and bucketing each withdrawn edge key under every newly-emptied id it
   * names is the same answer without the rescans, and it is what makes the closing total below
   * possible to state honestly: the per-id lines can share an edge, so their sizes may sum to more
   * than what actually stopped projecting. The distinct count across ALL of them — one {@link Set}
   * of edge keys, added to regardless of which id a claim matched — is the number that agrees with
   * {@code LogProjection.withdrawnEdges}, and a closing line states it whenever more than one id is
   * newly emptied, so the owner sees the export's number rather than adding up lines that
   * double-count. With exactly one newly-emptied id the per-id line already says the whole story
   * and no closing line is added.
   *
   * <p><b>An id that strands no edge gets no line, final review.</b> The line exists to name the
   * edges that stop projecting, so one saying "0 edge(s)" tells the operator nothing and misleads
   * about the rest of the sentence. The case that produces one is the qid being retracted here
   * being itself a canonical id: a source claimed it, the owner merged something onto it, the local
   * side was retracted earlier — so the merge is already gone and the source's node claim is all
   * that still holds the id — and retracting the id NOW takes that claim away, which is what newly
   * empties it. Every edge naming it went with its own retraction ({@link Retractions#survives},
   * either endpoint) and is already in {@link Effect}'s counts, so the set is always empty there.
   * The guard is on the set rather than on {@code options.qid()} because emptiness is the property
   * the line is about: any other id this retraction empties without stranding an edge has the same
   * nothing to report. The closing total then counts the lines that were actually written, not the
   * ids that were emptied, so it cannot appear beside a single line.
   *
   * <p><b>Asked of the log this retraction would produce</b>, not of the log as it stands: the rule
   * is about what a retraction reaches, and there is no retraction in the log yet. Nothing is
   * appended — the row is built in memory, and {@link #run} may still be a dry run.
   */
  private List<String> strandedByThisRetraction(Options options) {
    List<LoggedAssertion> before = log.readAll();
    List<LoggedAssertion> after = new ArrayList<>(before);
    after.add(new Retraction(options.qid(), options.reason(), clock.instant()));

    Set<String> newlyEmptied = new LinkedHashSet<>(Equivalences.retractedStandIns(after));
    newlyEmptied.removeAll(Equivalences.retractedStandIns(before));
    if (newlyEmptied.isEmpty()) {
      return List.of();
    }

    Retractions retractions = Retractions.in(after);
    Equivalences equivalences = Equivalences.folding(after);
    Map<String, Set<String>> edgeKeysByCanonical = new LinkedHashMap<>();
    for (String canonical : newlyEmptied) {
      edgeKeysByCanonical.put(canonical, new LinkedHashSet<>());
    }
    Set<String> allStrandedEdgeKeys = new LinkedHashSet<>();

    for (int i = 0; i < after.size(); i++) {
      LoggedAssertion assertion = after.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      AssertionRecord claim =
          switch (assertion) {
            case AssertionRecord sourced -> sourced;
            case OwnerEdge owned -> owned.toAssertion();
            default -> null;
          };
      if (claim == null || !equivalences.namesARetractedStandIn(claim)) {
        continue;
      }
      boolean namesANewlyEmptiedId = false;
      if (newlyEmptied.contains(claim.fromQid())) {
        edgeKeysByCanonical.get(claim.fromQid()).add(claim.edgeKey());
        namesANewlyEmptiedId = true;
      }
      if (newlyEmptied.contains(claim.toQid())) {
        edgeKeysByCanonical.get(claim.toQid()).add(claim.edgeKey());
        namesANewlyEmptiedId = true;
      }
      if (namesANewlyEmptiedId) {
        allStrandedEdgeKeys.add(claim.edgeKey());
      }
    }

    List<String> notes = new ArrayList<>();
    for (String canonical : newlyEmptied) {
      Set<String> stranded = edgeKeysByCanonical.get(canonical);
      if (stranded.isEmpty()) {
        // Nothing stops projecting under this id, so there is nothing for a line to say - see
        // this method's javadoc on the qid being retracted.
        continue;
      }
      notes.add(
          "the merge onto "
              + canonical
              + " goes too, and nothing else holds a node for that id, so "
              + stranded.size()
              + " edge(s) naming it stop projecting with it (#224)");
    }
    if (notes.size() > 1) {
      notes.add(allStrandedEdgeKeys.size() + " distinct edge(s) stop projecting in all (#224)");
    }
    return notes;
  }
}
