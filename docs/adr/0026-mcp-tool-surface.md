---
status: Accepted
date: "2026-08-24"
topic: mcp-tool-surface
tags: [project, mcp, interface]
supersedes: []
related: [mcp-protocol-conformance, mcp-transports, taste-layer-separation, quarantine-model-generated-assertions, path-ranking-by-confidence]
---
# 26. Expose six MCP tools, and hold back assert_edge

## Context

The open risk this project is built to test is whether MCP is a pleasant *authoring*
interface or whether a UI is wanted within ten minutes. That question is only
answerable if the tool surface is small enough to learn in one conversation and
complete enough to seed a real graph.

A model is also very good at proposing plausible relationships it cannot distinguish
from ones it knows. Giving it a direct write tool before corroboration is visible
would let hypotheses accumulate as facts.

## Decision

Six tools, no more:

| Tool | Effect |
|---|---|
| `search_entities(query, kind?)` | candidates with QIDs and disambiguation; writes nothing |
| `add_entity(qid)` | upsert, returns id |
| `expand_entity(entityId, sources?, maxNew?)` | runs adapters, returns new edges |
| `get_entity(entityId)` | node plus neighbours grouped by edge type, plus this entity's affinity if it has been rated |
| `find_paths(from, to, maxHops)` | ranked routes with per-hop citations |
| `note_affinity(entityId, rating, note)` | taste layer, its own tables |

**Amendment (2026-08-25, increment-4a final review).** This table is the target
end-state across increments, not what increment 4a ships. `note_affinity` lands
with the taste layer in increment 5 (ADR 33) and is out of scope here — the five
tools above the `note_affinity` row are what increment 4a's server actually
exposes. `ToolSurfaceTest.noteAffinityIsDeferred()` mirrors the existing
`assertEdgeIsNotAToolYet()` check so this stays true. *(Superseded by the amendment
below: increment 5 shipped the sixth tool, and `noteAffinityIsDeferred()` was replaced by
`sixToolsWithTheSpecifiedNames()`. `assertEdgeIsNotAToolYet()` still stands.)*

**Amendment (2026-08-25, increment 5 / ADR 39).** `note_affinity` has landed, so the server now
exposes the full six. Reading affinity back is surfaced on `get_entity` — the `get_entity` row above
says so — rather than as a seventh tool, precisely so this table stays six rows long; ADR 39 argues
that choice against both a dedicated `get_affinity` and a bulk `list_affinity`, the latter on ADR
16's data minimisation. `ToolSurfaceTest.theTasteLayerAddsOneToolAndNoMore()` asserts the count, so
adding either is a change to this ADR before it is a change to the code.

- **`assert_edge` is deliberately absent** until corroboration is visibly working.
  When it arrives its assertions carry the `llm:` prefix of ADR 23.
- **Every tool returns a `CallToolResult` built by hand** (`mcp/ToolResults`),
  carrying the JSON as `structuredContent` and again as a text content block for
  clients that render only `content`; `isError` is set from the result's outcome.
  *(Amendment, 2026-08-25: the original wording here said "declares an
  `outputSchema` and returns `structuredContent`" — Spring AI's
  annotation-driven schema generation (`generateOutputSchema = true`) puts a
  tool on a STRUCTURED-mode path whose result conversion produces only
  `structuredContent`, never a text block and never `isError: true`, which
  silently drops the error signalling this ADR requires. There is therefore no
  framework-generated `outputSchema` in this tool surface — see the increment-4a
  final-fix report, FIX 1.)*
- **Tool names follow MCP's naming rules** — ASCII letters, digits, underscore, hyphen
  and dot, within the length bound. *(Amendment, 2026-08-25: `ToolSurfaceTest`
  asserts this by reflection over the `@McpTool` annotations, not ArchUnit —
  ArchUnit's structural rules are not built for reading annotation attribute
  values.)*
- **All tool inputs are validated** before reaching the domain, and `expand_entity`
  carries a call budget, satisfying the specification's rate-limiting requirement.
- **`note_affinity` is the only tool touching the taste layer**, and it never writes
  to the graph.

## Alternatives considered

- **A general `query` tool taking Gremlin** — maximally capable in one tool, and it
  exposes the engine choice ADR 18 exists to keep reversible, hands the model an
  unbounded execution surface, and abandons per-tool schemas.
- **Including `assert_edge` from the start** — would make authoring faster immediately,
  and would let uncorroborated guesses enter the graph before there is any visible
  signal separating them from sourced facts.
- **Splitting `expand_entity` per source** — clearer provenance at call time, and it
  makes the tool list grow with every adapter, which is exactly what the SPI exists to
  avoid.
- **Returning prose rather than structured content** — simpler, and it discards
  machine-readable structure the protocol supports for precisely this case.

## Consequences

- The surface is learnable in one sitting and stable as sources are added.
- Structured results mean the model gets typed data rather than text it must reparse.
- Seeding is conversational and therefore possibly slow. That is the risk under test,
  not a defect to design around in advance.
- Nothing in the surface can currently retract a claim; retraction is expressible
  against the log but deliberately unexposed.
