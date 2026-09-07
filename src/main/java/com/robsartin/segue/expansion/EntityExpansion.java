package com.robsartin.segue.expansion;

import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.ExpansionBounds;
import com.robsartin.segue.domain.LocalEntity;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.ingest.UnknownEndpointException;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.wikidata.WikidataUnavailableException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One expansion: run every source that supports the seed's kind, record what they return through
 * {@link IngestService}, and report what happened as facts.
 *
 * <p><b>Two callers, one body</b> (#284). {@code SegueService} turns the outcome into the {@code
 * ToolResult} a language model reads (ADR 27); the promotion expander counts. Before this class the
 * body lived in the tool layer, so a second caller had either to reach the tool layer or to state
 * the expansion a second time, and a second statement of an expansion is the drift this class
 * exists to end.
 *
 * <p><b>It writes, and that is why it is fenced.</b> It runs the adapters and appends what they
 * return, so a package that can reach it gains a bulk write and a network connection at once, past
 * whatever its own fence says. An ArchUnit rule therefore bars every package but the two callers,
 * {@code app} — which wires them — and this one.
 *
 * <p>What did NOT move here: {@code ToolResult}, the {@code ok}/{@code partial} shaping, the reason
 * sentences and {@code SegueService.ExpansionSummary}. Those are the tool layer's, and the outcome
 * carries facts precisely so that each caller can word them for its own reader.
 */
public final class EntityExpansion {

  private static final Logger log = LoggerFactory.getLogger(EntityExpansion.class);

  private final EntityResolver resolver;
  private final GraphStore graph;
  private final IngestService ingest;
  private final SourceAdapters adapters;

