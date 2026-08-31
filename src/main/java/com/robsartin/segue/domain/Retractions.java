package com.robsartin.segue.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a projection honours the retractions in a log (ADR 44).
 *
 * <p><b>One rule, called from both folds.</b> {@code GraphProjector} rebuilds the graph at boot and
 * {@code LogProjection} folds the same log for the exporter; if each carried its own idea of what a
 * retraction reaches, a graph and a picture of that graph could disagree about which edges are
 * still there. That is the same argument ADR 42 makes for {@code KindMapper.rederive}, and it lands
 * in {@code domain} rather than beside either caller because a retraction is nobody's vocabulary -
 * it is the log's own.
 *
 * <p><b>The unit is the entity.</b> A retraction of {@code Q900101} reaches that entity's node
 * claims and every edge claim with {@code Q900101} at either end. Not one edge, because the case
 * this exists for is a wrongly-<em>resolved</em> entity whose whole expansion is wrong; and not one
 * expansion, because retracting an expansion would leave the wrong identity in the graph, still
 * findable and still rateable. See ADR 44 for the full argument, including why it does not cascade
 * to the neighbours the expansion discovered.
 *
 * <p><b>It reaches backwards only, by position in the log.</b> A claim is retracted when it lies
 * <em>before</em> the retraction; claims appended afterwards stand. That is what makes re-adding an
 * entity the natural way back in - nothing special happens, the new claims are simply newer than
 * the retraction - and it is why there is no un-retraction to build.
 *
 * <p>Position, deliberately, and not {@code assertedAt}. The log's order is a guarantee the port
 * already makes ({@code readAll} returns sequence order) and it is total, where assertion time can
 * tie - a whole Wikidata expansion shares one instant by construction - and can legitimately run
 * behind the append that carries it. Comparing positions asks the question the decision actually
 * means: what had we already been told when this was decided?
 *
 * @param lastRetraction for each retracted entity, the position of the last row retracting it.
 *     Last, not first: a second retraction of an entity that was re-added in between has to reach
 *     the re-add
 */
public record Retractions(Map<String, Integer> lastRetraction) {

  public Retractions {
    lastRetraction = Map.copyOf(Objects.requireNonNull(lastRetraction, "lastRetraction"));
  }

  /** Read the retractions out of a log, in the order {@code AssertionLog.readAll} returns it. */
  public static Retractions in(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    Map<String, Integer> last = new HashMap<>();
    for (int i = 0; i < log.size(); i++) {
      if (log.get(i) instanceof Retraction retraction) {
        last.put(retraction.qid(), i);
      }
    }
    return new Retractions(last);
  }

  /**
   * Whether the row at {@code index} reaches the projection.
   *
   * <p>False for a retraction row itself: a retraction describes the fold rather than appearing in
   * it, so there is nothing for a graph or an export to hold. Both callers ask this one question,
   * which is what stops one of them remembering to skip retraction rows and the other forgetting.
   */
  public boolean survives(int index, LoggedAssertion assertion) {
    Objects.requireNonNull(assertion, "assertion");
    return switch (assertion) {
      case Retraction ignored -> false;
      case NodeAssertion node -> !isRetractedAt(index, node.qid());
      case AssertionRecord edge ->
          !isRetractedAt(index, edge.fromQid()) && !isRetractedAt(index, edge.toQid());
      // The owner's three claims (#92) are retracted by the same rule as the sourced ones, and
      // by the same argument: the unit is the entity, so a claim reaches the projection unless
      // an entity it names was retracted after it was made. Who said it makes no difference to
      // whether it was taken back - "I minted the wrong thing" is exactly the case ADR 44
      // exists for, arriving from the owner's own hand rather than a source's.
      case LocalEntity local -> !isRetractedAt(index, local.qid());
      case OwnerEdge edge ->
          !isRetractedAt(index, edge.fromQid()) && !isRetractedAt(index, edge.toQid());
      // A merge names two entities and is dropped if either is retracted, on the edge's rule and
      // not the node's: a SameAs holds a relationship between two ids rather than asserting that
      // either exists, so retracting the canonical side has to reach it too. Carrying an
      // equivalence onto a retracted entity is the failure this rules out.
      case SameAs sameAs ->
          !isRetractedAt(index, sameAs.localQid()) && !isRetractedAt(index, sameAs.canonicalQid());
    };
  }

  private boolean isRetractedAt(int index, String qid) {
    Integer cut = lastRetraction.get(qid);
    return cut != null && index < cut;
  }
}
