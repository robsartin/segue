package com.robsartin.segue.mcp;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.ExpansionBounds;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathRanking;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.RatingScale;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.ExpandContext;
import com.robsartin.segue.port.ExpandResult;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapter;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.wikidata.RecognitionInstitutions;
import com.robsartin.segue.wikidata.WikidataUnavailableException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The facade every MCP tool calls. It owns the ports; the tools own nothing.
 *
 * <p>Three invariants matter more than the rest of the class:
 *
 * <ul>
 *   <li>only {@link IngestService} ever applies a claim to the graph. This class calls it for every
 *       write and never touches {@link GraphStore#record}, {@link GraphStore#upsertNode} or {@code
 *       AssertionLog.append} directly — ArchUnit rule {@code onlyIngestAppliesClaimsToTheGraph}
 *       fails the build on any of the three, and it is the right rule (ADR 19). Read that as
 *       enforcement rather than intent: until issue #44 the rule matched {@code GraphStore.record}
 *       alone, so two-thirds of this sentence described a convention while claiming a guarantee.
 *   <li>nothing thrown by a port escapes a public method here except a programmer error, such as a
 *       null argument. Every other shortfall — an unknown qid, a malformed one, an unreachable
 *       source, a result cut short by its own bound — comes back as a {@link ToolResult} the
 *       calling model can read and act on (ADR 27), with {@link CorrelationId#current()} folded
 *       into the detail of every non-ok result so a user-visible error can be pasted into a log
 *       search (ADR 29). {@link WikidataUnavailableException} in particular is caught at every call
 *       site that can throw it — {@code resolver.search}, {@code resolver.fetch}, and the unwrapped
 *       neighbour fetch inside {@link #expandEntity} — rather than left to escape.
 *   <li>this class is the only place the two layers meet, and they meet nowhere below it (ADR 33).
 *       {@link #noteAffinity} writes taste and never touches the graph; {@link #getEntity} reads
 *       both and composes them into one view. Neither store learns about the other, which is what
 *       keeps the world graph exportable without personal data attached — see ADR 39.
 * </ul>
 *
 * <p>Every method returns view types from {@code mcp/} (translated by {@link ViewMapper}), never
 * the domain records themselves — see {@link ViewMapper}'s Javadoc for why.
 */
public final class SegueService {

  private static final Logger log = LoggerFactory.getLogger(SegueService.class);

  /** ADR 26/ADR 22: identity is a Wikidata QID, always of this shape. */
  private static final Pattern QID = Pattern.compile("Q\\d+");

  private final EntityResolver resolver;
  private final GraphStore graph;
  private final IngestService ingest;
  private final SourceAdapters adapters;
  private final AffinityStore affinity;
  private final Clock clock;

  public SegueService(
      EntityResolver resolver,
      GraphStore graph,
      IngestService ingest,
      SourceAdapters adapters,
      AffinityStore affinity,
      Clock clock) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.graph = Objects.requireNonNull(graph, "graph");
    this.ingest = Objects.requireNonNull(ingest, "ingest");
    this.adapters = Objects.requireNonNull(adapters, "adapters");
    this.affinity = Objects.requireNonNull(affinity, "affinity");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Candidates for a free-text query, best match first. Writes nothing. */
  public ToolResult<List<CandidateView>> search(String query, NodeKind kind, int limit) {
    Objects.requireNonNull(query, "query");
    List<Candidate> candidates;
    try {
      candidates = resolver.search(query, kind, limit);
    } catch (WikidataUnavailableException e) {
      log.warn("search(\"{}\") source unavailable: {}", query, e.getMessage());
      return error("wikidata unavailable: " + e.getMessage());
    }
    return ToolResult.ok(
        candidates.size() + " candidate(s) for \"" + query + "\"",
        ViewMapper.toCandidateViews(candidates));
  }

  /**
   * Fetch one entity's identity from the resolver and record it. Recording is an upsert, so a
   * second call with the same qid is idempotent — it refreshes the node rather than duplicating it.
   */
  public ToolResult<NodeView> addEntity(String qid) {
    Objects.requireNonNull(qid, "qid");
    if (!QID.matcher(qid).matches()) {
      return error("not a QID: " + qid);
    }
    Optional<NodeAssertion> fetched;
    try {
      fetched = resolver.fetch(qid);
    } catch (WikidataUnavailableException e) {
      log.warn("addEntity({}) source unavailable: {}", qid, e.getMessage());
      return error("wikidata unavailable: " + e.getMessage());
    }
    if (fetched.isEmpty()) {
      return error("no such entity: " + qid);
    }
    NodeAssertion assertion = fetched.get();
    ingest.record(assertion);
    return ToolResult.ok(
        "added " + qid + " (" + assertion.label() + ")", ViewMapper.toNodeView(assertion.toNode()));
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
   * not counted in {@link ExpansionSummary#nodesAdded()} — a correction is not a discovery.
   *
   * <p>Neighbour fetches that fail once are not retried for the same neighbour within this call —
   * that is the bound on how many calls a dense, partly-unreachable entity can trigger, alongside
   * {@code maxNewEdges} bounding the assertions considered at all. A neighbour fetch that throws
   * {@link WikidataUnavailableException} is treated exactly like one that returned empty: the
   * neighbour is skipped and the call continues, rather than aborting a 30-round-trip expansion
   * after some assertions are already committed.
   *
   * <p>What is reported as skipped is the count of <em>distinct</em> neighbours, not of the
   * assertions dropped along with them. Both are defensible numbers; only one matches the name
   * {@link ExpansionSummary#skippedNeighbors()} and the sentence it is rendered into, and this
   * graph is a multigraph by design — Nick Cave both wrote and scored The Proposition, so two
   * assertions can name one pair of nodes. Counting per assertion told a calling model that two
   * entities were lost when one was.
   *
   * <p><b>A {@code CONCEPT} seed is bounded below whatever {@code maxNewEdges} was requested</b>
   * ({@link ExpansionBounds}, issue #112): {@code maxNewEdges} resolves to {@code
   * ExpansionBounds.effective(node.kind(), maxNewEdges)} before it reaches {@link ExpandContext} or
   * either bound below, so a caller cannot ask past the ceiling and a bitten ceiling is reported
   * exactly like any other truncation — through the same observed {@code truncated} flag, arriving
   * as {@code partial}.
   */
  public ToolResult<ExpansionSummary> expandEntity(String qid, int maxNewEdges) {
    Objects.requireNonNull(qid, "qid");
    Optional<NodeRecord> seed = graph.node(qid);
    if (seed.isEmpty()) {
      return error("unknown entity: " + qid + " — add it before expanding");
    }
    if (maxNewEdges <= 0) {
      return error("maxNewEdges must be positive, got " + maxNewEdges);
    }
    NodeRecord node = seed.get();
    // Issue #112: a ceiling on CONCEPT, applied to whatever the request resolved to before that
    // number reaches an adapter or the bound below — the same reason ReverseClaims itself is
    // asked for no more than this, not merely truncated after the fact.
    int effectiveMax = ExpansionBounds.effective(node.kind(), maxNewEdges);
    ExpandContext ctx = new ExpandContext(effectiveMax);

    boolean sourceUnavailable = false;
    boolean adapterTruncated = false;
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
      sourceUnavailable |= result.sourceUnavailable();
      adapterTruncated |= result.truncated();
      collected.addAll(result.assertions());
      for (NodeAssertion neighbor : result.neighbors()) {
        described.putIfAbsent(neighbor.qid(), neighbor);
      }
    }

    boolean truncated = adapterTruncated || collected.size() > effectiveMax;
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
      ingest.record(assertion);
      edgesAdded++;
    }

    int skippedNeighbors = unresolvableNeighbors.size();
    ExpansionSummary summary =
        new ExpansionSummary(
            qid, nodesAdded, edgesAdded, skippedNeighbors, truncated, sourceUnavailable);
    List<String> reasons = new ArrayList<>();
    if (sourceUnavailable) {
      reasons.add("a source was unavailable and could not be reached");
    }
    if (truncated) {
      reasons.add("the result was truncated at the bound of " + effectiveMax);
    }
    if (skippedNeighbors > 0) {
      reasons.add(skippedNeighbors + " neighbour(s) could not be resolved and were skipped");
    }
    if (reasons.isEmpty()) {
      return ToolResult.ok(
          "expanded " + qid + ": " + edgesAdded + " edge(s), " + nodesAdded + " new node(s)",
          summary);
    }
    log.warn("expandEntity({}) partial: {}", qid, reasons);
    return ToolResult.partial(withCorrelation(String.join("; ", reasons)), summary);
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

  /** One entity plus its neighbours, grouped by the relationship type that connects them. */
  public ToolResult<EntityView> getEntity(String qid) {
    Objects.requireNonNull(qid, "qid");
    Optional<NodeRecord> node = graph.node(qid);
    if (node.isEmpty()) {
      return error("unknown entity: " + qid);
    }
    List<EdgeRecord> edges = graph.edges(qid);
    TreeMap<String, List<NodeRecord>> byType = new TreeMap<>();
    for (EdgeRecord edge : edges) {
      String neighborQid = edge.fromQid().equals(qid) ? edge.toQid() : edge.fromQid();
      graph
          .node(neighborQid)
          .ifPresent(
              neighbor ->
                  byType.computeIfAbsent(edge.typeCode(), t -> new ArrayList<>()).add(neighbor));
    }
    List<NeighborGroup> groups =
        byType.entrySet().stream()
            .map(
                e ->
                    new NeighborGroup(
                        e.getKey(), e.getValue().stream().map(ViewMapper::toNodeView).toList()))
            .toList();
    // The join across the two layers ADR 33 anticipated, done here rather than in either store:
    // the graph does not know this entity is liked and the affinity table does not know what it
    // is. Null when the user has never rated it — see EntityView.
    AffinityView rated = affinity.find(qid).map(ViewMapper::toAffinityView).orElse(null);
    EntityView view = new EntityView(ViewMapper.toNodeView(node.get()), groups, rated);
    // The summary line names neither the rating nor the note, only whether one exists. It is the
    // one string in this method a caller is likely to paste somewhere, and ADR 33 keeps affinity
    // values out of anything that reads like a log line.
    return ToolResult.ok(
        node.get().label()
            + ": "
            + edges.size()
            + " edge(s), "
            + groups.size()
            + " type(s)"
            + (rated == null ? "" : ", rated"),
        view);
  }

  /**
   * Record what the user thinks of one entity: the taste layer's only write (ADR 33).
   *
   * <p><b>This never touches the graph or the log, and the shape of the method is the proof.</b>
   * There is no {@code ingest.record} call and no {@link AssertionRecord} anywhere in it — a rating
   * is not a claim about the world, so it gets no {@link com.robsartin.segue.domain.Provenance}, no
   * corroboration and no {@code llm:} prefix. ArchUnit's {@code
   * affinityNeverTouchesTheWorldFactLayer} rule keeps that true as the class grows.
   *
   * <p><b>Three refusals, in order, and every one of them a readable result rather than a throw
   * (ADR 27).</b> Not a QID; a QID the graph has never seen; a rating off the 1-5 scale. The second
   * is ADR 39's identity decision and its cost is deliberate: something Wikidata does not have
   * cannot be rated at all, because a rating that joins to no world facts is a note in a text file
   * with extra steps. The check is here rather than in {@link AffinityRecord} because only this
   * class can see the graph.
   *
   * <p><b>Nothing in this method logs.</b> Not on the happy path and not on the refusals — every
   * other method in this class logs its shortfalls, and this one deliberately does not, because ADR
   * 30's structured logging is precisely the thing that makes ADR 33's "affinity is never logged"
   * easy to violate by reflex. The refusal text never echoes the rating or the note back either: an
   * error string is the likeliest of all of these to be logged by something upstream.
   *
   * @param note optional; blank is treated as absent, so a model that helpfully sends {@code ""}
   *     does not store an empty note that later reads as "they wrote something"
   */
  public ToolResult<AffinityView> noteAffinity(String qid, int rating, String note) {
    Objects.requireNonNull(qid, "qid");
    if (!QID.matcher(qid).matches()) {
      return error("not a QID: " + qid);
    }
    if (graph.node(qid).isEmpty()) {
      return error("unknown entity: " + qid + " — add it before rating it");
    }
    if (rating < RatingScale.MIN || rating > RatingScale.MAX) {
      return error("rating must be an integer from 1 to 5");
    }
    String trimmed = note == null || note.isBlank() ? null : note.strip();
    AffinityRecord recorded = new AffinityRecord(qid, rating, trimmed, clock.instant());
    affinity.put(recorded);
    return ToolResult.ok("noted affinity for " + qid, ViewMapper.toAffinityView(recorded));
  }

  /**
   * Every route between two entities up to {@code maxHops}, ranked most-trustworthy-first (ADR 31).
   * Never the raw order {@link GraphStore#paths} returns — shortest is not most trustworthy.
   *
   * <p>Both endpoints must already be in the graph. Without this check an entity nobody ever {@code
   * add_entity}'d reads identically to two entities the graph knows are unrelated — both return
   * {@code ok} with zero routes — and a model that forgot to add one would report "these things are
   * unrelated" rather than the actual problem.
   *
   * <p><b>A result cut short by {@link PathRanking#MAX_PATHS} comes back {@code partial}, naming
   * how many routes exist</b> (issue #65). This was the one place the tool surface claimed a
   * completeness it did not have: the cap was applied and the count reported was the capped one, so
   * a dense pair returned "50 route(s)" whether the graph held fifty or two hundred, and a model
   * reading that would reasonably report fifty as the number of routes. It was seen twice on the
   * real graph without anyone noticing. Everything else here already reported shortfall — {@link
   * #expandEntity} has a {@code truncated} flag, the reverse-claims query fetches one more than its
   * bound so truncation is an observation — and ADR 27 exists to make exactly this readable rather
   * than silent.
   */
  public ToolResult<List<PathView>> findPaths(String fromQid, String toQid, int maxHops) {
    Objects.requireNonNull(fromQid, "fromQid");
    Objects.requireNonNull(toQid, "toQid");
    if (maxHops <= 0) {
      return error("maxHops must be positive, got " + maxHops);
    }
    if (graph.node(fromQid).isEmpty()) {
      return error("unknown entity: " + fromQid + " — add it before searching for routes");
    }
    if (graph.node(toQid).isEmpty()) {
      return error("unknown entity: " + toQid + " — add it before searching for routes");
    }
    List<PathResult> raw = graph.paths(fromQid, toQid, maxHops);
    List<PathResult> ranked =
        PathRanking.rank(raw, degreeLookup(), RecognitionInstitutions::isRecognitionInstitution);
    List<PathView> views = ViewMapper.toPathViews(ranked);
    // Truncation is observed, not inferred: ranking sorts and then caps, so the two sizes
    // differ exactly when the cap dropped something. MAX_PATHS is named in the sentence below
    // but never consulted to DECIDE this — the same reason ReverseClaims fetches maxNewEdges + 1
    // rather than guessing whether it was cut short.
    int omitted = raw.size() - ranked.size();
    if (omitted == 0) {
      return ToolResult.ok(ranked.size() + " route(s) from " + fromQid + " to " + toQid, views);
    }
    // Say what was kept as well as what was lost. A truncated answer whose remainder is the
    // BEST routes is worth far more to a model than one holding an arbitrary fifty, and ADR
    // 31 ranks — model guesses last, then hub intermediates, then confidence — before the cap
    // applies, so that is a property of the result rather than a hopeful description of it.
    String detail =
        raw.size()
            + " route(s) from "
            + fromQid
            + " to "
            + toQid
            + ", more than the cap of "
            + PathRanking.MAX_PATHS
            + ": the "
            + ranked.size()
            + " best-ranked are returned and "
            + omitted
            + " omitted";
    log.warn("findPaths({}, {}) partial: {}", fromQid, toQid, detail);
    return ToolResult.partial(withCorrelation(detail), views);
  }

  /**
   * The graph's shape, handed to {@link PathRanking} as a plain function over a qid.
   *
   * <p>ADR 31's specificity amendment (issue #52) needs to know how busy a route's intermediate
   * nodes are, and {@code PathRanking} lives in {@code domain}, which carries no third-party
   * dependencies and no graph access at all (ADR 18, enforced by ArchUnit). This class already
   * holds the port, so the lookup is built here and passed down — the ranking uses the graph's
   * shape without the domain ever learning what a graph is.
   *
   * <p>Memoised for the duration of one call and no longer. A dense pair can produce thousands of
   * candidate routes through a handful of nodes, so the cache turns an edge scan per hop into one
   * per distinct entity; a fresh map per call is what keeps it from answering with a degree the
   * graph has since moved past.
   */
  private ToIntFunction<String> degreeLookup() {
    Map<String, Integer> cache = new HashMap<>();
    return qid -> cache.computeIfAbsent(qid, q -> graph.edges(q).size());
  }

  private static <T> ToolResult<T> error(String reason) {
    return ToolResult.error(withCorrelation(reason));
  }

  private static String withCorrelation(String reason) {
    String correlation = CorrelationId.current();
    return correlation.isEmpty() ? reason : reason + " (correlation " + correlation + ")";
  }

  /**
   * What one expansion produced, and how much of it it could not resolve.
   *
   * @param nodesAdded entities newly recorded by this call, counted once each — an existing node
   *     whose identity this call refreshed (issue #55) is not among them, because the number
   *     answers how much the graph grew and a corrected node is not a discovered one
   * @param edgesAdded assertions recorded by this call — per assertion, not per pair of nodes, so
   *     two sources claiming the same relationship count twice and are merged downstream by {@code
   *     GraphStore.record}
   * @param skippedNeighbors distinct entities this call could not identify, counted once each
   *     however many assertions named them
   * @param truncated the adapter or the {@code maxNewEdges} bound cut the result short
   * @param sourceUnavailable at least one source could not be reached at all
   */
  public record ExpansionSummary(
      String qid,
      int nodesAdded,
      int edgesAdded,
      int skippedNeighbors,
      boolean truncated,
      boolean sourceUnavailable) {}
}
