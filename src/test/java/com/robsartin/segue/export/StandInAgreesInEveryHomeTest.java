package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.minted;
import static com.robsartin.segue.export.InventedGraph.node;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LoggedAssertion;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.own.ProjectionLabelsProbe;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.ratings.LabelsProbe;
import com.robsartin.segue.tinker.TinkerGraphStore;
import com.robsartin.segue.wikidata.KindMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The stand-in rule's four homes give one answer, on one log (issue #220).
 *
 * <p><b>This pins today's answers, and does not claim they are right.</b> Every expected value
 * below is what the four homes say today, recorded so that one of them moving alone reds; three of
 * the pinned rows are behaviours ADR 59 lists as residuals — one for {@code Q10000900203} / {@code
 * Q10000900204} (a local merged twice, issue #221) and one for {@code Q10000900202} (the bypass
 * kind, issue #222) — and pinning them is not agreeing with them.
 *
 * <p><b>A kind is compared only where a home exposes one.</b> Two of the four — {@code
 * Equivalences.standIns} (via the fold) and the live {@code IngestService} graph — return a {@code
 * NodeRecord} and so answer with a kind; {@code ratings/Labels.forQids} returns a label only, by
 * design, and {@code OwnRun.labelsInTheProjection} does too. Asking a label-only home for a kind
 * would be asking it to answer a question it was built not to ask.
 *
 * <p><b>It does not close ADR 59's residual.</b> The residual is that the stand-in rule has four
 * homes; after this it still has four. What changes is that nothing failed if one drifted, and now
 * something does.
 */
class StandInAgreesInEveryHomeTest {

  private static final String APRIL = "Q0011";
  private static final String SIGNAL = "Q0012";
  private static final String TWICE_OVER = "Q0013";
  private static final String CLAIMED_LOCAL = "Q0014";
  private static final String LATE_LOCAL = "Q0015";
  private static final String SPARE = "Q0016";

  private static final String TAPE = "Q10000900201";
  private static final String BEACON = "Q10000900202";
  private static final String FIRST = "Q10000900203";
  private static final String SECOND = "Q10000900204";
  private static final String KNOWN = "Q10000900205";
  private static final String LATER = "Q10000900206";

  private static final String FOLD = "Equivalences.standIns (via LogProjection.of)";
  private static final String LIVE = "IngestService.standIn (live record)";
  private static final String OWN = "OwnRun.labelsInTheProjection";
  private static final String RATINGS = "ratings/Labels.forQids";

  /**
   * The four homes this guard reads, named independently of {@link #answersFor}'s own map - so that
   * dropping a {@code byHome.put(...)} line there shrinks one side of the count and not the other,
   * and the mismatch reds instead of the guard quietly becoming a three-home guard.
   */
  private static final List<String> HOMES = List.of(FOLD, LIVE, OWN, RATINGS);

  /** The fixture: the spec's table, row for row. No edges and no retractions - see the spec. */
  private static FakeAssertionLog fourHomesLog() {
    return new FakeAssertionLog()
        .with(
            minted(APRIL, NodeKind.WORK, "the April tape"),
            merged(APRIL, TAPE),
            node(SIGNAL, NodeKind.WORK, "a signal a source named", List.of("Q5")),
            merged(SIGNAL, BEACON),
            minted(TWICE_OVER, NodeKind.WORK, "the ledger, twice over"),
            merged(TWICE_OVER, FIRST),
            merged(TWICE_OVER, SECOND),
            minted(CLAIMED_LOCAL, NodeKind.WORK, "the owner's working title"),
            node(KNOWN, NodeKind.GROUP, "the name the source already had"),
            merged(CLAIMED_LOCAL, KNOWN),
            minted(LATE_LOCAL, NodeKind.WORK, "the owner's other working title"),
            merged(LATE_LOCAL, LATER),
            node(LATER, NodeKind.GROUP, "the name the source brought later"),
            minted(SPARE, NodeKind.WORK, "the second working title"),
            merged(SPARE, TAPE));
  }

  /**
   * One canonical id and what the four homes say about it today.
   *
   * @param standInKind what {@code Equivalences.standIns} holds before either fold overlays the
   *     log's own claims; null with {@code standInLabel} when it holds nothing
   * @param shownKind what the projection shows once those claims have landed - the answer all four
   *     homes give
   */
  private record Pinned(
      String canonical,
      NodeKind standInKind,
      String standInLabel,
      NodeKind shownKind,
      String shownLabel) {}

  private static final List<Pinned> PINNED =
      List.of(
          new Pinned(TAPE, NodeKind.WORK, "the April tape", NodeKind.WORK, "the April tape"),
          new Pinned(
              BEACON,
              NodeKind.WORK,
              "a signal a source named",
              NodeKind.WORK,
              "a signal a source named"),
          new Pinned(
              FIRST,
              NodeKind.WORK,
              "the ledger, twice over",
              NodeKind.WORK,
              "the ledger, twice over"),
          new Pinned(
              SECOND,
              NodeKind.WORK,
              "the ledger, twice over",
              NodeKind.WORK,
              "the ledger, twice over"),
          new Pinned(
              KNOWN,
              NodeKind.WORK,
              "the owner's working title",
              NodeKind.GROUP,
              "the name the source already had"),
          new Pinned(
              LATER,
              NodeKind.WORK,
              "the owner's other working title",
              NodeKind.GROUP,
              "the name the source brought later"));

  // Read once. Every home gets the same fifteen rows, which is the whole point, and building
  // the live graph per assertion would open a TinkerGraph six times over.
  private static final FakeAssertionLog LOG = fourHomesLog();
  private static final Map<String, NodeRecord> IN_THE_FOLD = LogProjection.of(LOG).nodes();
  private static final Map<String, NodeRecord> IN_THE_LIVE_GRAPH = liveGraphNodes();
  private static final Map<String, String> IN_THE_RATINGS_LIST =
      LabelsProbe.forQids(LOG, canonicalIds());
  private static final Map<String, String> IN_THE_OWN_TOOL =
      ProjectionLabelsProbe.labelsInTheProjection(LOG.readAll(), Equivalences.in(LOG.readAll()));

  /** One home's answer: a label always, a kind only where the home exposes one. */
  private record Answer(String label, NodeKind kind) {

    static final Answer NOTHING = new Answer(null, null);

    String describe() {
      if (label == null) {
        return "no node";
      }
      return kind == null ? "\"" + label + "\"" : kind + " \"" + label + "\"";
    }
  }

  @Test
  @DisplayName("every home calls each canonical id the same thing")
  void shouldAgreeOnEveryCanonicalLabelWhenAllFourHomesReadOneLog() {
    assertThat(answersFor(TAPE).keySet())
        .as("the four homes this guard reads (%s), independent of the count below", HOMES)
        .containsExactlyElementsOf(HOMES);

    List<String> disagreements = new ArrayList<>();
    long answered = 0;
    for (Pinned row : PINNED) {
      List<Map.Entry<String, Answer>> homes = List.copyOf(answersFor(row.canonical()).entrySet());
      for (Map.Entry<String, Answer> home : homes) {
        answered += home.getValue().label() == null ? 0 : 1;
      }
      for (int i = 0; i < homes.size(); i++) {
        for (int j = i + 1; j < homes.size(); j++) {
          if (!Objects.equals(homes.get(i).getValue().label(), homes.get(j).getValue().label())) {
            disagreements.add(
                row.canonical()
                    + ": "
                    + homes.get(i).getKey()
                    + " says "
                    + homes.get(i).getValue().describe()
                    + ", "
                    + homes.get(j).getKey()
                    + " says "
                    + homes.get(j).getValue().describe());
          }
        }
      }
    }

    // Homes that all answered nothing would agree perfectly.
    assertThat(answered)
        .as("every home answered for every canonical id the pinned table says is present")
        .isEqualTo(homeCount() * PINNED.stream().filter(r -> r.shownLabel() != null).count());
    assertThat(disagreements)
        .as(
            "one stand-in rule, %d homes (ADR 59's residual, issue #220) - each line names the"
                + " pair that disagrees",
            homeCount())
        .isEmpty();
  }

  @Test
  @DisplayName("both homes that expose a kind give each canonical id the same kind")
  void shouldAgreeOnEveryCanonicalKindWhenBothHomesThatExposeAKindReadOneLog() {
    List<String> disagreements = new ArrayList<>();
    long answered = 0;
    for (Pinned row : PINNED) {
      Answer inFold = fromNode(IN_THE_FOLD.get(row.canonical()));
      Answer inGraph = fromNode(IN_THE_LIVE_GRAPH.get(row.canonical()));
      answered += inFold.kind() == null ? 0 : 1;
      answered += inGraph.kind() == null ? 0 : 1;
      if (!Objects.equals(inFold.kind(), inGraph.kind())) {
        disagreements.add(
            row.canonical()
                + ": "
                + FOLD
                + " says "
                + inFold.describe()
                + ", "
                + LIVE
                + " says "
                + inGraph.describe());
      }
    }

    assertThat(answered)
        .as("both kind-exposing homes answered for every canonical id the table says is present")
        .isEqualTo(2 * PINNED.stream().filter(r -> r.shownKind() != null).count());
    assertThat(disagreements)
        .as("the two homes that expose a kind, on one log - each line names the pair")
        .isEmpty();
  }

  @Test
  @DisplayName("the stand-in answer today, before and after the log's own claims land on it")
  void shouldHoldTodaysStandInAnswerWhenTheFixtureIsRead() {
    Map<String, NodeRecord> standIns = Equivalences.standIns(LOG.readAll());

    for (Pinned row : PINNED) {
      assertThat(describe(standIns.get(row.canonical())))
          .as(
              "Equivalences.standIns for %s, read raw - it has no \"unless something claimed"
                  + " it\" condition, and gets that guarantee from being applied first",
              row.canonical())
          .isEqualTo(describe(row.standInKind(), row.standInLabel()));
      assertThat(describe(IN_THE_FOLD.get(row.canonical())))
          .as("what the projection shows for %s once the log's own claims land", row.canonical())
          .isEqualTo(describe(row.shownKind(), row.shownLabel()));
    }

    assertThat(standIns.keySet())
        .as(
            "the pre-pass offers a node for every canonical id a surviving merge names, in log order")
        .containsExactlyElementsOf(
            PINNED.stream().filter(r -> r.standInLabel() != null).map(Pinned::canonical).toList());
  }

  @Test
  @DisplayName("the bypass row's claimed kind and its re-derived kind differ")
  void shouldRederiveAKindDifferentFromTheClaimedOneWhenTheBypassRowsClassesAreMapped() {
    NodeAssertion bypassClaim =
        node(SIGNAL, NodeKind.WORK, "a signal a source named", List.of("Q5"));

    assertThat(KindMapper.rederive(bypassClaim).kind())
        .as(
            "%s's stated class Q5 must re-derive to a kind different from the claimed %s - that gap"
                + " is the whole reason this row is pinned as the bypass-lag row (issue #222); if it"
                + " ever agreed, the row would stop discriminating and nothing else here would"
                + " notice",
            SIGNAL, bypassClaim.kind())
        .isNotEqualTo(bypassClaim.kind());
  }

  /** Every home's answer for one canonical id, in a fixed order so a failure reads alike twice. */
  private static Map<String, Answer> answersFor(String canonical) {
    Map<String, Answer> byHome = new LinkedHashMap<>();
    byHome.put(FOLD, fromNode(IN_THE_FOLD.get(canonical)));
    byHome.put(LIVE, fromNode(IN_THE_LIVE_GRAPH.get(canonical)));
    byHome.put(OWN, fromLabel(IN_THE_OWN_TOOL.get(canonical)));
    byHome.put(RATINGS, fromLabel(IN_THE_RATINGS_LIST.get(canonical)));
    return byHome;
  }

  /**
   * How many homes this guard reads - {@link #HOMES}, not {@link #answersFor}'s own map size, so a
   * home silently dropped from {@link #answersFor} cannot shrink both sides of the vacuity count
   * together.
   */
  private static long homeCount() {
    return HOMES.size();
  }

  /**
   * The live path, driven through {@code IngestService.record} directly rather than {@code
   * GraphProjector.project}, deliberately. In production, {@code GraphProjector.project} seeds
   * every canonical node from {@code Equivalences.standIns} before its loop runs, so {@code
   * IngestService.standIn}'s own upsert never fires there - the node it would write already exists.
   * {@code record}'s own javadoc goes further still: nothing in production sends a {@code SameAs}
   * to {@code record} at all - {@code OwnRun} appends a merge through {@code claim}, which has no
   * graph half. This replay bypasses both, calling {@code record} directly with no pre-seed, so the
   * upsert does fire here: it is the live home's only probe of that code, anywhere.
   */
  private static Map<String, NodeRecord> liveGraphNodes() {
    Map<String, NodeRecord> nodes = new LinkedHashMap<>();
    List<LoggedAssertion> logged = LOG.readAll();
    try (TinkerGraphStore graph = new TinkerGraphStore()) {
      IngestService ingest = new IngestService(new FakeAssertionLog(), graph, IdentityMerge.NONE);
      logged.forEach(ingest::record);
      for (String qid : canonicalIds()) {
        graph.node(qid).ifPresent(node -> nodes.put(qid, node));
      }
    }
    return nodes;
  }

  private static Set<String> canonicalIds() {
    return new LinkedHashSet<>(PINNED.stream().map(Pinned::canonical).toList());
  }

  private static Answer fromNode(NodeRecord node) {
    return node == null ? Answer.NOTHING : new Answer(node.label(), node.kind());
  }

  private static Answer fromLabel(String label) {
    return label == null ? Answer.NOTHING : new Answer(label, null);
  }

  private static String describe(NodeRecord node) {
    return node == null ? "no node" : describe(node.kind(), node.label());
  }

  private static String describe(NodeKind kind, String label) {
    return label == null ? "no node" : kind + " \"" + label + "\"";
  }
}
