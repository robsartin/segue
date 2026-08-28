package com.robsartin.segue.mcp;

import com.robsartin.segue.domain.NodeKind;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * The three MCP tools that resolve identity: find a candidate, add it, look it up again.
 *
 * <p>Wired as a {@code @Bean} in {@code SegueConfiguration}, same as everything else in this
 * project — see that class's Javadoc. Being a Spring bean at all, by whatever means, is what
 * matters: the starter's {@code ServerAnnotatedMethodBeanPostProcessor} inspects every bean during
 * context refresh for {@code @McpTool} methods, regardless of whether it arrived via component
 * scanning or a {@code @Bean} factory method. See the task-7 report for how that discovery
 * mechanism was confirmed by reading the autoconfiguration rather than assumed. Every method begins
 * with {@link CorrelationId#begin()} and clears it in a {@code finally}, because the stdio
 * transport has no header layer to carry a trace id any other way (ADR 29).
 *
 * <p>Every method returns {@link CallToolResult} directly rather than a Spring AI-generated one —
 * see {@link ToolResults}' Javadoc for why {@code generateOutputSchema} is gone from every
 * {@code @McpTool} here (FIX 1 of the increment-4a final review; also ADR 26 amendment).
 */
public class EntityTools {

  /**
   * Search results are capped this low by default; a model can ask for more, up to the resolver's
   * own clamp of 50.
   */
  private static final int DEFAULT_SEARCH_LIMIT = 10;

  private final SegueService service;

  public EntityTools(SegueService service) {
    this.service = service;
  }

  @McpTool(
      name = "search_entities",
      description =
          """
          Search for entities by free text (a person, band, film, place, or any other name) and \
          return ranked candidates carrying Wikidata QIDs. Pass one to add_entity first; \
          get_entity, expand_entity and find_paths only work on entities already added. Each \
          candidate has a short description field: use it to disambiguate when the same name \
          matches more than one entity, such as a painter and a film named after him. This tool \
          writes nothing.

          The kind parameter does NOT filter results. Wikidata's search endpoint cannot report an \
          entity's kind at search time, so kind is never applied as a filter here and every \
          candidate's kind field is a placeholder rather than a real classification — do not treat \
          it as fact. An empty result means the search text matched nothing; it never means "no \
          entity of that kind exists". The real kind is settled once the entity is added.\
          """,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              idempotentHint = true))
  public CallToolResult searchEntities(
      @McpToolParam(required = true, description = "Free-text name or phrase to search for.")
          String query,
      @McpToolParam(
              required = false,
              description =
                  "Ignored for filtering — see the tool description. Left in for resolvers that"
                      + " may support it in future.")
          NodeKind kind,
      @McpToolParam(
              required = false,
              description = "Maximum candidates to return. Defaults to 10, capped at 50.")
          Integer limit) {
    CorrelationId.begin();
    try {
      return ToolResults.of(
          service.search(query, kind, limit == null ? DEFAULT_SEARCH_LIMIT : limit));
    } finally {
      CorrelationId.clear();
    }
  }

  @McpTool(
      name = "add_entity",
      description =
          """
          Fetch one entity's canonical identity from Wikidata by QID (for example Q192668) and add \
          it to the graph, or refresh it if it is already present — calling this twice with the \
          same QID is safe and simply re-fetches. Use search_entities first if you do not already \
          have a QID. Returns the stored node (QID, kind, label), or an error result if Wikidata \
          has no entity at that QID.\
          """,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = false,
              destructiveHint = false,
              idempotentHint = true))
  public CallToolResult addEntity(
      @McpToolParam(required = true, description = "A Wikidata QID, e.g. Q192668.") String qid) {
    CorrelationId.begin();
    try {
      return ToolResults.of(service.addEntity(qid));
    } finally {
      CorrelationId.clear();
    }
  }

  @McpTool(
      name = "get_entity",
      description =
          """
          Look up one entity already in the graph by QID and return it together with its \
          neighbours, grouped by the relationship type connecting them — every DIRECTED edge \
          together, every MEMBER_OF edge together, and so on. Neighbours only appear once \
          expand_entity has been run for this entity; a freshly added entity that has not been \
          expanded yet has none. Read-only; this never calls out to Wikidata. Returns an error \
          result if the QID has not been added yet — call add_entity first.

          This is also where affinity is read back: if the user has rated this entity with \
          note_affinity, the result carries their rating from 1 to 5 and when it last changed. \
          The affinity field is absent for an entity they have never rated, which means "they \
          have not said" and not "they dislike it". Any note they wrote is deliberately NOT \
          returned, here or by any other tool — it is theirs, it is read on their own machine, \
          and asking for it again will not produce it.\
          """,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = true,
              destructiveHint = false,
              idempotentHint = true))
  public CallToolResult getEntity(
      @McpToolParam(required = true, description = "A Wikidata QID already in the graph.")
          String qid) {
    CorrelationId.begin();
    try {
      return ToolResults.of(service.getEntity(qid));
    } finally {
      CorrelationId.clear();
    }
  }
}
