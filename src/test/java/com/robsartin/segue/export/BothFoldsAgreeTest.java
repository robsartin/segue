package com.robsartin.segue.export;

import static com.robsartin.segue.export.InventedGraph.ALMANAC;
import static com.robsartin.segue.export.InventedGraph.BYPASS;
import static com.robsartin.segue.export.InventedGraph.CORRECTED;
import static com.robsartin.segue.export.InventedGraph.DEMO;
import static com.robsartin.segue.export.InventedGraph.DETOUR;
import static com.robsartin.segue.export.InventedGraph.FORFEIT;
import static com.robsartin.segue.export.InventedGraph.HOLLOW_TIDE;
import static com.robsartin.segue.export.InventedGraph.KETTLES;
import static com.robsartin.segue.export.InventedGraph.LAPSE;
import static com.robsartin.segue.export.InventedGraph.LEDGER;
import static com.robsartin.segue.export.InventedGraph.MARLOW;
import static com.robsartin.segue.export.InventedGraph.MISHEARD;
import static com.robsartin.segue.export.InventedGraph.PRESSING;
import static com.robsartin.segue.export.InventedGraph.REROUTED;
import static com.robsartin.segue.export.InventedGraph.STANDING;
import static com.robsartin.segue.export.InventedGraph.STRAY;
import static com.robsartin.segue.export.InventedGraph.TWICE;
import static com.robsartin.segue.export.InventedGraph.WATERMARK;
import static com.robsartin.segue.export.InventedGraph.WREN;
import static com.robsartin.segue.export.InventedGraph.edge;
import static com.robsartin.segue.export.InventedGraph.merged;
import static com.robsartin.segue.export.InventedGraph.minted;
import static com.robsartin.segue.export.InventedGraph.node;
import static com.robsartin.segue.export.InventedGraph.owned;
import static com.robsartin.segue.export.InventedGraph.retract;
import static org.assertj.core.api.Assertions.assertThat;

import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.Retraction;
import com.robsartin.segue.export.InventedGraph.FakeAssertionLog;
import com.robsartin.segue.ingest.GraphProjector;
import com.robsartin.segue.port.IdentityMerge;
import com.robsartin.segue.tinker.TinkerGraphStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The log is folded in two places - {@code GraphProjector} at boot (ADR 24) and {@link
 * LogProjection} for the exporter (ADR 41) - and this is the test that they cannot drift apart.
 *
 * <p>Everything else about retraction is checked on one side or the other. This checks the pair,
 * because the failure that matters is not either fold being wrong: it is a graph and a picture of
 * that graph disagreeing about which edges are still there, where the export is the artefact
 * somebody keeps and looks at weeks later, believing it. ADR 42 put node-kind re-derivation behind
 * one shared rule for the same reason; ADR 44 puts retraction behind {@code Retractions}.
 */
class BothFoldsAgreeTest {

  private static final Instant RETRACTED_AT = Instant.parse("2026-02-01T00:00:00Z");

