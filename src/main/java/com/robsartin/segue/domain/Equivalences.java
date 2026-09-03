package com.robsartin.segue.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * Qid#checkAllocatable} on the canonical side — and allocatable and unallocatable are complementary
 * by construction, so no id can sit on both sides of the relation whatever the convention does
 * next. If that were false, {@link #resolve} would leave a rating stranded on an intermediate id.
 *
 * @param canonicalByLocal each merged local id, and the id it turned out to be, <b>in log
 *     order</b>. Last claim wins when one local id was merged twice — the same "what had we already
 *     been told" reading {@link Retractions} takes, and the only one that lets a wrong merge be
 *     corrected by a later one. The order is preserved rather than copied away by {@code
 *     Map.copyOf}, whose iteration order is unspecified and salted per JVM: {@link #resolve}
 *     iterates this map, so an unordered copy would let two runs over one unchanged log disagree
 *     about which of two collided ratings survives. That is the byte-identical-output argument
 *     {@code KnownList.promoted} makes for its own sort, crediting ADR 43
 */
public record Equivalences(Map<String, String> canonicalByLocal) {

  /** A projection with no merges in it, named at the call site rather than defaulted. */
  public static final Equivalences NONE = new Equivalences(Map.of());

  public Equivalences {
    canonicalByLocal =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(Objects.requireNonNull(canonicalByLocal, "canonicalByLocal")));
  }

  /**
   * Read the merges out of a log, in the order {@code AssertionLog.readAll} returns it.
   *
   * <p>A merge a retraction reaches is not one: {@link Retractions#survives} is asked the same
   * question here that both graph folds ask it, so an equivalence the graph refused to carry cannot
   * still be resolving ratings.
   */
  public static Equivalences in(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
    Retractions retractions = Retractions.in(log);
    Map<String, String> byLocal = new LinkedHashMap<>();
    for (int i = 0; i < log.size(); i++) {
      if (log.get(i) instanceof SameAs merge && retractions.survives(i, merge)) {
        byLocal.put(merge.localQid(), merge.canonicalQid());
      }
    }
    return new Equivalences(byLocal);
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
   * <p><b>Derived from {@link #localsOfMerges}, and that is the point.</b> "Does this merge have a
   * local side, and what does it look like?" is one question, asked by the stand-in here and by
   * {@code IngestService.standIn} on the live path. Answering it in two places is how the two folds
   * drift — which they did, briefly, when this method read {@link LocalEntity} claims while {@code
   * LogProjection} still read its own accumulator to decide whether to copy a merge's edges.
   *
   * @return each canonical id and the node to stand in for it, in log order
   */
  public static Map<String, NodeRecord> standIns(List<LoggedAssertion> log) {
    Map<String, NodeRecord> standIns = new LinkedHashMap<>();
    for (Map.Entry<Integer, NodeRecord> at : localsOfMerges(log).entrySet()) {
      if (log.get(at.getKey()) instanceof SameAs merge) {
        NodeRecord local = at.getValue();
        // No instanceOf: a stand-in carries what it was given rather than inventing a class -
        // LocalEntity.toNode()'s own reason, and the owner stated no classes.
        standIns.putIfAbsent(
            merge.canonicalQid(),
            new NodeRecord(merge.canonicalQid(), local.kind(), local.label(), List.of()));
      }
    }
    return Collections.unmodifiableMap(standIns);
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
   * <p><b>Node kinds are taken as the claim stated them.</b> {@code KindMapper.rederive} is the
   * identity on a claim carrying no {@code P31} classes (ADR 42), and a claim that carries classes
   * is a source's, about an id no source can allocate. So there is nothing here to re-derive, and
   * {@code domain} does not learn about {@code wikidata} to say so. If that ever became false, a
   * stand-in's kind would lag the local node's own.
   *
   * <p><b>Log order, in both senses.</b> A node is read as it stood <em>when the merge was
   * made</em>, matching {@code standIn}'s "order is log order" paragraph, so a claim appended after
   * a merge is not what the merge stood in for; and the returned map keeps log order for {@link
   * #canonicalByLocal}'s reason, which is what lets {@link #standIns} say "the first merge onto a
   * canonical id names it".
   *
   * @return the log position of each surviving merge that has a local side, and that side's node
   */
  public static Map<Integer, NodeRecord> localsOfMerges(List<LoggedAssertion> log) {
    Objects.requireNonNull(log, "log");
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
        case NodeAssertion claim -> claimed.put(claim.qid(), claim.toNode());
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
      case OwnerEdge owned -> {
        String from = canonical(owned.fromQid());
        String to = canonical(owned.toQid());
        if (from.equals(owned.fromQid()) && to.equals(owned.toQid())) {
          yield Optional.of(owned);
        }
        yield from.equals(to)
            ? Optional.empty()
            : Optional.of(new OwnerEdge(from, to, owned.typeCode(), owned.assertedAt()));
      }
      default -> Optional.of(assertion);
    };
  }

  /**
   * The same edge claim with both of its endpoints read through the equivalences (#178) — {@link
   * #foldEndpoints(LoggedAssertion)} for a caller that already knows it holds a sourced edge, so
   * that the exporter's fold needs no cast to ask its {@code edgeKey}.
   *
   * <p>The general method delegates here, so there is one rule and not two.
   */
  public Optional<AssertionRecord> foldEndpoints(AssertionRecord claim) {
    Objects.requireNonNull(claim, "claim");
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

  /** What this id turned out to be, or the id itself where the owner has said nothing. */
  private String canonical(String qid) {
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
