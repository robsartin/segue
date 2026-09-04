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

**Amendment (2026-09-04, issue #233): the consequence beginning "Because the log is written first" is
scoped, and the scope was not stated.** It reads *"Because the log is written first, a failure
applying to the graph leaves the log ahead. That is the correct failure direction: a restart replays
it right."* That is true of a claim
that can eventually project, and only of one. `TinkerGraphStore.record` and `JenaGraphStore.record`
both refuse an edge naming an entity nothing has claimed as a node, and the fourth decision bullet
above makes replay fatal at the first such row — so for that claim the log being ahead is not
recoverable in any sense: the row is permanent under ADR 19, and the restart that was supposed to
"replay it right" fails at it instead, as does every restart after that. Measured for #233 on a
temp-file log: the live call threw, the log held the row, and two consecutive replays both threw
`replay failed at sequence 2`.

**The ordering decision is unchanged, and so is its argument.** What changes is that
`IngestService.record` now asks the store's own precondition — through `GraphStore.node`, on the port,
so both engines answer alike — **before** the append, and refuses with a message naming the endpoint.
The log therefore never gets ahead by a row that can never catch up, when the row names an entity
that has never existed. Appending is still first; the two halves are still not atomic; a crash
between them still leaves the recoverable direction, and now genuinely so.

**That closes one of two cases the original sentence did not cover, not both.** The gate above asks
the RUNNING graph, through `GraphStore.node` — and ADR 44 already leaves the running graph stale
until the next boot, because `GraphStore` has no remove and a retraction is honoured by the fold, not
applied to a store. So an edge naming an entity that WAS a node and has since been retracted still
finds `graph.node` answering present, still passes the gate, still gets appended, and still leaves
the log unable to replay past it — exactly as permanent as the case this amendment closes, for the
same reason. Measured for #233: `record()` returned `ACCEPTED` against the running graph for an edge
naming a just-retracted endpoint, and the next boot threw `replay failed at sequence 4`. Filed as
issue #234 and **not fixed by this amendment** — the gate would need to ask what the log's fold
currently holds, not what the running graph currently holds, to see a retraction that has not yet
been replayed.

**Alternatives considered, and why each lost.**

- **Apply to the graph, then append to the log** — reverse ADR 24's ordering instead of gating.
  Rejected: a crash between the two halves would leave the graph holding a claim the log never
  recorded, and the next boot rebuilds the graph from the log alone, so that claim disappears with
  nothing saying so — the graph acting as a second source of truth, which ADR 19 forbids.
- **Tolerate the row at boot** — let `GraphProjector` skip a claim it cannot apply and continue.
  Rejected for the third time (issues #221, #224, and this one): it drops a claim nobody retracted,
  and it turns `LogProjection.danglingEdges` from an alarm expected to read zero into a count that
  could rise unwatched.
- **Widen `GraphStore` to return a refusal instead of throwing.** Rejected: it widens the port ADR 18
  keeps narrow so the engine choice stays reversible — the same widening ADR 41 already refused for
  the exporter — and it would not even help, since the row is already appended by the time
  `GraphStore.record` runs.
- **Guard only in `SegueService.expandEntity`, resolving both endpoints there.** Rejected as the
  answer, even though it is the one path this defect is reachable through today: a check in front of
  one caller is not a gate, and `retract`, a future dev tool, or a second facade would each need their
  own copy. Issue #228 rejected the same shape in its own words.
- **Drop the claim silently on refusal** — log a warning and discard it. Rejected: the producer-side
  version of tolerating at boot, dropping a claim without telling whoever made it.
- **A plain `IllegalStateException`, matching the store's own throw.** Rejected: the caller has to
  tell this refusal apart from a genuine store failure or a programmer error, and catching
  `IllegalStateException` broadly would swallow all three instead of only the one it is meant for.

**Two things this does not do.** It does not widen `GraphStore` (the stores keep throwing; the gate
is a second, earlier asking of the same question, and `GraphStoreContract` is what keeps the two
answers identical), and it does not repair a log that already carries such a row. That repair is not
"append the missing node claim" — replay is positional, so a claim appended after the edge still
leaves the boot failing at the edge's sequence number. It is `./gradlew retractEntity` on the
endpoint, which withdraws the edge under ADR 44 without deleting anything.