  /**
   * One log exercising every case the rule has: a retracted entity with edges out and in, an
   * untouched entity, a re-add after the retraction, and a second entity retracted twice with a
   * claim between the two retractions.
   */
  private static FakeAssertionLog awkwardLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.CONCEPT, "Wren Alderman"),
            node(KETTLES, NodeKind.GROUP, "The Paper Kettles"),
            node(HOLLOW_TIDE, NodeKind.GROUP, "Hollow Tide"),
            node(MARLOW, NodeKind.PERSON, "Ines Marlow"),
            edge(WREN, KETTLES, "MEMBER_OF"),
            edge(MARLOW, WREN, "INFLUENCED_BY"),
            edge(MARLOW, HOLLOW_TIDE, "MEMBER_OF"),
            new Retraction(WREN, "resolved to the wrong Wren", RETRACTED_AT),
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            edge(WREN, HOLLOW_TIDE, "MEMBER_OF"),
            new Retraction(KETTLES, "a duplicate of Hollow Tide", RETRACTED_AT));
  }

  /**
   * The same pair of folds over the third layer (#92, issue #92 task 6's review): an entity the
   * owner minted with an owner edge out of it, a merge onto an id no source has claimed, a second
   * minted entity merged onto an id a source <em>has</em> claimed, and one owner edge appended
   * <em>after</em> a merge - which folds onto the canonical id like any other, because since #178
   * the resolution is over the whole log rather than at the merge's own row. That is spec ruling 2,
   * and it is what {@code owned(ALMANAC, MARLOW, "INFLUENCED_BY")} is for: it is claimed against an
   * id already merged and both folds hold it as {@code PRESSING INFLUENCED_BY MARLOW}. This
   * paragraph said the opposite until Task 4's review caught it - the sentence was true of {@code
   * carry}, which ran at the merge's position, and it survived the change that made it false. It is
   * no longer the last row of the fixture - the twice-merged cases below it are - but the claim
   * this paragraph makes is about what that row does, not about its position.
   *
   * <p><b>Widened for #178, because the Mikado probe produced no evidence here at all.</b> Both
   * merge tests below died inside {@code GraphProjector.project} before comparing anything, so the
   * export half of the endpoint fold was entirely unexercised - and this is the only test that
   * compares the pair. What was missing from the fixture was the case the fold is hardest on:
   * {@code LEDGER} carries an owner edge <b>out</b> of it, one <b>in</b> to it, and one whose other
   * end is itself a merged local id, all claimed before either merge. A fold that rewrote one
   * direction, or that resolved only one end of an edge, would leave the two folds holding
   * different graphs and nothing else here would say so.
   *
   * <p><b>{@code BYPASS} is the local id nothing minted.</b> Spec ruling 2 says the fold must not
   * assume that a claim naming a merged local id came through {@code OwnCli}: "a later claim naming
   * the local id, by a path that bypasses the tool, folds onto the canonical id like any other". So
   * one merge here has its local side named by a plain node claim, and both folds have to agree
   * about the canonical node it stands in for - a question they answered with one expression before
   * the stand-in was hoisted, and could quietly answer with two afterwards.
   *
   * <p><b>{@code CORRECTED} is merged twice</b> (#221) — onto {@code MISHEARD} and then onto {@code
   * WATERMARK}, which is how a wrong merge is corrected. Both folds must hold no node at all under
   * {@code MISHEARD}: the exporter's fold and the boot replay each built one, from {@code
   * Equivalences.standIns} and {@code IngestService.standIn} respectively, so a fix to either alone
   * leaves them holding different graphs and this is the test that says so.
   *
   * <p><b>{@code STRAY} is merged twice as well, and is the other face of the same rule</b> (#221's
   * 2026-09-03 amendment, added here because the amendment landed after the paragraph above was
   * written and this fixture had no case for it). It is merged onto {@code DETOUR}, given a
   * separate owner edge naming {@code DETOUR} <em>directly</em> while it stood as the canonical id,
   * and only then corrected onto {@code REROUTED}. Unlike {@code MISHEARD}, {@code DETOUR}'s
   * stand-in must survive the correction in both folds, because {@code Equivalences.stands} widened
   * from last-wins alone to last-wins OR a surviving edge naming the canonical id — dropping {@code
   * DETOUR}'s node here would leave the {@code WREN → DETOUR} edge dangling in one fold or the
   * other, or both. {@code TwiceMergedIdLeavesNoOrphanTest} pins this shape directly, against each
   * fold on its own; this fixture is what asks whether the two folds still agree about it.
   *
   * <p><b>{@code LAPSE} is the retracted merge (#224), and it is the case where one fold does not
   * disagree but <em>throws</em>.</b> It is minted, merged onto {@code FORFEIT}, given an owner
   * edge naming {@code FORFEIT} directly — which {@code OwnRun} offers the moment the stand-in
   * exists — and then retracted. The merge stops projecting and takes the only node {@code FORFEIT}
   * ever had with it, so before the fix {@code GraphProjector.project} died inside this fixture at
   * the edge's own row and the comparison below never ran at all. Both folds must now hold no node
   * under {@code FORFEIT} and no edge naming it. {@code RetractedStandInTakesItsEdgesTest} pins the
   * shape against each fold on its own; this fixture is what asks whether the two still agree.
   */
  private static FakeAssertionLog ownedLog() {
    return new FakeAssertionLog()
        .with(
            node(WREN, NodeKind.PERSON, "Wren Alderman"),
            node(MARLOW, NodeKind.PERSON, "Ines Marlow"),
            node(KETTLES, NodeKind.GROUP, "The Paper Kettles"),
            node(HOLLOW_TIDE, NodeKind.GROUP, "Hollow Tide"),
            minted(ALMANAC, NodeKind.WORK, "The Salt Almanac"),
            owned(ALMANAC, WREN, "INFLUENCED_BY"),
            minted(LEDGER, NodeKind.WORK, "the Watermark ledger"),
            owned(LEDGER, HOLLOW_TIDE, "INFLUENCED_BY"),
            owned(MARLOW, LEDGER, "INFLUENCED_BY"),
            owned(LEDGER, ALMANAC, "INFLUENCED_BY"),
            merged(ALMANAC, PRESSING),
            minted(DEMO, NodeKind.WORK, "the Kettles demo"),
            owned(MARLOW, DEMO, "INFLUENCED_BY"),
            merged(DEMO, KETTLES),
            merged(LEDGER, WATERMARK),
            node(BYPASS, NodeKind.WORK, "a local-shaped id a source named"),
            owned(BYPASS, WREN, "INFLUENCED_BY"),
            merged(BYPASS, STANDING),
            minted(TWICE, NodeKind.WORK, "the Salt Almanac again"),
            owned(TWICE, ALMANAC, "INFLUENCED_BY"),
            merged(TWICE, PRESSING),
            owned(ALMANAC, MARLOW, "INFLUENCED_BY"),
            minted(CORRECTED, NodeKind.WORK, "A Self-Pressed Record"),
            owned(CORRECTED, MARLOW, "INFLUENCED_BY"),
            merged(CORRECTED, MISHEARD),
            merged(CORRECTED, WATERMARK),
            minted(STRAY, NodeKind.WORK, "a stray liner note"),
            merged(STRAY, DETOUR),
            owned(WREN, DETOUR, "INFLUENCED_BY"),
            merged(STRAY, REROUTED),
            minted(LAPSE, NodeKind.WORK, "a working title he took back"),
            merged(LAPSE, FORFEIT),
            owned(WREN, FORFEIT, "INFLUENCED_BY"),
            retract(LAPSE),
            // #228: the merge re-declared onto the id the retraction already emptied, and an owner
            // edge naming the retracted LOCAL id after it. The edge reaches FORFEIT through
            // canonicalByLocal rather than by name, which is the case Equivalences
            // .namesARetractedStandIn missed while it read a claim's raw endpoints.
            merged(LAPSE, FORFEIT),
            owned(WREN, LAPSE, "INFLUENCED_BY"));
  }

  /** Everything {@link #ownedLog} names, including both canonical ids a merge introduces. */
  private static final List<String> OWNED_QIDS =
      List.of(
          WREN,
          MARLOW,
          KETTLES,
          HOLLOW_TIDE,
          ALMANAC,
          DEMO,
          LEDGER,
          BYPASS,
          TWICE,
          PRESSING,
          WATERMARK,
          STANDING,
          CORRECTED,
          MISHEARD,
          STRAY,
          DETOUR,
          REROUTED,
          LAPSE,
          FORFEIT);

  @Test
  @DisplayName("both folds hold the same nodes when the owner has minted and merged an entity")
  void shouldHoldTheSameNodesWhenTheOwnerHasMintedAndMerged() {
    FakeAssertionLog log = ownedLog();
    LogProjection folded = LogProjection.of(log);

    // Two folds that both held nothing would agree perfectly. This is what says they held the
    // thing the merge produces - a canonical node for an id no source ever claimed. DETOUR is the
    // amendment's case: a later merge corrected STRAY away from it, and its stand-in survives
    // anyway because the WREN -> DETOUR edge still names it.
    assertThat(folded.nodes()).containsKeys(PRESSING, WATERMARK, DETOUR);

    // The plain half of the same rule: a correction with no surviving edge on the first canonical
    // leaves nothing there to disagree about, which is exactly what makes the half-fix control
    // below meaningful - it is this key, alone, that a fix to standIns without a fix to
    // IngestService gets wrong.
    assertThat(folded.nodes()).doesNotContainKey(MISHEARD);

    // #224: the retraction took the merge, and the merge was the only thing holding a node under
    // FORFEIT. The local id goes too - its own claim is what the retraction names.
    assertThat(folded.nodes()).doesNotContainKey(FORFEIT);
    assertThat(folded.nodes()).doesNotContainKey(LAPSE);

    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(log, replayed, IdentityMerge.NONE);

      for (String qid : OWNED_QIDS) {
        Optional<NodeRecord> inGraph = replayed.node(qid);
        assertThat(inGraph.isPresent())
            .as("replayed graph holds %s, exported fold holds: %s", qid, folded.nodes().keySet())
            .isEqualTo(folded.nodes().containsKey(qid));
        if (inGraph.isPresent()) {
          assertThat(describe(folded.nodes().get(qid)))
              .as("both folds call %s the same thing", qid)
              .isEqualTo(describe(inGraph.get()));
        }
      }
    }
  }

  @Test
  @DisplayName("both folds hold the same edges when the owner has minted and merged an entity")
  void shouldHoldTheSameEdgesWhenTheOwnerHasMintedAndMerged() {
    FakeAssertionLog log = ownedLog();
    Set<String> folded =
        LogProjection.of(log).edges().stream()
            .map(BothFoldsAgreeTest::key)
            .collect(Collectors.toSet());

    // Same argument as the nodes test: two empty sets agree about nothing. Both ends of the
    // merged local id have to have reached the canonical id for this comparison to mean anything,
    // and these two are the out-side and the in-side of it.
    assertThat(folded)
        .contains(
            WATERMARK + " INFLUENCED_BY " + HOLLOW_TIDE, MARLOW + " INFLUENCED_BY " + WATERMARK);

    // The amendment's surviving edge: claimed directly against DETOUR while it stood as STRAY's
    // canonical id, and it names DETOUR either way - not a local id the fold would otherwise
    // resolve - so this is unchanged by the fold and both folds must still hold it.
    assertThat(folded).contains(WREN + " INFLUENCED_BY " + DETOUR);

    // Two local ids merged onto ONE canonical id, with an owner edge between them: folding both
    // ends gives an edge from that id to itself. A self-loop is a claim that a thing relates to
    // itself, which neither a source nor the owner ever made - so the fold drops it, in both
    // folds, rather than inventing evidence out of an equivalence (#178).
    assertThat(folded)
        .as(
            "an equivalence collapses two ids into one thing; it does not make that thing relate"
                + " to itself, and Scorer's degree and find_paths would both read the self edge")
        .doesNotContain(PRESSING + " INFLUENCED_BY " + PRESSING);

    assertThat(folded)
        .as(
            "the owner claimed this against a stand-in a retraction has since taken away, so it"
                + " goes with it rather than replaying into nothing (#224)")
        .doesNotContain(WREN + " INFLUENCED_BY " + FORFEIT);

    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      long applied = GraphProjector.project(log, replayed, IdentityMerge.NONE);

      Set<String> inGraph =
          OWNED_QIDS.stream()
              .flatMap(qid -> replayed.edges(qid).stream())
              .map(BothFoldsAgreeTest::key)
              .collect(Collectors.toSet());

      assertThat(inGraph).isEqualTo(folded);
      assertThat(replayed.edgeCount()).isEqualTo(folded.size());
      assertThat(applied)
          .as(
              "the count is what reached the graph, not what survived the retractions - the"
                  + " self-loop the fold collapsed still applies nothing (#178), and none of"
                  + " LAPSE's four new rows land either: its own minted claim is retracted"
                  + " backwards along with everything else about it, the merge does not survive"
                  + " the retraction, the WREN -> FORFEIT edge is withdrawn because it names the"
                  + " stand-in the retraction emptied, and the retraction row itself is never"
                  + " applied (#224). The re-merge #228 added onto the same canonical id applies"
                  + " nothing to the graph either - its local side does not survive the"
                  + " retraction, so standIn() finds no node to copy - but a SameAs counts as"
                  + " applied whether or not it builds one, so it still counts toward this total."
                  + " The owner edge #228 added beside it does not: it names the retracted local"
                  + " id, which folds onto the same emptied canonical id through that surviving"
                  + " re-merge, so it is withdrawn for the same reason as WREN -> FORFEIT above."
                  + " So it is every row in this log but those five and that one owner edge.")
          .isEqualTo(30);
    }
  }

  private static String describe(NodeRecord node) {
    return node.kind() + " \"" + node.label() + "\"";
  }

  @Test
  @DisplayName("the boot replay and the exporter's fold hold the same nodes after retractions")
  void bothFoldsKeepTheSameNodes() {
    FakeAssertionLog log = awkwardLog();
    LogProjection folded = LogProjection.of(log);

    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(log, replayed, IdentityMerge.NONE);

      for (String qid : List.of(WREN, KETTLES, HOLLOW_TIDE, MARLOW)) {
        assertThat(replayed.node(qid).isPresent())
            .as("replayed graph holds %s, exported fold holds it: %s", qid, folded.nodes().keySet())
            .isEqualTo(folded.nodes().containsKey(qid));
      }
    }
  }

  @Test
  @DisplayName("the boot replay and the exporter's fold hold the same edges after retractions")
  void bothFoldsKeepTheSameEdges() {
    FakeAssertionLog log = awkwardLog();
    Set<String> folded =
        LogProjection.of(log).edges().stream()
            .map(BothFoldsAgreeTest::key)
            .collect(Collectors.toSet());

    try (TinkerGraphStore replayed = new TinkerGraphStore()) {
      GraphProjector.project(log, replayed, IdentityMerge.NONE);

      Set<String> inGraph =
          folded.isEmpty()
              ? Set.of()
              : LogProjection.of(log).nodes().keySet().stream()
                  .flatMap(qid -> replayed.edges(qid).stream())
                  .map(BothFoldsAgreeTest::key)
                  .collect(Collectors.toSet());

      assertThat(inGraph).isEqualTo(folded);
      assertThat(replayed.edgeCount()).isEqualTo(folded.size());
    }
  }

  private static String key(EdgeRecord edge) {
    return edge.fromQid() + " " + edge.typeCode() + " " + edge.toQid();
  }
}
