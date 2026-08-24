---
status: Accepted
date: "2026-08-24"
topic: structured-logging
tags: [project, observability, mcp]
supersedes: []
related: [request-correlation, mcp-transports, observability-baseline, privacy-and-data-handling]
---
# 30. Emit structured logs to stderr

## Context

ADR 15 commits to an observability baseline, and ADR 28 establishes that stdout
belongs to the MCP protocol and nothing else. Those two combine into a specific
requirement: logs must be machine-parseable, and they must not go where the protocol
lives.

The taste layer adds a second constraint. Affinity notes are personal statements about
what the user likes and why, and ADR 16 treats them as personal data.

## Decision

- **Logs are structured JSON in ECS format**, using Spring Boot's built-in structured
  logging. No additional dependency.
- **Every log line goes to stderr.** The console appender targets `System.err`; the
  framework banner is off.
- **SLF4J is the only logging API.** ArchUnit forbids `System.err.println` and
  `java.util.logging` alongside its ban on `System.out`.
- **The correlation identifiers from ADR 29 are carried in MDC** and appear on every line.
- **Affinity notes and ratings are never logged, at any level.** Entity identifiers may
  be; what the user said about an entity may not.
- **Outbound third-party requests identify segue by repository URL, not by personal
  email address**, even where an API's usage policy invites contact information.

## Alternatives considered

- **Plain text logs** — more readable when tailing by hand, and unparseable when the
  question is "what happened during request X", which is the question that actually gets
  asked.
- **Logging to a file** — also keeps stdout clean, and discards the client's ability to
  capture and surface server diagnostics, which the stdio binding explicitly provides for.
- **A Logstash or ECS encoder dependency** — more formatting control, unnecessary now that
  the framework ships structured logging natively.
- **Logging affinity notes at DEBUG** — convenient while developing, and it writes personal
  data to disk in a file nobody thinks of as a data store. A level is not a boundary.

## Consequences

- Logs are queryable by correlation id, tool name, and source.
- stdout stays clean by construction, which is what keeps the stdio transport working.
- Debugging the taste layer means reasoning from identifiers and counts rather than
  content, which is the intended trade.
- ECS JSON is verbose to read raw; `jq` is the expected reading tool.
