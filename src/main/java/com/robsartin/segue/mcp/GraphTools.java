package com.robsartin.segue.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * The two MCP tools that walk the graph: grow it from a source, or trace a citable route through
 * it.
 *
 * <p>Wired as a {@code @Bean} in {@code SegueConfiguration} for the same reason as {@link
 * EntityTools} — see that class's Javadoc and the task-7 report for how tool discovery was
 * confirmed. This class deliberately takes the default edge bound as a plain {@code int} rather
 * than the {@code SegueProperties} type itself: {@code SegueProperties} lives in {@code app}, and
 * {@code app} already depends on {@code mcp} to wire these tools, so importing it here would close
 * a package cycle that ArchUnit's {@code noPackageCycles} rule forbids (ADR 32). {@code
 * SegueConfiguration} resolves {@code properties.maxNewEdges()} once and passes the plain value
 * across the boundary. Every method begins with {@link CorrelationId#begin()} and clears it in a
 * {@code finally} (ADR 29).
 *
 * <p>Every method returns {@link CallToolResult} directly — see {@link ToolResults}' Javadoc (FIX 1
 * of the increment-4a final review; also ADR 26 amendment).
 */
public class GraphTools {

  /**
   * Route length bound when a model omits maxHops. Four hops is generous for a personal graph
   * without inviting a combinatorial search.
   */
  private static final int DEFAULT_MAX_HOPS = 4;

  private final SegueService service;
  private final int defaultMaxNewEdges;

  public GraphTools(SegueService service, int defaultMaxNewEdges) {
    this.service = service;
    this.defaultMaxNewEdges = defaultMaxNewEdges;
  }

  @McpTool(
      name = "expand_entity",
      description =
          """
          Discover an entity's relationships by running every source adapter that supports its \
          kind (currently Wikidata) and recording what they find as new edges and, where needed, \
          new neighbour nodes. The entity must already be in the graph — call add_entity first.

          This works on a person or a band, not only on a film or an album. Wikidata states a \
          creative relation once, on the WORK ("this film's director is X"), so expanding a person \
          also asks which items name them. A well-known musician commonly yields 80-100 edges.

          Cost: two network calls for the expansion itself, plus one per neighbour whose identity \
          the source could not supply along the way. That is usually a handful, so a first \
          expansion typically takes a few seconds; expanding the same entity again afterward is \
          faster, because its neighbours are already known nodes. Tell the user to expect a short \
          wait rather than assuming the call has hung.

          maxNewEdges bounds how many new assertions this call will consider; omit it to use the \
          server's configured default. The bound keeps the most-linked neighbours rather than an \
          arbitrary slice, so a small bound still returns the famous ones first. The result reports \
          whether it had to stop early at that bound, or could not resolve some neighbours.

          A CONCEPT seed — a subject, a topic, an award — is capped at a much smaller ceiling than \
          any maxNewEdges you pass, because expanding a broad subject is a flood rather than a \
          discovery: it pulls in hundreds of works that merely mention it. Asking for a larger \
          bound will not raise that ceiling; asking for a smaller one is honoured exactly. The \
          result comes back partial and names the ceiling actually applied. Every other kind is \
          bounded only by maxNewEdges.

          Expanding something because it was recommended to you will usually push it DOWN the next \
          recommendation list, and that is not a fault. The recommender divides by the candidate's \
          own degree, which this call raises; the entity has not become less interesting, it has \
          become better measured. Expanding it is still the right way to learn more about it.

          CONCEPT is also the kind an entity gets when its Wikidata classes are not recognised, so \
          a capped expansion occasionally means "we could not tell what this is" rather than "this \
          is a broad subject". If the entity is plainly a work, a person or a place, treat the cap \
          as a classification gap worth reporting to the user rather than as a fact about the \
          entity.\
          """,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = false,
              destructiveHint = false,
              idempotentHint = false))
  public CallToolResult expandEntity(
      @McpToolParam(required = true, description = "A Wikidata QID already in the graph.")
          String qid,
      @McpToolParam(
              required = false,
              description =
                  "Upper bound on new assertions to consider in this call. Omit to use the"
                      + " server's configured default. A CONCEPT seed is capped below this — see"
                      + " the tool description.")
          Integer maxNewEdges) {
    CorrelationId.begin();
    try {
      int bound = maxNewEdges == null ? defaultMaxNewEdges : maxNewEdges;
      return ToolResults.of(service.expandEntity(qid, bound));
    } finally {
      CorrelationId.clear();
    }
  }

  @McpTool(
      name = "find_paths",
      description =
          """
          Find routes between two entities already in the graph, up to maxHops relationships \
          apart, ranked most-trustworthy-first — a route built on well-corroborated edges outranks \
          a shorter one resting on a single unconfirmed source. Each route comes back hop by hop \
          with the assertion(s) that back it, so it is directly citable: this is the "you like \
          this because X leads to Y leads to Z" feature. Read-only.

          An empty result means no route exists within maxHops hops, not that the two entities are \
          unrelated at a greater distance — try a larger maxHops. Both entities must already be in \
          the graph (add_entity first); this returns an error, not an empty result, if either one \
          has not been added. maxHops defaults to 4 if omitted.

          At most 50 routes come back. A densely connected pair can have many more, and the \
          result says so when that happens, reporting how many exist — so the capped count is \
          never mistaken for the total. The 50 kept are the best-ranked ones rather than an \
          arbitrary slice, because the ranking above is applied before the cap.\
          """,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              idempotentHint = true))
  public CallToolResult findPaths(
      @McpToolParam(required = true, description = "QID to start from, already in the graph.")
          String fromQid,
      @McpToolParam(required = true, description = "QID to reach, already in the graph.")
          String toQid,
      @McpToolParam(
              required = false,
              description = "Longest route to consider, in hops. Defaults to 4.")
          Integer maxHops) {
    CorrelationId.begin();
    try {
      return ToolResults.of(
          service.findPaths(fromQid, toQid, maxHops == null ? DEFAULT_MAX_HOPS : maxHops));
    } finally {
      CorrelationId.clear();
    }
  }
}
