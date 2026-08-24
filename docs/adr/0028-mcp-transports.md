---
status: Accepted
date: "2026-08-24"
topic: mcp-transports
tags: [project, mcp, interface]
supersedes: []
related: [mcp-protocol-conformance, structured-logging, mcp-tool-surface, privacy-and-data-handling]
---
# 28. Ship both transports, and keep stdout for the protocol alone

## Context

A local MCP server is normally launched as a subprocess by its client and speaks over
standard streams. A remote or shared one speaks Streamable HTTP. `CLAUDE.md` calls for
both.

The stdio binding is normative about one thing that is easy to violate by accident:

> The server **MUST NOT** write anything to its `stdout` that is not a valid MCP message.

stdout *is* the protocol channel. A stray log line, a framework banner, a
`System.out.println`, or an uncaught stack trace corrupts the JSON-RPC stream and the
client sees a parse error rather than a diagnostic. The specification designates
stderr for logging and tells clients not to read stderr output as an error signal.

## Decision

- **Both transports are built and both are integration-tested.**
- **Nothing writes to stdout but the protocol.** All logging goes to stderr, the
  framework banner is disabled, and ArchUnit forbids any reference to `System.out`
  anywhere in `src/main`.
- **An integration test asserts stdout purity**: a full stdio session must emit only
  valid newline-delimited JSON. The ArchUnit rule cannot see into a misbehaving
  dependency, so the test is what actually protects this.
- **For Streamable HTTP:** the `Origin` header is validated on every request with 403
  on mismatch, which is a specification MUST and the defence against DNS rebinding;
  the server binds to `127.0.0.1`, never `0.0.0.0`.
- **RFC 9457 Problem Details applies to the non-MCP HTTP surface only** — actuator and
  health. The MCP endpoint answers in JSON-RPC and must not be "corrected" to use it.

## Alternatives considered

- **stdio only** — smallest surface and enough for the risk under test, but it forecloses
  pointing anything remote at the server without a later transport project.
- **HTTP only** — easier to exercise with ordinary HTTP tooling, and it is not how a
  client launches a local server, which is the case that actually answers the open risk.
- **Both built, only stdio tested** — cheaper, and an untested transport is one that
  quietly stops working, which is worse than not shipping it.
- **Routing logs to a file rather than stderr** — also avoids the stdout collision, and
  discards the client's ability to surface server diagnostics, which the specification
  explicitly provides for.

## Consequences

- The server runs unchanged whether launched as a subprocess or hosted.
- The stdout rule is enforced twice, statically and dynamically, because the failure it
  prevents is silent and total.
- Binding to localhost means remote use is a deliberate configuration change with its
  own security review, not a default.
- Two transports is a larger test surface, accepted so neither rots.
