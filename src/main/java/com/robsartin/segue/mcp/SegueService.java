package com.robsartin.segue.mcp;

import com.robsartin.segue.domain.AffinityRecord;
import com.robsartin.segue.domain.AssertionRecord;
import com.robsartin.segue.domain.Candidate;
import com.robsartin.segue.domain.EdgeRecord;
import com.robsartin.segue.domain.NodeAssertion;
import com.robsartin.segue.domain.NodeKind;
import com.robsartin.segue.domain.NodeRecord;
import com.robsartin.segue.domain.PathRanking;
import com.robsartin.segue.domain.PathResult;
import com.robsartin.segue.domain.RatingScale;
import com.robsartin.segue.expansion.EntityExpansion;
import com.robsartin.segue.expansion.ExpansionOutcome;
import com.robsartin.segue.ingest.IngestService;
import com.robsartin.segue.port.AffinityStore;
import com.robsartin.segue.port.EntityResolver;
import com.robsartin.segue.port.GraphStore;
import com.robsartin.segue.port.SourceAdapters;
import com.robsartin.segue.wikidata.RecognitionInstitutions;
import com.robsartin.segue.wikidata.WikidataUnavailableException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 *       site that can throw it — {@code resolver.search}, {@code resolver.fetch}, and the neighbour
 *       fetch inside {@link com.robsartin.segue.expansion.EntityExpansion#expand} — rather than
 *       left to escape.
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
  private final EntityExpansion expansion;

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
    // Built here rather than injected: every collaborator it needs is already a field, and a
    // seventh constructor parameter would move thirty-odd call sites for nothing. #284.
    this.expansion = new EntityExpansion(this.resolver, this.graph, this.ingest, this.adapters);
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
   * Expand a known entity through every source that supports its kind, and say what happened in the
   * words ADR 27 asks a calling model to be given.
   *
   * <p><b>The expansion itself is {@link EntityExpansion}'s</b> (#284). Every decision it makes —
   * the refusals, the bound, the neighbour memo, issue #55's identity refresh, #233's refused
   * endpoints — moved there with the body and with the javadoc that argues for each, because a
   * second caller needed the expansion and did not need a {@code ToolResult}.
   *
   * <p>What stays here is the shaping: the three refusal sentences, the reason list in the order it
   * has always been built, and the {@code ok}/{@code partial} result. Those are wire strings with
   * one audience, and a shared sentence would be a wire string with two, one of them a language
   * model.
   */
  public ToolResult<ExpansionSummary> expandEntity(String qid, int maxNewEdges) {
    Objects.requireNonNull(qid, "qid");
    return switch (expansion.expand(qid, maxNewEdges)) {
      case ExpansionOutcome.Refused refused -> error(refusalSentence(refused, maxNewEdges));
      case ExpansionOutcome.Expanded expanded -> shape(expanded);
    };
  }

  /**
   * The three sentences this method has always returned, byte for byte.
   *
   * <p>{@link ExpansionOutcome.Refused} carries a reason and no number, deliberately — it is read
   * by a second caller that renders a tally label rather than a sentence. The one sentence that
   * quotes a number quotes the caller's own argument, and this is the caller, so {@code
   * maxNewEdges} is passed in rather than travelling on the outcome.
   */
  private static String refusalSentence(ExpansionOutcome.Refused refused, int maxNewEdges) {
    return switch (refused.reason()) {
      case UNKNOWN_ENTITY -> "unknown entity: " + refused.qid() + " — add it before expanding";
      case LOCAL_ENTITY ->
          "local entity: "
              + refused.qid()
              + " — no source to expand from, because the owner minted it";
      case BOUND_NOT_POSITIVE -> "maxNewEdges must be positive, got " + maxNewEdges;
    };
  }

  /** The wire summary and the reason list, assembled in the order they have always been. */
  private static ToolResult<ExpansionSummary> shape(ExpansionOutcome.Expanded expanded) {
    String qid = expanded.qid();
    int edgesAdded = expanded.edgesAdded();
    int nodesAdded = expanded.nodesAdded();
    int skippedNeighbors = expanded.skippedNeighbors();
    int effectiveMax = expanded.effectiveMax();
    ExpansionSummary summary =
        new ExpansionSummary(
            qid,
            nodesAdded,
            edgesAdded,
            skippedNeighbors,
            expanded.truncated(),
            expanded.sourceUnavailable());
    List<String> reasons = new ArrayList<>();
    if (expanded.sourceUnavailable()) {
      reasons.add(
          String.join(", ", expanded.unavailableSources())
              + (expanded.unavailableSources().size() == 1 ? " was" : " were")
              + " unavailable and could not be reached");
    }
    if (!expanded.truncatingSources().isEmpty()) {
      reasons.add(
          String.join(", ", expanded.truncatingSources())
              + (expanded.truncatingSources().size() == 1
                  ? " truncated its result"
                  : " truncated their results")
              + " at the bound of "
              + effectiveMax);
    }
    if (expanded.boundCutTheConcatenation()) {
      reasons.add("the combined result was truncated at the bound of " + effectiveMax);
    }
    if (skippedNeighbors > 0) {
      reasons.add(skippedNeighbors + " neighbour(s) could not be resolved and were skipped");
    }
    if (!expanded.refusedEndpoints().isEmpty()) {
      reasons.add(
          expanded.refusedEndpoints().size()
              + " endpoint(s) the graph holds no node for were refused: "
              + String.join(", ", expanded.refusedEndpoints()));
    }
    if (reasons.isEmpty()) {
      return ToolResult.ok(
          "expanded " + qid + ": " + edgesAdded + " edge(s), " + nodesAdded + " new node(s)",
          summary);
    }
    log.warn("expandEntity({}) partial: {}", qid, reasons);
    return ToolResult.partial(withCorrelation(String.join("; ", reasons)), summary);
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
   * @param truncated an adapter or the {@code maxNewEdges} bound cut the result short — aggregate
   *     across sources on purpose, because the question it answers ("is this result complete?") has
   *     one answer however many sources ran; <b>which</b> source is named in {@link
   *     ToolResult#detail} (issue #148)
   * @param sourceUnavailable at least one source could not be reached at all — aggregate for the
   *     same reason, and attributed in the same place
   */
  public record ExpansionSummary(
      String qid,
      int nodesAdded,
      int edgesAdded,
      int skippedNeighbors,
      boolean truncated,
      boolean sourceUnavailable) {}
}
