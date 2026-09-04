package com.robsartin.segue.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Which local ids the owner has said are really something else, and what the taste layer does about
 * it (#92).
 *
 * <p><b>The sibling of {@link Retractions}, and it exists for the same reason.</b> A merge is one
 * claim in the log with consequences in more than one reader, and two readers that each carried
 * their own idea of what a merge reaches would be free to disagree. {@code recommend} and {@code
 * rate} both ask this type the same two questions, so the known-list and the deck cannot answer
 * them differently.
 *
 * <p><b>The graph half of a merge has its rule here, and both folds now call it.</b> {@code
 * IngestService.carry} used to <em>copy</em> the edges onto the canonical id and leave them on the
 * local id too, so two nodes carried one entity's edges and every neighbour of a merged entity had
 * one more incident edge than the world justified — the defect issue #178 measured, in {@code
 * docs/superpowers/specs/2026-09-02-merge-degree-design.md}, worth up to 12.5 % of a candidate's
 * score and enough to unseat rank 1. {@link #foldEndpoints} is the fix: {@code IngestService.apply}
 * reads every endpoint through it as the claim is applied, and {@code LogProjection} reads every
 * endpoint through it as the claim is folded, so the edges exist once and the copy is gone.
 *
 * <p>The local <em>node</em> stays exactly where it was either way, so a route or a log entry
 * recorded last month still resolves — that is what "a merged local id stays resolvable" means, and
 * it is only the edges that move. What is left over is the taste layer, which is keyed by qid and
 * is rebuilt by nothing: after a merge there are <b>two</b> affinity rows naming one thing, and
 * both were live.
 *
 * <p><b>Measured before this was written</b>, on an invented graph where one minted entity reaches
 * one candidate through one intermediate. The rating was 5 on the local id, and the merge carried
 * it to the canonical id as {@code IdentityMerge.carryingRatings} does:
 *
 * <pre>
 *   BEFORE merge   candidate 0.2236, 1 shared intermediate
 *   AFTER  merge   candidate 0.4332, 2 shared intermediates
 * </pre>
 *
 * <p>{@code KnownList.promoted} promotes both rows, the graph held both nodes carrying the same
 * edges until #178 folded them onto one, and the owner's one opinion is counted twice. The second
 * face of the same defect is that where only the canonical id is rated, the local entity
 * <em>becomes a candidate</em> — in the same fixture it ranked first, above the entity the run
 * existed to find.
 *
 * <p><b>Nothing is deleted, here or anywhere.</b> ADR 19 makes the log append-only and {@code
 * AffinityStore} has no delete (ADR 39): the local row is what the owner actually said at the time
 * and it stays. This type resolves the two rows into one <em>view</em> at read time, which is the
 * same shape {@link Retractions} uses to drop a row from a fold without touching the log.
 *
 * <p><b>There are no chains to follow, and the check that guarantees it is the one that actually
 * runs here.</b> One hop is the whole of the resolution because a canonical id can never itself be
 * the local side of another merge — but the reason is <em>not</em> {@code SameAs.declared}'s
 * two-leading-zeros convention, which this class never sees: {@link #in} is fed {@code
 * AssertionLog.readAll}, the reconstruction path, where the factory is not called and only the
 * canonical constructor runs. What runs there is the pair that cannot be re-tightened by this
 * project — {@link LocalEntity#checkUnallocatable} on the local side and {@link
 * Qid#checkCanonicalSide} on the canonical side — and those two are exact complements over {@code
 * Q\d+} by construction, so no id can sit on both sides of the relation whatever the convention
 * does next. If that were false, {@link #resolve} would leave a rating stranded on an intermediate
 * id.
 *
 * <p><b>Complementary, not "allocatable versus unallocatable".</b> That was the same sentence until
 * ADR 62 reserved an eleven-digit shape which the grammar cannot allocate and a merge's canonical
 * side may nonetheless take. Had the local side gone on refusing only what is <em>allocatable</em>,
 * that one shape would have been legal on both sides and the argument above would have been quietly
 * false; the local check reads the same predicate instead, so the complement is restored by
 * construction rather than by coincidence.
 *
 * <p><b>One local id merged onto one canonical id and then corrected onto another retires the first
 * canonical id's stand-in — unless a surviving edge still names it.</b> {@link #standIns} is {@code
 * putIfAbsent} and keyed by canonical id, so the first merge names the stand-in; {@link
 * #canonicalByLocal} is last-wins, so {@link #foldEndpoints} sends the edges to the last. {@link
 * #stands} is what decides whether the first canonical id's stand-in is built at all: it answers
 * false — no node — for a superseded merge no surviving edge references, and true — the node
 * stands, though its own edges have all folded onto the last id — for one a surviving edge does
 * reference, because {@code OwnRun} can offer a merge's canonical id as an endpoint the moment its
 * stand-in exists, and a claim made against it before the correction survives the correction (ADR
 * 19, #221 fix round 1). The rating carry does not follow this widening: {@link #last} is its own,
 * narrower predicate, so a rating is carried only onto the id a local id resolves to TODAY.
 *
 * @param canonicalByLocal each merged local id, and the id it turned out to be, <b>in log
 *     order</b>. Last claim wins when one local id was merged twice — the same "what had we already
 *     been told" reading {@link Retractions} takes, and the only one that lets a wrong merge be
 *     corrected by a later one. The order is preserved rather than copied away by {@code
 *     Map.copyOf}, whose iteration order is unspecified and salted per JVM: {@link #resolve}
 *     iterates this map, so an unordered copy would let two runs over one unchanged log disagree
 *     about which of two collided ratings survives. That is the byte-identical-output argument
 *     {@code KnownList.promoted} makes for its own sort, crediting ADR 43
 * @param referencedEndpoints every id a surviving {@link AssertionRecord} or {@link OwnerEdge}
 *     names as {@code fromQid} or {@code toQid} (#221 fix round 1). <b>Insertion-ordered, and no
 *     answer depends on the order:</b> {@link #stands} only ever asks it {@code contains}, never
 *     iterates it. The order is kept all the same — the compact constructor and {@link #in} both
 *     build a {@code LinkedHashSet} — for a narrower reason than {@code canonicalByLocal}'s, which
 *     is that a result changes: this is a record, so the set is printed by {@code toString}
 *     whenever an assertion over an {@code Equivalences} fails, and a salted iteration would print
 *     one unchanged log two ways on two JVMs. {@code Set} equality is order-blind, so {@code
 *     equals} reads the same either way
 * @param retractedStandIns the canonical ids a retraction emptied (#224): a merge named each of
 *     them and a retraction of that merge's LOCAL side dropped it, and nothing else in the
 *     projection holds a node for the id — no surviving node claim, and no surviving merge whose
 *     stand-in it still is. {@link #foldEndpoints} yields nothing for an edge naming one, because
 *     the endpoint the edge was claimed against was the retracted entity under the name its merge
 *     gave it. Populated only by {@link #folding}; empty everywhere else, including {@link #NONE}
 *     and {@link #in}, which have no edge to fold. Insertion-ordered for {@link
 *     #referencedEndpoints}' reason - nothing reads the order, and a record's {@code toString}
 *     prints it into every failing assertion over an {@code Equivalences}
 */
public record Equivalences(
    Map<String, String> canonicalByLocal,
    Set<String> referencedEndpoints,
    Set<String> retractedStandIns) {

  /** A projection with no merges in it, named at the call site rather than defaulted. */
  public static final Equivalences NONE = new Equivalences(Map.of());

  public Equivalences {
    canonicalByLocal =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(canonicalByLocal, "canonicalByLocal")));
    referencedEndpoints =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(
                Objects.requireNonNull(referencedEndpoints, "referencedEndpoints")));
    retractedStandIns =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(Objects.requireNonNull(retractedStandIns, "retractedStandIns")));
  }

  /**
   * A caller that has the merges and the surviving edges but no fold to perform — {@link #in},
   * whose readers ask about ratings, labels and known lists and never about an edge's endpoints. An
   * empty {@link #retractedStandIns} is exactly as accurate there as a computed one: {@link
   * #foldEndpoints} is the only method that reads it, and no caller of {@link #in} calls it.
   */
  public Equivalences(Map<String, String> canonicalByLocal, Set<String> referencedEndpoints) {
    this(canonicalByLocal, referencedEndpoints, Set.of());
  }

  /**
   * A caller that has only merges to hand, and none of the surviving edges that could keep a
   * superseded stand-in alive (#221 fix round 1) — safe for every caller, including {@link #NONE}
   * and {@link #stands}'s own live-path paragraph below.
   *
   * <p><b>{@link #NONE} is asked {@link #stands} and {@link #last} — {@code IngestService.record}'s
   * live path does exactly that — and an empty {@code referencedEndpoints} still answers both
   * correctly.</b> Both methods start by asking {@link #last}, which for an {@code Equivalences}
   * that has never heard of a local id ({@code canonicalByLocal.get(localQid) == null}) answers
   * true before {@code referencedEndpoints} is ever consulted — {@link #stands}'s own "equivalences
   * that have never heard of the local id do not contradict the merge" paragraph. So the empty set
   * here changes nothing for {@link #NONE}: it never reaches the clause that would need it.
   *
   * <p>The two remaining direct callers construct an {@code Equivalences} with a real {@code
   * canonicalByLocal} and hand it on to a reader that never asks {@link #referencedEndpoints} at
   * all: {@code KnownListTest} to {@code KnownList.notOffered}, which reads {@link #merged()}, and
   * {@code RateRunTest} to {@code RateRun.buildDeck}, which reaches {@link #resolve} and {@link
   * #merged()} through the deck. So an empty set is exactly as accurate there as a computed one
   * would be — and it is the conclusion rather than the route that carries that, since neither
   * reader touches the field on any path.
   */
  public Equivalences(Map<String, String> canonicalByLocal) {
    this(canonicalByLocal, Set.of());
  }

  /**
   * Read the merges out of a log, in the order {@code AssertionLog.readAll} returns it.
   *
   * <p>A merge a retraction reaches is not one: {@link Retractions#survives} is asked the same
   * question here that both graph folds ask it, so an equivalence the graph refused to carry cannot
   * still be resolving ratings.
   *
   * <p><b>The referenced-endpoint set is built here too, from the same pass, for {@link #stands}'s
   * reason</b> (#221 fix round 1). It is every id a surviving {@link AssertionRecord} or {@link
   * OwnerEdge} names as {@code fromQid} or {@code toQid}, read RAW off the log rather than through
   * the fold: the question {@link #stands} asks is "did the owner (or a source) claim something
   * against this id while it stood as a canonical id", and folding the claim through the very
   * equivalences being computed would answer a different question — "does an edge exist against
   * whatever it resolves to today", which is {@link #canonicalByLocal}'s own question, not this
   * one.
   */
  public static Equivalences in(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    Retractions retractions = Retractions.in(log);
    Map<String, String> byLocal = new LinkedHashMap<>();
    Set<String> referenced = new LinkedHashSet<>();
    for (int i = 0; i < log.size(); i++) {
      LoggedAssertion assertion = log.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      switch (assertion) {
        case SameAs merge -> byLocal.put(merge.localQid(), merge.canonicalQid());
        case AssertionRecord edge -> {
          referenced.add(edge.fromQid());
          referenced.add(edge.toQid());
        }
        case OwnerEdge edge -> {
          referenced.add(edge.fromQid());
          referenced.add(edge.toQid());
        }
        // A node claim or a retraction names no relationship, and this set is only ever asked
        // about a canonical id's edges. Named explicitly, matching Retractions.survives and
        // IngestService.apply, rather than through a default: a default arm would let a
        // seventh LoggedAssertion that DOES carry endpoints compile silently into "names
        // nothing" and reproduce the fix-round-1 defect this method exists to close.
        case NodeAssertion ignored -> {}
        case LocalEntity ignored -> {}
        case Retraction ignored -> {}
      }
    }
    return new Equivalences(byLocal, referenced);
  }

  /**
   * A node for every canonical id a surviving merge names, to be applied <b>before</b> the log is
   * projected (#178).
   *
   * <p><b>Why before, and not at the merge's own position.</b> {@code IngestService.standIn}
   * creates the canonical node where the merge sits, which is safe only while the edges are created
   * at that same moment. Once the endpoints are folded by {@link #foldEndpoints}, an edge claimed
   * <em>earlier</em> in the log arrives on the canonical id before the merge row that would have
   * created its node, and {@code TinkerGraphStore.record} refuses an endpoint it has never seen —
   * {@code assertion references unknown entity … - upsert the node first}, at every boot, on a row
   * ADR 19 forbids deleting. Hoisting the node is therefore a prerequisite of the fold rather than
   * part of it, and it is a no-op on its own.
   *
   * <p><b>Offered whether or not a source has named the canonical entity.</b> {@code standIn} asks
   * {@code graph.node(canonical).isEmpty()} because it runs mid-log and a source that got there
   * first must not be overwritten. Applied first, the same guarantee comes free from the other
   * direction: every real claim in the log lands on top of this map and wins, by {@code
   * upsertNode}'s last-writer-wins and by {@code LogProjection}'s {@code nodes.put}. Asking the
   * question here as well would put one ordering rule in two places.
   *
   * <p><b>Read off the log, not off a store, and that is what makes this a second pass.</b> At the
   * moment this map is wanted, nothing has been projected: the store is empty and the minted node
   * whose kind and label the stand-in copies does not exist in it yet. So {@link #localsOfMerges}
   * is the projection's walk done once in advance — it tracks node claims and honours {@link
   * Retractions} exactly as the projection will — and the projection then runs as it always did.
   * Two passes over the log, one pass over the store.
   *
   * <p><b>Log order, in both senses.</b> A minted entity is read as it stood <em>when the merge was
   * made</em>, matching {@code standIn}'s "order is log order" paragraph, so a claim appended after
   * a merge is not what the merge stood in for; and where two local ids were merged onto one
   * canonical id the first merge names the stand-in, matching {@code standIn}'s creating a node
   * only where none exists. The returned map keeps log order for {@link #canonicalByLocal}'s
   * reason.
   *
   * <p><b>A merge a later merge corrected names nothing, unless a surviving edge still needs it</b>
   * (#221; widened in a later round of the same issue — see {@link #stands}). Ordinarily the last
   * merge of a local id wins, for the edges through {@link #foldEndpoints} and for the node as
   * well, so the first canonical id is not left holding a labelled node with no edges that nothing
   * claimed. But a surviving edge CAN claim it directly, made while it still stood as the canonical
   * id, and dropping its node then would leave that edge with an endpoint the store has never seen
   * — so {@link #stands} answers true for exactly that case, and the stand-in survives holding the
   * merged entity's label and the edge, nothing more.
   *
   * <p><b>Two local ids merged onto ONE canonical id are not always untouched by the widening, and
   * the exact case is worth stating rather than waved at.</b> Say local A merged onto X and was
   * later corrected away from it, and local B also merged onto X and still stands there today.
   * Where a surviving edge names X directly, A's now-superseded merge row contributes to this map
   * again — {@link #stands} answers true for it too — exactly as B's does, and {@code
   * putIfAbsent}'s first-in-log-order rule decides between A's label and B's, restoring for that
   * one pairing the answer this method gave before #221 ever filtered by {@link #stands} at all.
   * Where no surviving edge names X, A's merge contributes nothing and B's label wins outright,
   * whatever the log order. Either way it is the one {@code putIfAbsent} below, not a third rule.
   *
   * <p><b>The stand-in rule has four homes, and they are named here so that the count is not
   * guessed at.</b> "The canonical id gains a node carrying the merged entity's label where nothing
   * has claimed one" is written out in {@link #standIns} (this method, over the log, for both
   * folds), {@code IngestService.standIn} (over the running graph, live path only), {@code
   * OwnRun.labelsInTheProjection} (its own {@code !labels.containsKey(canonicalQid)} copy, so the
   * tool offers the canonical id as an endpoint) and {@code ratings/Labels.forQids} (so a carried
   * canonical row is not listed as "not in the graph"). The last two read labels off the log rather
   * than nodes off a graph, which is why they are copies rather than callers.
   *
   * <p><b>They agree wherever they are asked the same equivalences, which is not everywhere</b>
   * (#221). All four ask {@link #stands}; three of them ask it of {@code Equivalences.in(log)} and
   * {@code IngestService.standIn} asks it of {@link #NONE}, whose {@code stands} is unconditionally
   * true because it holds no log to contradict the merge in front of it. So for a local id merged
   * twice, the three that read the log name no stand-in under the superseded canonical id and the
   * live one still builds one - the same shape as ADR 42's kind lag, and lagging until the next
   * boot for the same reason: the live path applies one claim, and the fold applies the whole log.
   * Nothing in production reaches it, because {@code IngestService.record}'s own javadoc records
   * that nothing sends a {@code SameAs} there. They also do not agree condition for condition, for
   * a separate reason: this method has no "unless something claimed it" condition at all and takes
   * that guarantee from being applied first, as the paragraph above says.
   *
   * <p>{@code StandInAgreesInEveryHomeTest} is what holds them to it (issue #220): one log, four
   * homes, and one answer per canonical id in every home that reads the same equivalences, with the
   * two rows that split pinned per home and named. It pins what they do rather than claiming it is
   * right, and ADR 59's residual - four homes, not one caller - is untouched by it.
   *
   * <p>The kind this one carries now comes through {@code rederive} (#222); the other three are
   * unchanged by that, because two of them carry a label and no kind at all, and {@code
   * IngestService.standIn} copies the local node as the graph in front of it holds it - which on
   * the live path is the claim un-re-derived, because that path is not a projection (ADR 42).
   *
   * <p><b>Derived from {@link #localsOfMerges}, and that is the point.</b> "Does this merge have a
   * local side, and what does it look like?" is one question, asked by the stand-in here and by
   * {@code IngestService.standIn} on the live path. Answering it in two places is how the two folds
   * drift — which they did, briefly, when this method read {@link LocalEntity} claims while {@code
   * LogProjection} still read its own accumulator to decide whether to copy a merge's edges.
   *
   * @param rederive how the calling fold derives a node claim's kind - {@code KindMapper::rederive}
   *     from both of them, handed in because {@code domain} may not name it
   * @return each canonical id and the node to stand in for it, in log order
   */
  public static Map<String, NodeRecord> standIns(
      List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive) {
    Equivalences merges = Equivalences.in(log);
    Map<String, NodeRecord> standIns = new LinkedHashMap<>();
    for (Map.Entry<Integer, NodeRecord> at : localsOfMerges(log, rederive).entrySet()) {
      if (log.get(at.getKey()) instanceof SameAs merge && merges.stands(merge)) {
        NodeRecord local = at.getValue();
        // No instanceOf: a stand-in carries what it was given rather than inventing a class -
        // LocalEntity.toNode()'s own reason. On the bypass path the local side DID state classes,
        // and they are deliberately dropped here too (#222). Not because they would say nothing:
        // instanceOf is read on its own, independently of the kind derived from it - DotWriter
        // tooltips it and shades a WORK by it, GraphMlWriter writes it out as its own attribute -
        // so carrying the list forward would put the local side's classes on the canonical id and
        // report them as that entity's, which no source has stated about it. The kind is a
        // different case and is copied: it is what the stand-in exists to say.
        standIns.putIfAbsent(
            merge.canonicalQid(),
            new NodeRecord(merge.canonicalQid(), local.kind(), local.label(), List.of()));
      }
    }
    return Collections.unmodifiableMap(standIns);
  }

  /**
   * Every id the fold will hold a node for: the stand-ins it builds, plus every id a surviving node
   * claim or minted entity names (#228).
   *
   * <p><b>Promoted from a local, because three readers now ask it.</b> {@link #retractedStandIns}
   * asks it to decide whether a merge's canonical id is emptied; {@code IngestService.claim} asks
   * it to refuse an owner claim naming an endpoint the fold would hold no node for, before the
   * append rather than at the next boot; and {@code GraphProjector.project} asks it to name the
   * rows of a log that already carries one. A second copy of this walk is how the gate and the fold
   * would come to disagree about which entities exist, which is the one disagreement that stops the
   * application starting.
   *
   * <p><b>It is exactly {@code LogProjection.of(log).nodes().keySet()}</b>, computed without
   * folding a single edge, and exactly the node set a {@code GraphProjector} replay leaves. That is
   * asserted rather than claimed - {@code
   * BothFoldsAgreeTest.shouldNameExactlyTheNodesTheFoldHoldsWhenAskedOfOneLog} compares all three
   * over the fixture that holds every shape the third layer has.
   *
   * <p><b>No re-derivation parameter</b>, for {@link #retractedStandIns}' reason exactly: this
   * reads which ids have a node, never what kind it is, and {@link #standIns}' key set cannot
   * depend on the re-derivation.
   */
  public static Set<String> nodesTheFoldHolds(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    return Collections.unmodifiableSet(
        nodesHeld(log, standIns(log, UnaryOperator.identity()).keySet()));
  }

  /**
   * {@link #nodesTheFoldHolds}' walk, over a stand-in key set the caller has already decided.
   *
   * <p>Separate from the public method for one caller: {@link #retractedStandIns}' own computation
   * has to ask this question of a stand-in set it is still working out, and calling the public
   * method there would ask {@link #standIns} - which reads {@link #in} - in the middle of deciding
   * what {@link #in} answers.
   */
  private static Set<String> nodesHeld(List<LoggedAssertion> log, Set<String> standInIds) {
    Retractions retractions = Retractions.in(log);
    Set<String> held = new LinkedHashSet<>(standInIds);
    for (int i = 0; i < log.size(); i++) {
      LoggedAssertion assertion = log.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      switch (assertion) {
        case NodeAssertion claim -> held.add(claim.qid());
        // Unreachable from retractedStandIns' own question: a minted id is always the two-leading-
        // zero local shape (ADR 59) and a merge's canonicalQid() is always the eleven-digit
        // canonical shape (ADR 62), so this arm can never be what keeps retractedStandIns' held set
        // containing a canonical id. Kept for nodesTheFoldHolds' broader question - does an
        // ordinary, never-merged, minted entity have a node - which BothFoldsAgreeTest pins.
        case LocalEntity minted -> held.add(minted.qid());
        // An edge, a merge and a retraction all claim no node. Named explicitly rather than
        // through a default, matching Retractions.survives and Equivalences.in: a default arm
        // would let a seventh LoggedAssertion that DOES claim a node compile silently into
        // "claims nothing" and empty a canonical id the log holds.
        case AssertionRecord ignored -> {}
        case OwnerEdge ignored -> {}
        case SameAs ignored -> {}
        case Retraction ignored -> {}
      }
    }
    return held;
  }

  /**
   * The canonical ids a retraction emptied — a merge gave each of them its only node, a retraction
   * of that merge's local side took the merge away, and nothing else holds a node for the id
   * (#224).
   *
   * <p><b>Why an edge naming one does not project, and why that is ADR 44 rather than a delete.</b>
   * {@code OwnRun} offers a merge's canonical id as a claimable endpoint the moment its stand-in
   * exists, so the owner can claim an edge against it. Retracting the local id afterwards drops the
   * merge — {@link Retractions#survives} drops a {@link SameAs} on the edge rule, either side — and
   * with it the only node that id ever had. The edge survives on its own terms, names an endpoint
   * no fold holds, and {@code TinkerGraphStore.record} refuses it: {@code replay failed at sequence
   * … assertion references unknown entity … - upsert the node first}, at every boot, on rows ADR 19
   * forbids deleting. The claim was one the owner made about the entity he has just retracted,
   * written under the name his own merge gave it, so it goes with it. Nothing is deleted: the log
   * keeps every row, and this changes only what the fold makes of them.
   *
   * <p><b>Position-blind, and that is the one place this rule does NOT follow ADR 44.</b> A
   * retraction reaches backwards only, by position in the log — {@link Retractions#survives} is
   * asked about a row's index for exactly that reason — but this set is whole-log and {@link
   * #foldEndpoints} takes no index, so an edge claimed <em>after</em> the retraction that names an
   * emptied canonical id is withdrawn just the same. That is deliberate. A backwards-only rule
   * would leave the log {@code [node, minted, merge, retract, edge-naming-the-canonical-id]} naming
   * an endpoint no fold holds, and {@code TinkerGraphStore.record} would refuse it at every boot —
   * the very break this rule exists to close, re-created by the ordering rather than fixed. What is
   * emptied is emptied for the whole projection, because a node either exists in the folded graph
   * or it does not, and no edge may name one that does not. {@code
   * RetractedStandInTakesItsEdgesTest} pins both folds on that log; the design spec's 2026-09-03
   * amendment records the finding and the ruling.
   *
   * <p><b>Only the local side counts</b>, which is why {@link Retractions#reaches} exists. A merge
   * dropped because its CANONICAL side was retracted leaves nothing to repair here: that id is
   * retracted outright, and {@link Retractions#survives} has already dropped every edge naming it.
   *
   * <p><b>A canonical id {@link #nodesTheFoldHolds} already holds on its own account is not
   * emptied.</b> The developer guide's promise that "what a source claimed about the canonical id
   * is untouched" is exactly {@link #nodesTheFoldHolds}'s node-claim arm, and the merge was never
   * the only thing holding such an id up. Without this, retracting one thing the owner minted would
   * strip the edges off a real Wikidata entity's whole expansion.
   *
   * <p><b>Nor is one a surviving merge still stands in for.</b> Two local ids merged onto one
   * canonical id and only one of them retracted leaves the other merge's stand-in exactly where it
   * was, so the id has a node and the edges naming it have an endpoint. {@link #standIns} is the
   * one place that answers "which canonical ids have a stand-in", and this reads it rather than
   * deciding it again.
   *
   * <p><b>No re-derivation parameter, unlike {@link #standIns} and {@link #localsOfMerges}.</b>
   * This reads which canonical ids have a stand-in, never what kind that node is, and the key set
   * of {@link #standIns} cannot depend on the re-derivation: {@link #localsOfMerges} decides which
   * merges have a local side by survival alone and {@link #stands} reads no kind, so the operator
   * only ever sets a value this method discards. It is checked rather than asserted — {@code
   * EquivalencesTest.shouldNameTheSameCanonicalIdsWhateverKindTheFoldDerives} compares the key sets
   * under two re-derivations that disagree about every kind. The parameter is left off so that
   * {@code retract} — which needs this set for its report and must not learn Wikidata's vocabulary
   * (ADR 44: "a retraction is nobody's vocabulary") — can call it.
   */
  public static Set<String> retractedStandIns(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    Retractions retractions = Retractions.in(log);
    Set<String> held = nodesHeld(log, standIns(log, UnaryOperator.identity()).keySet());
    Set<String> emptied = new LinkedHashSet<>();
    for (int i = 0; i < log.size(); i++) {
      if (log.get(i) instanceof SameAs merge
          && retractions.reaches(i, merge.localQid())
          && !held.contains(merge.canonicalQid())) {
        emptied.add(merge.canonicalQid());
      }
    }
    return Collections.unmodifiableSet(emptied);
  }

  /**
   * The merges as a fold reads them: {@link #in}, plus the canonical ids a retraction emptied
   * (#224) — and, for one caller that is not a fold, the same answer a fold would get.
   *
   * <p><b>Named rather than an overload of {@link #in}, on {@link #localsOfMerges}' reason.</b> The
   * two folds are the only callers of {@link #foldEndpoints} that hold a log, and an overload
   * quietly giving one of them the older, edge-blind answer is how the two would drift while
   * looking identical at the call site. {@code GraphProjector.project} and {@code LogProjection.of}
   * both build their equivalences here.
   *
   * <p><b>{@code RetractRun.strandedByThisRetraction} is the third caller, and folds nothing.</b>
   * It builds an {@link Equivalences} over the log the retraction WOULD produce, purely to ask
   * {@link #namesARetractedStandIn} of every surviving claim, so that the edges its report names
   * are the edges the export will actually withdraw. It has to come through here rather than
   * through {@link #in} for the same drift reason: {@link #in} carries an empty {@link
   * #retractedStandIns}, so that predicate would answer {@code false} for everything and the report
   * would silently name nothing. Every other caller of {@link #in} — {@code OwnRun}, {@code
   * RateCli}, {@code ratings/Labels} and {@code RecommendCli} — asks about ratings, labels, known
   * lists and what to offer, and neither folds an edge nor asks that question.
   */
  public static Equivalences folding(List<LoggedAssertion> log) {
    Equivalences merges = Equivalences.in(log);
    return new Equivalences(
        merges.canonicalByLocal(), merges.referencedEndpoints(), retractedStandIns(log));
  }

  /**
   * What each surviving merge's local side looked like at the moment the merge was made (#178) —
   * the one answer to "does this merge have a local side", for everything that has to agree about
   * it.
   *
   * <p><b>One predicate, one home.</b> {@link #standIns} builds the canonical node from this, and
   * {@code IngestService.standIn} asks the running graph the same question in the same words
   * ({@code graph.node(local)}). A merge whose local id nothing has claimed is not an error and
   * carries nothing — the log is append-only, so a merge may be replayed with the claim it resolves
   * retracted, or with a retraction sitting between the two.
   *
   * <p><b>Any surviving node claim counts, not only a minted one.</b> Spec ruling 2 is explicit
   * that the fold must not assume every claim naming a merged local id came through {@code OwnCli}:
   * "a later claim naming the local id, by a path that bypasses the tool, folds onto the canonical
   * id like any other". Reading {@link LocalEntity} alone made a {@link NodeAssertion} about a
   * local-shaped id visible to one fold and not the other. It is unreachable from today's sources —
   * no source can allocate a {@code Q00} id — which is why it is a rule to state rather than a bug
   * to have shipped.
   *
   * <p><b>Node kinds come from the caller, not from the claim (#222).</b> {@code
   * KindMapper.rederive} is the identity on a claim carrying no {@code P31} classes (ADR 42), which
   * covers every {@link LocalEntity}: the owner states a kind and no classes. A {@link
   * NodeAssertion} <em>can</em> carry classes, and both folds re-derive the local node's own kind
   * from them - so for as long as this method read the claim's stated kind, a bypass claim gave a
   * stand-in of one kind beside a local node re-derived to another, and the two nodes standing for
   * one entity disagreed about what it is. Both folds read this one method, so they agreed about
   * the lagging kind and {@code BothFoldsAgreeTest} could not see it; {@code
   * StandInKindMatchesTheLocalNodeTest} compares the stand-in with the node beside it instead.
   *
   * <p><b>Which is why the re-derivation is a parameter.</b> {@code KindMapper} lives in {@code
   * wikidata}, and {@code ArchitectureTest.domainHasNoThirdPartyDependencies} allows this package
   * {@code domain}, {@code java} and {@code javax} and nothing else - not even {@code port}, so a
   * seam declared there was never available either. What is available is a {@code
   * java.util.function} type, and each fold hands in the {@code KindMapper::rederive} it already
   * applies to every node claim it folds. It is required rather than defaulted, on {@link #NONE}'s
   * reason: an overload quietly restoring the old behaviour is how a third fold would arrive with
   * the lag and nothing saying so.
   *
   * <p><b>Log order, in both senses.</b> A node is read as it stood <em>when the merge was
   * made</em>, matching {@code standIn}'s "order is log order" paragraph, so a claim appended after
   * a merge is not what the merge stood in for; and the returned map keeps log order for {@link
   * #canonicalByLocal}'s reason, which is what lets {@link #standIns} say "the first merge onto a
   * canonical id names it".
   *
   * @param rederive how the calling fold derives a node claim's kind - {@code KindMapper::rederive}
   *     from both of them, handed in because {@code domain} may not name it
   * @return the log position of each surviving merge that has a local side, and that side's node
   */
  public static Map<Integer, NodeRecord> localsOfMerges(
      List<LoggedAssertion> log, UnaryOperator<NodeAssertion> rederive) {
    Objects.requireNonNull(log, "log");
    Objects.requireNonNull(rederive, "rederive");
    Retractions retractions = Retractions.in(log);
    Map<String, NodeRecord> claimed = new LinkedHashMap<>();
    Map<Integer, NodeRecord> atMerge = new LinkedHashMap<>();
    for (int i = 0; i < log.size(); i++) {
      LoggedAssertion assertion = log.get(i);
      if (!retractions.survives(i, assertion)) {
        continue;
      }
      switch (assertion) {
        case LocalEntity local -> claimed.put(local.qid(), local.toNode());
        case NodeAssertion claim -> claimed.put(claim.qid(), rederive.apply(claim).toNode());
        case SameAs merge -> {
          NodeRecord local = claimed.get(merge.localQid());
          if (local != null) {
            atMerge.put(i, local);
          }
        }
        default -> {
          // An edge claim or a retraction says nothing about which entities exist.
        }
      }
    }
    return Collections.unmodifiableMap(atMerge);
  }

  /**
   * The same claim with both of its endpoints read through the equivalences (#178).
   *
   * <p><b>This is the graph half of a merge, and it lives here for the reason the taste half
   * does.</b> The log is folded in two places — {@code GraphProjector} at boot (ADR 24) and {@code
   * LogProjection} for the exporter (ADR 41) — and a rule written twice is a rule that can be
   * corrected once. {@code BothFoldsAgreeTest} exists because those two have drifted before; this
   * method is what makes drifting impossible rather than merely detectable.
   *
   * <p><b>It is also the only place outside {@code sqlite} that may build an owner claim from
   * parts.</b> {@code ArchitectureTest.ownerClaimsAreMadeThroughTheirFactories} fences {@link
   * OwnerEdge}'s constructor to {@code domain} and {@code sqlite} (#92), and a fold written inside
   * either projection would break that rule — correctly, because a fold that reached the
   * constructor from {@code ingest} would be one more maker of an owner claim outside the one place
   * the convention lives. The canonical constructor rather than {@link OwnerEdge#claimed} is the
   * right half here for {@code readRow}'s reason: this is reconstruction of a claim already made,
   * and re-running the vocabulary check would let a retired edge type take out boot replay on a row
   * ADR 19 forbids deleting.
   *
   * <p><b>Only the endpoints move.</b> The type, the validity interval, the provenance and the kind
   * of claim it is are all left alone: an equivalence says <em>which id</em>, not what was claimed,
   * when, or by whom. A claim that is not an edge is returned untouched — a merged local id keeps
   * its own node (ADR 59's merge bullet), and folding a {@link SameAs} onto itself would rewrite
   * the claim that states the equivalence.
   *
   * <p><b>An edge whose two ends land on one id is dropped, and that is the one thing here that is
   * a decision rather than a rewrite.</b> The owner minting one thing twice and later saying so is
   * a real path, and an edge he claimed between the two folds to an edge from the canonical id to
   * itself. A self-loop is a claim that a thing relates to itself, which neither he nor any source
   * ever made — so the fold would be manufacturing evidence out of an equivalence, and {@code
   * Scorer}'s degree and {@code find_paths} would both read it. The edge is dropped instead. Task
   * 5's ADR amendment records the decision.
   *
   * <p><b>The second thing that yields nothing is a retraction, and it is a decision too</b>
   * (#224). An edge naming a {@link #retractedStandIns} id was claimed against a stand-in a
   * retraction has taken away, so the fold has no endpoint for it and neither would the store. It
   * is dropped rather than replayed into nothing: see that component, and ADR 44's 2026-09-03
   * amendment for why tolerating it as a dangling edge was refused a second time.
   *
   * <p><b>A self-loop the fold did not create is left exactly where it is</b>, which is why the
   * check is on {@code from.equals(to)} <em>after</em> the untouched-claim shortcut above rather
   * than on the folded pair alone. {@link AssertionRecord} does not forbid a claim from an entity
   * to itself, so one could already be in the log and in the graph, and nothing about #178 changed
   * that. Refusing it here would be an unrelated rule wearing this method's name; if the repo wants
   * that rule it belongs on the record, where every writer would meet it.
   *
   * <p><b>One hop, and the class javadoc says why there can never be two.</b>
   */
  public Optional<LoggedAssertion> foldEndpoints(LoggedAssertion assertion) {
    Objects.requireNonNull(assertion, "assertion");
    return switch (assertion) {
      case AssertionRecord claim -> foldEndpoints(claim).map(folded -> folded);
      case OwnerEdge owned ->
          // Delegated rather than repeated: the three branches - unchanged, collapsed, resolved -
          // are the AssertionRecord overload's, and an owner edge answers them by lending it its
          // two endpoints. What comes back is rebuilt AS an owner edge, because IngestService.apply
          // switches on the kind of claim and a folded owner edge attributed to a source would be
          // credited to a witness who never said it.
          foldEndpoints(owned.toAssertion())
              .map(
                  folded ->
                      folded.fromQid().equals(owned.fromQid())
                              && folded.toQid().equals(owned.toQid())
                          ? owned
                          : new OwnerEdge(
                              folded.fromQid(),
                              folded.toQid(),
                              owned.typeCode(),
                              owned.assertedAt()));
      default -> Optional.of(assertion);
    };
  }

  /**
   * The same edge claim with both of its endpoints read through the equivalences (#178) — {@link
   * #foldEndpoints(LoggedAssertion)} for a caller that already knows it holds a sourced edge, so
   * that the exporter's fold needs no cast to ask its {@code edgeKey}.
   *
   * <p><b>Both arms of the general method delegate here</b>, so there is one rule and not two: a
   * sourced edge is folded by this method directly, and an owner edge lends it its two endpoints
   * and is rebuilt from the answer. All four outcomes — withdrawn, unchanged, collapsed and
   * resolved — are decided in one place.
   */
  public Optional<AssertionRecord> foldEndpoints(AssertionRecord claim) {
    Objects.requireNonNull(claim, "claim");
    if (namesARetractedStandIn(claim)) {
      // A retraction took away the merge that gave this endpoint its only node (#224). Above the
      // shortcut below, deliberately: such an edge usually names no merged id at all, so the
      // "most of a log names nothing merged" fast path would return it unchanged. Above the
      // resolution too, because the endpoint this edge names is going nowhere - there is nothing
      // to resolve it to.
      return Optional.empty();
    }
    String from = canonical(claim.fromQid());
    String to = canonical(claim.toQid());
    if (from.equals(claim.fromQid()) && to.equals(claim.toQid())) {
      // Most of a log names no merged id at all, and a copy of every row would be waste.
      return Optional.of(claim);
    }
    if (from.equals(to)) {
      // The fold collapsed two distinct ids onto one. See this method's javadoc: an equivalence
      // says two names are one thing, and it does not go on to claim that the thing relates to
      // itself.
      return Optional.empty();
    }
    return Optional.of(
        new AssertionRecord(
            from, to, claim.typeCode(), claim.validFrom(), claim.validTo(), claim.provenance()));
  }

  /**
   * Whether this edge names a canonical id a retraction emptied, and is therefore withdrawn rather
   * than folded (#224).
   *
   * <p><b>One home for the question, because three readers ask it.</b> {@link #foldEndpoints} asks
   * it to decide what to yield; {@code LogProjection} asks it to decide what to count — the export
   * has to say how many edges it withdrew, and a withdrawn edge never reaches its missing-endpoint
   * check because the fold yielded nothing for it; and {@code RetractRun.strandedByThisRetraction}
   * asks it to decide what to warn the operator about before the retraction is appended, which is
   * how that report and that count come to agree by construction rather than by two people counting
   * alike. Reading {@link #retractedStandIns} twice would put "what withdrawal means" in two
   * places, which is this class's own standing objection.
   *
   * <p><b>It asks about the endpoints the fold resolves, not the ones the claim wrote</b> (#228).
   * An edge naming a merged local id whose merge points at an emptied canonical id is claimed
   * against the same absent endpoint as one that names that id directly - the endpoint the fold
   * would give it is the entity the retraction took away - so the raw read let the rule miss its
   * own case: {@code [minted(L), merged(L to A), retract(L), merged(L to A), owned(WREN to L)]}
   * threw {@code replay failed at sequence 6} on {@code a7c3455} while {@code retractedStandIns}
   * already named {@code A}. Reading through {@link #canonical} costs nothing where no merge is
   * involved, because that map answers with the id it was given.
   */
  public boolean namesARetractedStandIn(AssertionRecord claim) {
    Objects.requireNonNull(claim, "claim");
    return retractedStandIns.contains(canonical(claim.fromQid()))
        || retractedStandIns.contains(canonical(claim.toQid()));
  }

  /**
   * Whether this merge still contributes a node — {@link #last} OR a surviving edge names its
   * canonical id (#221, fix round 1 widening the original last-wins-only rule).
   *
   * <p><b>The widening exists because a legal, supported-flow log could not be replayed.</b> The
   * original rule was last-wins alone: {@link #standIns} named a stand-in for every surviving merge
   * whose canonical id was the CURRENT one, so a local id merged onto one canonical id and then
   * onto another left nothing under the first at all. That is correct while nothing else in the log
   * ever names the first canonical id — but {@code OwnRun} offers a merge's canonical id as an
   * endpoint the moment its stand-in exists, so the owner can claim an edge against it
   * <em>before</em> the correction arrives. The edge survives the correction (ADR 19: nothing is
   * deleted), the endpoint it names does not exist under last-wins-only, and {@code
   * TinkerGraphStore.record} refuses it — the boot replay a controller reproduced: {@code replay
   * failed at sequence 4 … assertion references unknown entity … - upsert the node first}, on a row
   * nothing can be dropped from ADR 19 makes append-only. A superseded canonical id whose stand-in
   * a surviving edge still needs is not an orphan — it has an edge, and the export shows exactly
   * the claim the owner made while it stood.
   *
   * <p><b>Two ways to rewrite the edge instead were rejected.</b> Re-pointing it onto the corrected
   * canonical would silently rewrite what the owner actually claimed — he named the <em>first</em>
   * id, and the first id may itself turn out to be a real, distinct entity the correction says
   * nothing about. Having {@code GraphProjector} tolerate the missing endpoint as a dangling edge
   * would replay the claim into nothing without saying so — the same silent data-loss shape issue
   * #101 already fixed once for the rating deck. Surviving the stand-in is the only option that
   * keeps every claim, changes no id, and fails loudly if it cannot.
   *
   * <p><b>The rating carry does NOT follow this widening — see {@link #last}.</b> A node surviving
   * because an edge names it is a fact about the graph; a rating is the owner's opinion about the
   * thing he corrected himself onto, and only the merge that stands today is entitled to carry it.
   * Widening {@code stands} without narrowing the carry separately would have written a rating onto
   * every canonical id a local id ever touched, which is the very defect the original {@link
   * #last}-only rule fixed in a previous round of this issue.
   *
   * <p><b>Equivalences that have never heard of the local id do not contradict the merge, so the
   * answer is true.</b> That is not a convenience for the empty case: {@code IngestService.record}
   * applies a claim with {@link #NONE}, because it sees one claim and not a log, and a {@code
   * SameAs} arriving there must go on getting its canonical node or the running graph is left with
   * an endpoint it has never heard of — which is the whole job of {@code IngestService.standIn}. A
   * caller holding the log gets the last-wins-or-referenced answer; a caller holding no log gets
   * the merge it was handed.
   *
   * <p><b>A retracted merge is not exempted from this rule — it is filtered out before reaching
   * it.</b> {@link #canonicalByLocal} is built only from surviving rows, so a retracted merge's own
   * canonical id is not what decides its answer here: where the same local id was merged again by a
   * row that survives, the map holds that later canonical. {@link #referencedEndpoints} is built
   * from surviving edges only, for the same reason — a retracted edge claims nothing and keeps
   * nothing alive. Every home of the stand-in rule asks {@link Retractions#survives} before it asks
   * this one, so a retracted row never actually reaches here on its own account; {@link
   * #localsOfMerges} does the filtering for both folds.
   */
  public boolean stands(SameAs merge) {
    Objects.requireNonNull(merge, "merge");
    return last(merge) || referencedEndpoints.contains(merge.canonicalQid());
  }

  /**
   * Whether these equivalences still point at this merge's canonical id — {@link
   * #canonicalByLocal}'s last-wins rule alone, asked about one row (#221).
   *
   * <p><b>The rating carry's own predicate, and deliberately narrower than {@link #stands}.</b>
   * {@code IngestService.apply} keys {@code merges.follow} on this method, not on {@link #stands}:
   * a superseded canonical id's stand-in may survive because a surviving edge names it, but the
   * rating is not a claim about that node — it is the owner's opinion about the thing he corrected
   * himself onto, and only the merge that resolves the local id TODAY is entitled to carry it.
   * Every merge of one local id would otherwise ask to carry the rating on every replay, which is
   * the exact defect a previous round of this issue fixed by keying the carry on this predicate to
   * begin with.
   */
  public boolean last(SameAs merge) {
    Objects.requireNonNull(merge, "merge");
    String canonical = canonicalByLocal.get(merge.localQid());
    return canonical == null || canonical.equals(merge.canonicalQid());
  }

  /**
   * What this id turned out to be, or the id itself where the owner has said nothing.
   *
   * <p><b>Public so a bucketing caller can ask the same question the fold answers</b> (#228).
   * {@code RetractRun.strandedByThisRetraction} groups the edges a retraction newly strands by
   * canonical id, and it has to group them by the id {@link #namesARetractedStandIn} actually
   * matched against — this one — rather than by {@code claim.fromQid()}/{@code claim.toQid()} as
   * the claim wrote them, or an edge naming a local id folds onto the right emptied id for the
   * predicate and the wrong one for the bucket, and lands in no line at all.
   */
  public String canonical(String qid) {
    return canonicalByLocal.getOrDefault(qid, qid);
  }

  /**
   * The local ids that have been merged — everything that must stop being offered.
   *
   * <p>The local side only. The canonical id is a real entity the owner may well want recommended
   * to him if he has not rated it; what he cannot be offered is the id he himself retired.
   */
  public Set<String> merged() {
    return canonicalByLocal.keySet();
  }

  /**
   * The ratings as they read through the equivalences: one row per thing, not one per id.
   *
   * <p>Each merged local id leaves the map, and its rating lands on the canonical id <b>only when
   * the canonical id has none</b>. That order is not a tiebreak invented here — {@code
   * IdentityMerge.carryingRatings} has already applied ADR 39's "the later rating wins" using the
   * {@code updatedAt} column, and written its answer to the store. This method is handed {@code
   * AffinityStore.readRatings}, which carries no timestamps at all, so re-deciding the question
   * here could only decide it worse. Where the store has an answer for the canonical id, that
   * answer is the one the owner's own most recent word produced.
   *
   * <p>Filling in an <em>absent</em> canonical rating is a repair rather than a duplicate of that
   * rule: {@code recommend} and {@code rate} replay with {@code IdentityMerge.NONE}, so a merge
   * logged since the last boot has had nothing carry its rating yet, and without this the owner's
   * rating would simply vanish from the run.
   *
   * <p><b>Two rated local ids merged into one canonical id: the first merge in the log wins, and
   * that is arbitrary rather than reasoned.</b> Collapsing them to one is the right answer — it is
   * what the owner said they are — but neither rating has a better claim than the other and there
   * is nothing here to choose between them with: {@code readRatings} carries no timestamps, so "the
   * later one" cannot be asked, and "the higher one" would be this class inventing an opinion about
   * taste. What <em>is</em> guaranteed is that the choice is the same on every run, which is the
   * whole reason this map keeps log order. The case is also narrow: it arises only where the
   * canonical id has no stored rating at all, and {@code IdentityMerge.carryingRatings} has already
   * settled it by {@code updatedAt} for every merge a boot has carried.
   *
   * @param ratings qid to a rating from 1 to 5 — {@code AffinityStore.readRatings}, note-free by
   *     construction, which is what keeps this method in {@code domain}
   */
  public Map<String, Integer> resolve(Map<String, Integer> ratings) {
    Objects.requireNonNull(ratings, "ratings");
    if (canonicalByLocal.isEmpty()) {
      return Map.copyOf(ratings);
    }
    Map<String, Integer> resolved = new LinkedHashMap<>(ratings);
    for (Map.Entry<String, String> merge : canonicalByLocal.entrySet()) {
      Integer local = resolved.remove(merge.getKey());
      if (local != null) {
        resolved.putIfAbsent(merge.getValue(), local);
      }
    }
    return Map.copyOf(resolved);
  }
}
