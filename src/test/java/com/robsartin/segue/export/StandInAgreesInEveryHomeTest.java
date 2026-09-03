package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.minted;
import static com.robsartin.segue.export.InventedGraph.node;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.Equivalences;
import com.robsartin.segue.domain.LocalEntity;
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
 * <p><b>Those two do not agree on the bypass row, and that is the code rather than a drift.</b>
 * {@code Q10000900202}'s local side is a {@code NodeAssertion} stating a class, so both folds
 * re-derive that node's kind and, since issue #222, the stand-in built from it as well. The live
 * path re-derives neither: {@code IngestService.record} upserts the claim as it stands, and {@code
 * IngestService.standIn} copies the local node as the running graph holds it, so both keep the
 * claimed kind until the next boot replays the log. That is ADR 42's accepted lag — the one the
 * local node itself has always had — so the kind on that row is pinned <em>per home</em> rather
 * than asserted equal across the two, which would be asserting something false about the code. The
 * invariant that does hold everywhere is asserted instead, in every home that exposes a kind: a
 * stand-in and the local node it stands for agree about what the entity is.
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

  /**
   * Row 3's claim, the source's own words: {@code SIGNAL}'s stated kind and the class it was
   * derived from. Read by both {@link #fourHomesLog()} and {@link
   * #shouldRederiveAKindDifferentFromTheClaimedOneWhenTheBypassRowsClassesAreMapped()} - one place
   * writes this row down, so an edit to it cannot leave the discriminating-property test asserting
   * against a stale copy.
   */
  private static final NodeAssertion BYPASS_CLAIM =
      node(SIGNAL, NodeKind.WORK, "a signal a source named", List.of("Q5"));

  /** The fixture: the spec's table, row for row. No edges and no retractions - see the spec. */
  private static FakeAssertionLog fourHomesLog() {
    return new FakeAssertionLog()
        .with(
            minted(APRIL, NodeKind.WORK, "the April tape"),
            merged(APRIL, TAPE),
            BYPASS_CLAIM,
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
   * @param local the local id the merge names, so that a stand-in can be compared with the node it
   *     stands for inside one home
   * @param standInKind what {@code Equivalences.standIns} holds before either fold overlays the
   *     log's own claims; null with {@code standInLabel} when it holds nothing. That pre-pass is
   *     the two FOLDS' - the live path has no whole log to read and builds its own stand-in inside
   *     {@code IngestService.standIn}, from the local node as the running graph holds it
   * @param shownInTheFold what the fold shows once those claims have landed
   * @param shownInTheLiveGraph what the live graph shows once they have - the same kind as {@code
   *     shownInTheFold} on every row but the bypass one, where ADR 42's live lag keeps the claimed
   *     kind until the next boot
   */
  private record Pinned(
      String canonical,
      String local,
      NodeKind standInKind,
      String standInLabel,
      NodeKind shownInTheFold,
      NodeKind shownInTheLiveGraph,
      String shownLabel) {}

  private static final List<Pinned> PINNED =
      List.of(
          new Pinned(
              TAPE,
              APRIL,
              NodeKind.WORK,
              "the April tape",
              NodeKind.WORK,
              NodeKind.WORK,
              "the April tape"),
          // The bypass row, and the only one whose two kind-exposing homes differ: the folds
          // re-derive Q5 to PERSON (#222), the live path holds the claimed WORK until the next
          // boot (ADR 42). See the class javadoc.
          new Pinned(
              BEACON,
              SIGNAL,
              NodeKind.PERSON,
              "a signal a source named",
              NodeKind.PERSON,
              NodeKind.WORK,
              "a signal a source named"),
          new Pinned(
              FIRST,
              TWICE_OVER,
              NodeKind.WORK,
              "the ledger, twice over",
              NodeKind.WORK,
              NodeKind.WORK,
              "the ledger, twice over"),
          new Pinned(
              SECOND,
              TWICE_OVER,
              NodeKind.WORK,
              "the ledger, twice over",
              NodeKind.WORK,
              NodeKind.WORK,
              "the ledger, twice over"),
          new Pinned(
              KNOWN,
              CLAIMED_LOCAL,
              NodeKind.WORK,
              "the owner's working title",
              NodeKind.GROUP,
              NodeKind.GROUP,
              "the name the source already had"),
          new Pinned(
              LATER,
              LATE_LOCAL,
              NodeKind.WORK,
              "the owner's other working title",
              NodeKind.GROUP,
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
  @DisplayName("each home that exposes a kind gives each canonical id that home's own pinned kind")
  void shouldHoldEachHomesOwnPinnedKindWhenBothHomesThatExposeAKindReadOneLog() {
    List<String> departures = new ArrayList<>();
    long answered = 0;
    for (Pinned row : PINNED) {
      Answer inFold = fromNode(IN_THE_FOLD.get(row.canonical()));
      Answer inGraph = fromNode(IN_THE_LIVE_GRAPH.get(row.canonical()));
      answered += inFold.kind() == null ? 0 : 1;
      answered += inGraph.kind() == null ? 0 : 1;
      departures.addAll(departure(FOLD, row.canonical(), inFold, row.shownInTheFold()));
      departures.addAll(departure(LIVE, row.canonical(), inGraph, row.shownInTheLiveGraph()));
    }

    assertThat(answered)
        .as("both kind-exposing homes answered for every canonical id the table says is present")
        .isEqualTo(
            PINNED.stream().filter(r -> r.shownInTheFold() != null).count()
                + PINNED.stream().filter(r -> r.shownInTheLiveGraph() != null).count());
    assertThat(departures)
        .as(
            "each of the two kind-exposing homes against its own pinned kind - they agree on every"
                + " row but the bypass one, where the folds re-derive (#222) and the live path"
                + " holds the claimed kind until the next boot (ADR 42)")
        .isEmpty();
    assertThat(
            PINNED.stream()
                .filter(r -> !Objects.equals(r.shownInTheFold(), r.shownInTheLiveGraph()))
                .toList())
        .as(
            "the two homes are pinned apart on the bypass row and nowhere else - a table pinning"
                + " them apart anywhere else would be recording a drift as though it were ADR 42's"
                + " lag, and one pinning them apart nowhere would have stopped recording that lag")
        .extracting(Pinned::canonical)
        .containsExactly(BEACON);
  }

  @Test
  @DisplayName("in each home that exposes a kind, a stand-in agrees with the local node it copies")
  void shouldMatchItsOwnLocalNodesKindWhenEachHomesStandInIsRead() {
    List<Pinned> rows =
        PINNED.stream().filter(r -> !claimedOutright().contains(r.canonical())).toList();
    List<String> departures = new ArrayList<>();
    for (Pinned row : rows) {
      departures.addAll(standInDeparture(FOLD, IN_THE_FOLD, row));
      departures.addAll(standInDeparture(LIVE, IN_THE_LIVE_GRAPH, row));
    }

    assertThat(rows)
        .as(
            "the rows whose canonical node IS the stand-in must still include the bypass row (%s),"
                + " or this asserts the invariant only where nothing re-derives and goes blind",
            BEACON)
        .extracting(Pinned::canonical)
        .contains(BEACON);
    assertThat(departures)
        .as(
            "a stand-in copies the local node as its own home holds it - the one thing both"
                + " kind-exposing homes say, whether or not that home re-derives")
        .isEmpty();
  }

  @Test
  @DisplayName("the stand-in answer today, before and after the log's own claims land on it")
  void shouldHoldTodaysStandInAnswerWhenTheFixtureIsRead() {
    Map<String, NodeRecord> standIns = Equivalences.standIns(LOG.readAll(), KindMapper::rederive);

    for (Pinned row : PINNED) {
      assertThat(describe(standIns.get(row.canonical())))
          .as(
              "Equivalences.standIns for %s, read raw - it has no \"unless something claimed"
                  + " it\" condition, and gets that guarantee from being applied first",
              row.canonical())
          .isEqualTo(describe(row.standInKind(), row.standInLabel()));
      assertThat(describe(IN_THE_FOLD.get(row.canonical())))
          .as("what the fold shows for %s once the log's own claims land", row.canonical())
          .isEqualTo(describe(row.shownInTheFold(), row.shownLabel()));
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
    assertThat(KindMapper.rederive(BYPASS_CLAIM).kind())
        .as(
            "%s's stated class Q5 must re-derive to a kind different from the claimed %s - that gap"
                + " is the whole reason this row is pinned as the bypass-lag row (issue #222); if it"
                + " ever agreed, the row would stop discriminating and nothing else here would"
                + " notice",
            SIGNAL, BYPASS_CLAIM.kind())
        .isNotEqualTo(BYPASS_CLAIM.kind());
  }

  /** One home's answer against its own pinned kind, as a line naming both or as nothing at all. */
  private static List<String> departure(
      String home, String canonical, Answer answer, NodeKind pinned) {
    if (Objects.equals(answer.kind(), pinned)) {
      return List.of();
    }
    return List.of(canonical + ": " + home + " says " + answer.describe() + ", pinned " + pinned);
  }

  /**
   * The qids the log claims a node for outright, derived from the log rather than listed. A
   * canonical id in this set has a node of its own, which last-writer-wins has put over the
   * stand-in in every home - so comparing that node against a local working title would be
   * comparing a source's own claim with the thing it replaced, and the row drops out of the
   * stand-in comparison instead. Deriving it means a fixture that starts claiming another canonical
   * id moves the scope with it, rather than leaving an assertion quietly asking the wrong question.
   */
  private static Set<String> claimedOutright() {
    Set<String> claimed = new LinkedHashSet<>();
    for (LoggedAssertion assertion : LOG.readAll()) {
      if (assertion instanceof NodeAssertion claim) {
        claimed.add(claim.qid());
      }
      if (assertion instanceof LocalEntity minted) {
        claimed.add(minted.qid());
      }
    }
    return claimed;
  }

  /**
   * One home's answer to "does this stand-in agree with the node it stands for", as a line naming
   * both or as nothing at all.
   */
  private static List<String> standInDeparture(
      String home, Map<String, NodeRecord> nodes, Pinned row) {
    Answer standIn = fromNode(nodes.get(row.canonical()));
    Answer local = fromNode(nodes.get(row.local()));
    if (Objects.equals(standIn.kind(), local.kind())) {
      return List.of();
    }
    return List.of(
        home
            + ": "
            + row.canonical()
            + " stands in as "
            + standIn.describe()
            + " for "
            + row.local()
            + ", which the same home holds as "
            + local.describe());
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
      // The local ids as well as the canonical ones: a stand-in is only checkable against the node
      // it copies if this home can be asked about both.
      for (String qid : canonicalIds()) {
        graph.node(qid).ifPresent(node -> nodes.put(qid, node));
      }
      for (Pinned row : PINNED) {
        graph.node(row.local()).ifPresent(node -> nodes.put(row.local(), node));
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
