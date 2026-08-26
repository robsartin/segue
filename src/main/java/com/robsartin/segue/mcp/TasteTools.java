package com.robsartin.segue.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * The one MCP tool that writes the taste layer, and the only one in the surface that touches
 * personal data (ADR 33).
 *
 * <p><b>Its own class, for one tool.</b> {@code note_affinity} could have been a sixth method on
 * {@link EntityTools} — it takes a qid, like the other three. It is separate because ADR 33's whole
 * decision is that taste and world facts are different kinds of claim with different privacy and
 * retention regimes, and a boundary that exists in the ports, the tables and the ArchUnit rules but
 * not in the tool layer is a boundary with a hole in it. Anyone opening this file is looking at
 * every line of code that writes affinity.
 *
 * <p><b>There is no read tool here, on purpose.</b> ADR 39 surfaces reading affinity back on {@code
 * get_entity} rather than as a seventh tool: ADR 26 pins the surface at six, the model already
 * calls {@code get_entity} to ask "what do I know about this", and a bulk {@code list_affinity}
 * would hand out the entire taste layer in one call for no use case that exists yet (ADR 16's data
 * minimisation). {@code ToolSurfaceTest.theTasteLayerAddsOneToolAndNoMore()} holds that line.
 *
 * <p>Wired as a {@code @Bean} in {@code SegueConfiguration} like the other tool classes — see
 * {@link EntityTools}' Javadoc for how the starter discovers them. The method begins with {@link
 * CorrelationId#begin()} and clears it in a {@code finally} (ADR 29), and returns {@link
 * CallToolResult} directly (see {@link ToolResults}).
 */
public class TasteTools {

  private final SegueService service;

  public TasteTools(SegueService service) {
    this.service = service;
  }

  @McpTool(
      name = "note_affinity",
      description =
          """
          Record what the user thinks of one entity already in the graph: a rating from 1 to 5, \
          and optionally a note in their own words. This is the taste layer — a first-person \
          statement, stored separately from the sourced world facts and never mixed into the \
          graph.

          The rating is required and is an integer from 1 (strongly not for them) to 5 (a \
          favourite). Low ratings are as useful as high ones: 1 and 2 are how "not for me" gets \
          recorded, and there is no separate dislike concept. The note is optional; a rating on \
          its own is a complete entry, so do not press the user for words they did not offer.

          The entity must already be in the graph — call search_entities then add_entity first. \
          Rating something Wikidata does not have is not possible, and this returns an error \
          result rather than inventing an identity for it.

          Re-rating the same entity replaces the previous rating rather than adding to it; the \
          result carries the timestamp of the change. Read a rating back with get_entity, which \
          returns the affinity alongside the entity's neighbours.\
          """,
      annotations =
          @McpTool.McpAnnotations(
              readOnlyHint = false,
              destructiveHint = false,
              idempotentHint = true))
  public CallToolResult noteAffinity(
      @McpToolParam(required = true, description = "A Wikidata QID already in the graph.")
          String qid,
      @McpToolParam(
              required = true,
              description =
                  "How much the user likes this entity, 1 (strongly not for them) to 5 (a"
                      + " favourite). Required.")
          int rating,
      @McpToolParam(
              required = false,
              description =
                  "The user's own words about this entity — where they first heard it, who"
                      + " recommended it, why it matters. Optional; omit it when they did not"
                      + " say.")
          String note) {
    CorrelationId.begin();
    try {
      return ToolResults.of(service.noteAffinity(qid, rating, note));
    } finally {
      CorrelationId.clear();
    }
  }
}
