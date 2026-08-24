---
status: Accepted
date: "2026-08-24"
topic: mcp-protocol-conformance
tags: [project, mcp, interface]
supersedes: []
related: [mcp-tool-surface, mcp-transports, request-correlation]
---
# 27. Pin the MCP protocol revision and follow its error conventions

## Context

The MCP specification is moving quickly and the Java tooling lags it. As of
2026-08-24 the current revision is **2026-07-28**, which removed protocol-level
sessions outright: no `Mcp-Session-Id`, no GET SSE stream, no resumable streams, and
no `initialize` handshake.

Spring AI 2.0.1 ships MCP Java SDK 2.0.x, which targets **2025-11-25**. SDK 2.0.1 is
the newest published artifact, so no Java implementation of 2026-07-28 exists yet.
Leaving the revision implicit would mean claiming conformance we cannot demonstrate.

## Decision

- **Conform to protocol revision 2025-11-25**, which is what the SDK speaks, and
  record it explicitly rather than inheriting it silently from a dependency.
- **Migrating to 2026-07-28 is a tracked follow-up**, blocked on the Java SDK.
- **Two error mechanisms, used as the specification intends:**
  - *Tool execution errors* — API failures, input validation, business-logic problems
    — return `isError: true` with actionable text the model can self-correct from.
    "14 edges added, 3 neighbours unresolved" is this case.
  - *Protocol errors* — unknown tool, malformed request — are JSON-RPC errors.
- **Nothing throws across the MCP boundary**, and no result carries a stack trace or
  filesystem path.
- **The correlating request id appears in `isError` text**, so a failure surfaced in a
  conversation is greppable in the logs.

## Alternatives considered

- **Implement 2026-07-28 directly against the raw protocol** — current conformance, at
  the cost of hand-rolling transport, framing and negotiation that the SDK provides,
  for a revision no client in use speaks yet.
- **Leave the revision implicit** — less to maintain, and it makes a dependency bump
  silently change protocol behaviour, which is the kind of change that should be a
  decision.
- **Report every failure as a JSON-RPC error** — uniform and simpler, and the
  specification is explicit that clients should feed execution errors to the model for
  self-correction while protocol errors are less likely to be recoverable. Collapsing
  them discards the distinction that makes partial results useful.

## Consequences

- The revision is a recorded decision, so upgrading is deliberate and reviewable.
- Everything this design depends on — the stdout rule, the `isError` split,
  `outputSchema` and `structuredContent` — is stable across both revisions, so the
  eventual migration touches transport configuration rather than tool code.
- We cannot yet interoperate with a client that requires 2026-07-28.
- Sessions exist in the pinned revision but are unused; nothing in the design relies
  on per-connection state, which is what makes the later removal a non-event.
