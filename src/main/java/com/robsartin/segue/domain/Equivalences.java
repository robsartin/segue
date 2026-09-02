package com.robsartin.segue.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p><b>The graph half of a merge has its rule here, and does not yet use it.</b> {@code
 * IngestService.carry} <em>copies</em> the edges onto the canonical id and leaves them on the local
 * id too, so two nodes carry one entity's edges and every neighbour of a merged entity has one more
 * incident edge than the world justifies — the defect issue #178 measured, in {@code
 * docs/superpowers/specs/2026-09-02-merge-degree-design.md}. {@link #foldEndpoints} is the fix's
 * rule, landed here first and called by nothing: it is a prerequisite leaf, and the commit that
 * makes both projections call it is what removes the copy. Until then this method is exercised by
 * {@code EquivalencesTest} alone.
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
 * <p>{@code KnownList.promoted} promotes both rows, the graph holds both nodes carrying the same
 * edges, and the owner's one opinion is counted twice. The second face of the same defect is that
 * where only the canonical id is rated, the local entity <em>becomes a candidate</em> — in the same
 * fixture it ranked first, above the entity the run existed to find.
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
   * <p><b>One hop, and the class javadoc says why there can never be two.</b>
   */
  public LoggedAssertion foldEndpoints(LoggedAssertion assertion) {
    Objects.requireNonNull(assertion, "assertion");
    return switch (assertion) {
      case AssertionRecord claim -> foldEndpoints(claim);
      case OwnerEdge owned -> {
        String from = canonical(owned.fromQid());
        String to = canonical(owned.toQid());
        yield from.equals(owned.fromQid()) && to.equals(owned.toQid())
            ? owned
            : new OwnerEdge(from, to, owned.typeCode(), owned.assertedAt());
      }
      default -> assertion;
    };
  }

  /**
   * The same edge claim with both of its endpoints read through the equivalences (#178) — {@link
   * #foldEndpoints(LoggedAssertion)} for a caller that already knows it holds a sourced edge, so
   * that the exporter's fold needs no cast to ask its {@code edgeKey}.
   *
   * <p>The general method delegates here, so there is one rule and not two.
   */
  public AssertionRecord foldEndpoints(AssertionRecord claim) {
    Objects.requireNonNull(claim, "claim");
    String from = canonical(claim.fromQid());
    String to = canonical(claim.toQid());
    if (from.equals(claim.fromQid()) && to.equals(claim.toQid())) {
      // Most of a log names no merged id at all, and a copy of every row would be waste.
      return claim;
    }
    return new AssertionRecord(
        from, to, claim.typeCode(), claim.validFrom(), claim.validTo(), claim.provenance());
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
