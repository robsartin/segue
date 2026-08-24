---
status: Accepted
date: "2026-08-24"
topic: sqlite-assertion-log
tags: [project, persistence, data]
supersedes: []
related: [assertion-log-source-of-truth, graph-engine-gremlin, taste-layer-separation]
---
# 24. Persist the assertion log in SQLite and project the graph at boot

## Context

ADR 19 makes the append-only assertion log the source of truth and the graph a
derived projection, but slice 0 held both in memory. An MCP server you author into
across sessions needs the log to survive a restart; a graph that evaporates makes
the authoring experience impossible to evaluate, which is the whole point of slice 2.

The access pattern is narrow and known: appends during ingest, one full ordered read
at startup, and small indexed reads for the taste layer. There is exactly one writer.

## Decision

- **The assertion log lives in a single SQLite file**, reached through an
  `AssertionLog` port so the store stays as replaceable as the graph engine.
- **The graph is rebuilt at boot** by `GraphProjector` replaying `readAll()` in
  sequence order into `TinkerGraphStore`. An empty database is a valid state.
- **Nodes are logged as assertions too.** A sealed `LoggedAssertion` permits
  `NodeAssertion` and `AssertionRecord`; replay dispatches on the pattern. A mutable
  node table would make nodes the one thing the graph is not derived from.
- **Replay failure is fatal at boot**, naming the sequence number.
- **Append to the log happens before applying to the graph**, and the two are
  deliberately not atomic.

## Alternatives considered

- **Postgres with Flyway and Testcontainers** — the house style elsewhere, and it buys
  concurrency and richer SQL that a single-writer personal graph does not need. It
  costs a running daemon before the server can start, which is a poor fit for a stdio
  server a client launches as a subprocess.
- **Append-only JSONL file** — closest to the literal model and dependency-free, but
  the taste layer and any indexed read become hand-rolled scans, with no migration story.
- **TinkerGraph's own gryo/graphson persistence** — simplest of all, and it makes the
  graph the source of truth, directly contradicting ADR 19.
- **H2 in file mode** — pure JVM with no native library, but less widely exercised for
  this shape and with no compensating advantage.

## Consequences

- The server starts with no infrastructure, which keeps the stdio transport viable and
  makes integration tests run against a temp file rather than a container.
- Backup and reset are file operations.
- **Replay cost grows with history.** At personal scale this is a startup detail; the
  answer if it stops being one is a snapshot of the projection, never an authoritative graph.
- Because the log is written first, a failure applying to the graph leaves the log ahead.
  That is the correct failure direction: a restart replays it right, whereas the reverse
  ordering would lose the claim permanently.
- One writer is assumed. Concurrent writers would need revisiting, and SQLite would
  surface that as lock contention rather than corruption.