  public EntityExpansion(
      EntityResolver resolver, GraphStore graph, IngestService ingest, SourceAdapters adapters) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.graph = Objects.requireNonNull(graph, "graph");
    this.ingest = Objects.requireNonNull(ingest, "ingest");
    this.adapters = Objects.requireNonNull(adapters, "adapters");
  }

  /**
   * Expand a known entity through every source that supports its kind.
   *
   * <p>An expansion can reference neighbour entities the graph has never seen, and {@link
   * GraphStore#record} throws on an unknown endpoint. Each unknown neighbour therefore has to be
   * identified before the edge that names it can be recorded, and there are two ways to get that:
   *
   * <ul>
   *   <li><b>The adapter already knew.</b> {@link ExpandResult#neighbors()} carries identity the
   *       source learned while discovering the edge, and it is used in preference to anything else.
   *       Wikidata's reverse lookup returns label and kind alongside each backlink in one query
   *       (ADR 36), and after that change an expansion routinely finds seventy-odd neighbours —
   *       enough that fetching them individually would cost more than the entire expansion did
   *       before.
   *   <li><b>Otherwise, fetch it.</b> {@link EntityResolver#fetch}, sequentially, one HTTP round
   *       trip per remaining neighbour. Deliberately the slow, correct choice over synthesising a
   *       placeholder node: a graph full of {@code Q12345}-labelled stubs is worse than an
   *       expansion that takes a few seconds. (Follow-up: a bounded virtual-thread fan-out for the
   *       neighbour fetches, not built in this increment.)
   * </ul>
   *
   * <p><b>Identity an adapter supplies inline is recorded whether or not the graph already holds
   * the node</b> (issue #55). A node's kind comes from a whitelist that grows as it is measured
   * against real data, so recording only for absent nodes froze every old node at whatever the
   * mapper said the day it was discovered, and the graph ended up holding two different kinds for
   * one class of entity. The refresh costs nothing because the source volunteered the identity in
   * the same response; an existing neighbour that nobody described is left alone rather than
   * fetched, since that would be a round trip each for every neighbour of every expansion. It is
   * not counted in {@link ExpansionOutcome.Expanded#nodesAdded()} — a correction is not a
   * discovery.
   *
   * <p>Neighbour fetches that fail once are not retried for the same neighbour within this call —
   * that is the bound on how many calls a dense, partly-unreachable entity can trigger, alongside
   * {@code maxNewEdges} bounding the assertions considered at all. A neighbour fetch that throws
   * {@link WikidataUnavailableException} is treated exactly like one that returned empty: the
   * neighbour is skipped and the call continues, rather than aborting a 30-round-trip expansion
   * after some assertions are already committed.
   *
   * <p><b>An edge {@code IngestService} refuses is skipped and named, not thrown</b> (#233). {@link
   * #neighborOf} resolves ONE endpoint — the far end from the seed's point of view — so an edge
   * naming the seed at neither end has its second endpoint resolved by nobody. Every adapter in
   * {@code src/main} puts the seed at an end, and nothing in {@link SourceAdapter} says it must, so
   * this is the report rather than the guard: the guard is at the append, where a refusal costs a
   * message instead of a log that cannot boot. Counted by distinct endpoint, the same unit {@link
   * ExpansionOutcome.Expanded#skippedNeighbors()} uses, and carried as {@link
   * ExpansionOutcome.Expanded#refusedEndpoints()} — the ids themselves, insertion-ordered, so
   * whatever a caller builds from them is stable. Each caller then decides what to say: {@code
   * SegueService} names them in its detail string, where ADR 56 kept an aggregate flag on the wire
   * and moved only attribution to prose, so both the count and the names live in prose alone. That
   * is a narrower choice than ADR 56 made, not the same one repeated.
   *
   * <p>What is reported as skipped is the count of <em>distinct</em> neighbours, not of the
   * assertions dropped along with them. Both are defensible numbers; only one matches the name
   * {@link ExpansionOutcome.Expanded#skippedNeighbors()} and the sentence it is rendered into, and
   * this graph is a multigraph by design — Nick Cave both wrote and scored The Proposition, so two
   * assertions can name one pair of nodes. Counting per assertion told a calling model that two
   * entities were lost when one was.
   *
   * <p><b>A shortfall is flagged aggregately and attributed in prose</b> (issue #148). {@link
   * ExpansionOutcome.Expanded#sourceUnavailable()} and {@link
   * ExpansionOutcome.Expanded#truncated()} stay ORed across adapters, and are derived on the
   * outcome so that ORing is stated once; the two lists beside them name the sources by {@link
   * SourceAdapter#id()}, which the SPI already requires every adapter to have. With one source "a
   * source was unavailable" was unambiguous. With two it is unactionable — "MusicBrainz is down"
   * and "Wikidata is down" call for different next moves — and a caller renders those lists into
   * the prose its own reader sees first, so that is where the subject belongs. The two alternatives
   * lost on cost against a benefit nothing here would use: a per-source field on {@link
   * ExpandResult} would restate {@code id()} as a second, forgeable authority for who the source
   * was, and a per-adapter breakdown on the tool's own wire type would change that type's shape for
   * a consumer that reads prose. See docs/adr/0056-attribute-a-shortfall-to-its-source.md.
   *
   * <p><b>One shortfall is deliberately attributed to nobody.</b> Every adapter is handed the same
   * {@link ExpandContext} and the bound is then applied to the concatenation, so when it is the
   * shared budget that cut the result, no single adapter made the cut. That reason says so instead
   * of naming one — the design note's GAP 3, which this issue does not settle.
   *
   * <p><b>A {@code CONCEPT} seed is bounded below whatever {@code maxNewEdges} was requested</b>
   * ({@link ExpansionBounds}, issue #112): {@code maxNewEdges} resolves to {@code
   * ExpansionBounds.effective(node.kind(), maxNewEdges)} before it reaches {@link ExpandContext} or
   * either bound below, so a caller cannot ask past the ceiling and a bitten ceiling is reported
   * exactly like any other truncation — through the same {@link
   * ExpansionOutcome.Expanded#truncated()} flag every other shortfall arrives on.
   *
   * <p><b>A local entity is refused, and the refusal is the point</b> (#92). The owner mints one
   * because no source models it, and its id is one Wikidata's grammar can never allocate (ADR 58),
   * so there is no source to expand from now or later. The tempting alternative is to run the
   * adapters anyway and return what they find, which is nothing — but ADR 56 has just finished
   * establishing that an empty {@link ExpandResult} already carries two meanings, "found nothing"
   * and "the source was unavailable", and teaching it a third would rebuild the defect ADR 56
   * fixed. So this is a {@link ExpansionOutcome.Refused} carrying {@link
   * ExpansionOutcome.Reason#LOCAL_ENTITY} rather than a silent {@link ExpansionOutcome.Expanded}
   * with a zero in it; the sentence it is said in belongs to whichever caller says it. A merged
   * local id is refused too: the equivalence gives the canonical id everything the local one held,
   * and that is the id a source can answer for.
   *
   * @param qid the entity to expand, which the graph must already hold a node for
   * @param maxNewEdges the caller's bound, before {@link ExpansionBounds#effective} is applied
   * @return {@link ExpansionOutcome.Refused} when nothing ran, {@link ExpansionOutcome.Expanded}
   *     otherwise
   */
  public ExpansionOutcome expand(String qid, int maxNewEdges) {
    Objects.requireNonNull(qid, "qid");
    Optional<NodeRecord> seed = graph.node(qid);
    if (seed.isEmpty()) {
      return new ExpansionOutcome.Refused(qid, ExpansionOutcome.Reason.UNKNOWN_ENTITY);
    }
    // #92: the owner minted this, so no source has it and none ever will — its id is one Wikidata
    // cannot allocate (ADR 58). Refused out loud, and before the bound is even checked, because
    // no argument makes it expandable. Returning the empty result instead would be the cheaper
    // move and the wrong one: ADR 56 has just separated the two things an empty ExpandResult
    // already means, and a third would rebuild the defect it fixed.
    if (LocalEntity.isLocal(qid)) {
      return new ExpansionOutcome.Refused(qid, ExpansionOutcome.Reason.LOCAL_ENTITY);
    }
    if (maxNewEdges <= 0) {
      return new ExpansionOutcome.Refused(qid, ExpansionOutcome.Reason.BOUND_NOT_POSITIVE);
    }
    NodeRecord node = seed.get();
    // Issue #112: a ceiling on CONCEPT, applied to whatever the request resolved to before that
    // number reaches an adapter or the bound below — the same reason ReverseClaims itself is
    // asked for no more than this, not merely truncated after the fact.
    int effectiveMax = ExpansionBounds.effective(node.kind(), maxNewEdges);
    ExpandContext ctx = new ExpandContext(effectiveMax);

    // Which sources fell short, in the order the adapters ran, rather than whether any did.
    // Issue #148: the booleans below are still ORed — a caller asking "is this result complete?"
    // wants one answer — but "a source was unavailable" is unactionable once there is more than
    // one source, because "MusicBrainz is down" and "Wikidata is down" call for different next
    // moves. The subject lives in the detail string; see the reasons built at the end.
    List<String> unavailableSources = new ArrayList<>();
    List<String> truncatingSources = new ArrayList<>();
    List<AssertionRecord> collected = new ArrayList<>();
    // Identity an adapter already knew, keyed by qid. First writer wins, matching the way the
    // graph resolves a conflict everywhere else: two sources describing one entity differently
    // is a real possibility, and silently preferring the later one would hide it.
    Map<String, NodeAssertion> described = new HashMap<>();
    for (SourceAdapter adapter : adapters.all()) {
      if (!adapter.supports(node.kind())) {
        continue;
      }
      ExpandResult result = adapter.expand(node, ctx);
      if (result.sourceUnavailable()) {
        unavailableSources.add(adapter.id());
      }
      if (result.truncated()) {
        truncatingSources.add(adapter.id());
      }
      collected.addAll(result.assertions());
      for (NodeAssertion neighbor : result.neighbors()) {
        described.putIfAbsent(neighbor.qid(), neighbor);
      }
    }

    // The shared budget cutting the concatenation is NOT attributable to any one adapter — every
    // adapter was handed the same ExpandContext and the bound is applied to what they jointly
    // returned (the design note's GAP 3, established rather than fixed here). So it is reported as
    // its own reason rather than folded into the named ones, which would put a source's name on a
    // cut it did not make.
    // The two derived flags that used to be computed here are now ExpansionOutcome.Expanded's,
    // so ADR 56's ORing is stated once for both callers rather than once per caller.
    boolean boundCutTheConcatenation = collected.size() > effectiveMax;
    List<AssertionRecord> bounded =
        collected.size() > effectiveMax
            ? collected.stream().limit(effectiveMax).toList()
            : collected;

    int nodesAdded = 0;
    int edgesAdded = 0;
    // Does double duty, deliberately: it is the memo that stops one neighbour being fetched twice
    // in a single call, and it is also the number reported as skippedNeighbors. Keeping a separate
    // counter is what made the two disagree — the counter incremented per dropped assertion while
    // the field, and the sentence built from it, both said "neighbour".
    Set<String> unresolvableNeighbors = new HashSet<>();
    // Every neighbour whose identity this call has already recorded — see the note further down.
    Set<String> identityRecorded = new HashSet<>();
    // Endpoints the graph holds no node for, by endpoint rather than by assertion — the same unit
    // skippedNeighbors uses, and for the same reason: two assertions naming one unknown entity are
    // one thing the caller can act on. Insertion-ordered so the reason string is stable.
    Set<String> refusedEndpoints = new LinkedHashSet<>();
    for (AssertionRecord assertion : bounded) {
      String neighbor = neighborOf(assertion, qid);
      if (neighbor != null) {
        if (unresolvableNeighbors.contains(neighbor)) {
          continue;
        }
        // This re-read is now the definition of "new" and nothing else. It still cannot let
        // nodesAdded double-count — a neighbour recorded on the first assertion naming it is in
        // the graph by the time the second one is examined — but it no longer decides whether
        // identity is recorded at all; see issue #55 below. edgesAdded needs no such guard:
        // assertions ARE what it counts, and two of them between one pair are two claims.
        boolean isNew = graph.node(neighbor).isEmpty();
        // An adapter that already knows this entity spares a round trip. That is not a
        // micro-optimisation since ADR 36: expanding a person now discovers seventy-odd
        // works in one query, and fetching each of them one at a time afterwards would cost
        // more than the whole expansion used to.
        Optional<NodeAssertion> resolved = Optional.ofNullable(described.get(neighbor));
        if (isNew) {
          if (resolved.isEmpty()) {
            try {
              resolved = resolver.fetch(neighbor);
            } catch (WikidataUnavailableException e) {
              log.warn(
                  "expandEntity({}) neighbour {} unavailable: {}", qid, neighbor, e.getMessage());
              resolved = Optional.empty();
            }
          }
          if (resolved.isEmpty()) {
            unresolvableNeighbors.add(neighbor);
            continue;
          }
        }
        // Issue #55. Identity the source volunteered is recorded even when the node already
        // exists, and the fetch above is deliberately NOT reached for one that does. Kinds come
        // from KindMapper's whitelist, which grows every time it is measured against real data
        // (issues #49 and #52); recording only for absent nodes froze each node's kind at
        // whatever the mapper said on the run that first saw it, so 73% of the CONCEPT nodes in
        // a real graph were works or groups the mapper had since learned to classify — and ADR
        // 31's hub rule then vetoed routes through them. GraphStore.upsertNode is
        // last-writer-wins and ADR 19 says a changed belief is a new claim, so re-recording is
        // the correction. It is free ONLY because the source already handed the identity over
        // in the same response; fetching identity for existing neighbours would be hundreds of
        // extra round trips per expansion and is a different decision, not this one.
        //
        // Not the same rule as described.putIfAbsent above, which stays first-writer-wins.
        // That one settles a disagreement between two sources WITHIN one call, where the later
        // writer has no claim to be the better one. This one refreshes from the SAME source
        // ACROSS runs, where the later reading is by construction the better one. Do not
        // unify them.
        //
        // Once per neighbour per call, not once per assertion. The graph re-read used to supply
        // that for free — a neighbour recorded on the first assertion naming it was present by
        // the second — and it no longer does, because the refresh fires whether or not the node
        // is there. This graph is a multigraph by design (Nick Cave both wrote and scored The
        // Proposition), so without the memo one pair of nodes would append the same identity
        // claim to the log twice, and a replay would apply it twice.
        if (resolved.isPresent() && identityRecorded.add(neighbor)) {
          ingest.record(resolved.get());
          if (isNew) {
            nodesAdded++;
          }
        }
      }
      try {
        ingest.record(assertion);
      } catch (UnknownEndpointException e) {
        // #233. The gate refused this edge BEFORE the append, so nothing is half-written and the
        // expansion carries on rather than aborting a thirty-round-trip call over one bad row —
        // the same choice made for an unresolvable neighbour above. Letting it escape is what the
        // class's second invariant forbids and what ADR 27 turns into a readable result instead.
        log.warn("expandEntity({}) refused an edge: {}", qid, e.getMessage());
        refusedEndpoints.addAll(e.endpoints());
        continue;
      }
      edgesAdded++;
    }

    int skippedNeighbors = unresolvableNeighbors.size();
    return new ExpansionOutcome.Expanded(
        qid,
        nodesAdded,
        edgesAdded,
        skippedNeighbors,
        effectiveMax,
        unavailableSources,
        truncatingSources,
        boundCutTheConcatenation,
        List.copyOf(refusedEndpoints),
        Map.of());
  }

  /** The other end of an assertion from the seed's point of view, or null if both ends are it. */
  private static String neighborOf(AssertionRecord assertion, String seedQid) {
    if (!assertion.fromQid().equals(seedQid)) {
      return assertion.fromQid();
    }
    if (!assertion.toQid().equals(seedQid)) {
      return assertion.toQid();
    }
    return null;
  }
}
