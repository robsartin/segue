---
status: Accepted
date: "2026-08-24"
topic: request-correlation
tags: [project, observability, mcp]
supersedes: []
related: [structured-logging, mcp-protocol-conformance, observability-baseline, mcp-transports]
---
# 29. Correlate every request with a UUIDv7 and W3C Trace Context

## Context

When a tool call goes wrong, the failure surfaces inside a conversation. Getting from
"Claude said the expand partly failed" to the log lines that explain why needs a
shared identifier, and the two transports offer very different starting points: HTTP
carries headers an upstream can populate, while stdio has no header layer at all —
per-request metadata travels in the JSON-RPC body and nothing propagates in.

## Decision

- **Every incoming JSON-RPC request is assigned a UUIDv7**, per RFC 9562. It goes into
  MDC and appears on every log line emitted while handling that request.
- **W3C Trace Context (`traceparent`) is honoured on the HTTP transport** via Micrometer
  Tracing, so segue participates in a wider trace when there is one. On stdio there is
  nothing to propagate from, and the request id is the only correlation available.
- **UUIDv7 is generated in-project**, in `support/UuidV7`, implementing the RFC layout:
  a 48-bit big-endian millisecond timestamp, version nibble `0111`, variant bits `10`,
  and a random remainder. JDK 25 has no v7 generator — `UUID.randomUUID()` is version 4.
  Tested against the RFC for version, variant, timestamp round-trip and batch ordering.
- **The request id is included in `isError` tool result text**, so an error shown in a
  conversation can be pasted straight into a log search.

## Alternatives considered

- **`UUID.randomUUID()` (version 4)** — already in the JDK and needs no code, but it is
  unordered, so logs cannot be sorted by identifier and storage locality is lost. Version
  7's time prefix is the entire reason to prefer it.
- **The `uuid-creator` library** — RFC-tested and handles guaranteed monotonicity within
  a single millisecond. A dependency to mint an identifier is disproportionate for about
  fifteen lines of specified bit layout; this becomes the right answer if intra-millisecond
  monotonicity is ever actually required.
- **Trace context alone, with no request id** — one fewer concept, and it leaves stdio
  with no correlation at all, which is the transport the authoring risk is tested on.
- **Reusing the client's JSON-RPC `id`** — free, and it is client-chosen, frequently a
  small integer, and not unique across restarts or clients.

## Consequences

- Every log line for a request is findable from one identifier, on both transports.
- Identifiers sort by arrival time, so a log tail reads chronologically without parsing
  timestamps.
- MDC must be propagated explicitly across the virtual-thread fan-out in ingest;
  it does not inherit by itself, and losing it there would blank out exactly the
  slow, failure-prone work the correlation exists to explain.
- A hand-written generator is code we own and must keep correct, which is why its
  conformance is asserted against the RFC rather than assumed.
