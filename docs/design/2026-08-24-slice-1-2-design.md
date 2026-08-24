# Segue slices 1 and 2 — SourceAdapter SPI, Wikidata ingest, and the MCP server

**Date:** 2026-08-24
**Status:** Approved, not yet implemented
**Supersedes:** the "Next steps" section of `CLAUDE.md`

## What this is for

Slice 0 answered the engine question and stopped. This design covers the two
slices that turn that spike into something usable: the ingest path that fills the
graph with real data, and the MCP interface that lets you author into it from a
conversation.

The risk being tested is unchanged and is the reason to build both together:
**is MCP a pleasant authoring interface, or do you want a UI within ten minutes?**
Conversational bulk seeding may simply be too slow. Better to learn that in days
than in months, and it cannot be learned from stub data — the answer depends on
whether real Wikidata expansion feels fast and accurate enough to keep going.

## Decisions this design records

Ten ADRs (0024–0033) capture the durable decisions. This document is the
implementation-shaped view of the same material and defers to them on any conflict.

## Architecture

### Module layout

```
domain/    records + edge vocabulary. Zero third-party deps.   (existing)
port/      GraphStore (existing) + AssertionLog, AffinityStore,
           SourceAdapter, EntityResolver                        (new)
tinker/    TinkerGraphStore                                     (existing)
jena/      JenaGraphStore — reference implementation            (existing)
sqlite/    SqliteAssertionLog, SqliteAffinityStore, schema      (new)
wikidata/  WikidataSourceAdapter, WikidataEntityResolver        (new)
ingest/    IngestService, GraphProjector                        (new)
mcp/       SegueService facade + six tool classes               (new)
app/       Spring Boot application + bean wiring                (new)
support/   UuidV7, correlation MDC helpers                      (new)
```

`bakeoff/` is **retired**. It was slice 0 scaffolding and the project is no longer
a bake-off:

- `BakeOff`'s cross-engine comparison becomes `GraphStoreContract`, an abstract
  test run against both adapters. This is strictly better: it was a program you
  had to remember to run, and becomes a gate that fails CI.
- `Fixture` moves to `src/test/java/.../fixture/Fixture.java`. Its placeholder
  `Q9000xx` QIDs then cannot reach a real store, which ADR 22 requires.
- `DomainSelfTest`'s 22 checks become real JUnit tests.

### Dependency rules

Enforced by ArchUnit, so they fail the build rather than eroding:

1. `domain` depends on nothing outside `java.*` and itself.
2. Adapters (`tinker`, `jena`, `sqlite`, `wikidata`) depend on `port` and `domain`
   only — never on each other, never upward.
3. **Only `ingest` may call `GraphStore.record` or `AssertionLog.append`.** This is
   ADR 19's invariant as a compile gate.
4. Spring annotations appear only in `app` and `mcp`. Adapters are plain Java built
   by `@Bean` methods, which is what keeps `wikidata` testable without an
   application context and keeps the SPI honest.
5. **Nothing in `src/main` references `System.out`.** See "The stdout constraint".

### The two SPIs

`CLAUDE.md` specifies one SPI. Writing the flows out shows it needs to be two,
because resolution and expansion are different capabilities with different
implementors — a similarity source like last.fm expands but has nothing to resolve.

```java
public interface SourceAdapter {
    String id();
    boolean supports(NodeKind kind);
    List<AssertionRecord> expand(NodeRecord seed, ExpandContext ctx);
}

public interface EntityResolver {
    String id();
    List<Candidate> search(String query, NodeKind kind, int limit);
    Optional<NodeAssertion> fetch(String qid);
}
```

`WikidataSourceAdapter` and `WikidataEntityResolver` implement one each.

### Nodes are assertions too

`AssertionRecord` is edge-shaped, but replay must reconstruct nodes as well, and
"Wikidata says Q5593 is a PERSON labelled Pablo Picasso" is a sourced claim like
any other. A mutable node table would make nodes the one thing the graph is *not*
derived from, silently breaking ADR 19.

```java
public sealed interface LoggedAssertion permits NodeAssertion, AssertionRecord {}

public record NodeAssertion(String qid, NodeKind kind, String label,
                            Provenance provenance) implements LoggedAssertion {}
```

`AssertionLog.readAll()` returns these in sequence order; replay dispatches on the
pattern. `AssertionRecord` gains the interface and nothing else — no behaviour change.

## Data flow

- **Boot** — `readAll()` in sequence order → `GraphProjector` → `TinkerGraphStore`.
  An empty database is a valid state; you seed through the tools.
- **`search_entities(query, kind?)`** — `EntityResolver` only, writes nothing.
  Wikidata's `description` field is what makes disambiguation readable
  ("Q11571 — Spanish painter").
- **`add_entity(qid)`** — `fetch` → `NodeAssertion` → `IngestService` → log, then graph.
- **`expand_entity(entityId, sources?, maxNew?)`** — every adapter supporting the
  node's kind produces assertions; unknown neighbour QIDs are resolved by a bounded
  virtual-thread fan-out; everything lands through `IngestService` in one batch.
  `maxNew` caps edges **before** the neighbour fetch, so we do not pay to resolve
  neighbours we then discard.
- **`get_entity(entityId)`** — node plus neighbours grouped by edge type. Read-only.
- **`find_paths(from, to, maxHops)`** — routes with per-hop citations, ranked. Read-only.
- **`note_affinity(entityId, rating, note)`** — `AffinityStore`, its own tables,
  never touches the graph.

`assert_edge` stays held back until corroboration is visibly working, per `CLAUDE.md`.

### The port change that comes with ranking

`shortestPaths(from, to, maxHops, limit)` becomes `paths(from, to, maxHops)`.

Today each adapter truncates to `limit` internally, which is precisely what makes
the ranking bug unfixable in one place. The adapters return every route they found
up to `maxHops`; a shared `PathRanking` orders and limits once, above the port:
**weakest confidence descending, hop count as tiebreak.** The old name was a
misnomer anyway — the traversal already returns all routes, not just the shortest.

An internal cap bounds the returned list so a dense neighbourhood cannot produce an
unbounded result.

## The stdout constraint

The MCP stdio binding is normative:

> The server **MUST NOT** write anything to its `stdout` that is not a valid MCP message.

stdout *is* the protocol channel. A stray log line, the Spring banner, a
`System.out.println`, or an uncaught stack trace corrupts the JSON-RPC stream, and
the client sees a parse error rather than a log message. stderr is explicitly the
logging channel, and clients are told not to treat stderr output as an error signal.

Therefore:

- All logging goes to **stderr** (`ConsoleAppender` with `<target>System.err</target>`).
- The Spring banner is disabled.
- ArchUnit forbids `System.out` anywhere in `src/main`.
- An integration test asserts that a full stdio session emits **only** valid
  newline-delimited JSON on stdout.

That last test is the one that actually protects this, because the ArchUnit rule
cannot see into a dependency that misbehaves.

## Protocol conformance

The current MCP revision is **2026-07-28**, which removed protocol-level sessions
entirely — no `Mcp-Session-Id`, no GET SSE stream, no resumability, no `initialize`
handshake.

Spring AI 2.0.1 ships MCP Java SDK 2.0.x, which targets **2025-11-25**. SDK 2.0.1 is
the newest published, so no Java implementation of 2026-07-28 exists yet.

**We pin to 2025-11-25** — what the SDK speaks — and record the gap as a tracked
follow-up. Everything this design depends on (the stdout rule, the `isError`
convention, `outputSchema`/`structuredContent`) is stable across both revisions, so
nothing here has to change when the SDK catches up.

### Errors

- **Tool execution errors** — API failures, validation problems, business-logic
  errors — return `isError: true` with actionable text. The spec is explicit that
  clients should feed these back to the model for self-correction, which is exactly
  what "14 edges added, 3 neighbours unresolved" wants to be.
- **Protocol errors** — unknown tool, malformed request — are JSON-RPC errors.
- Nothing throws across the MCP boundary, and no result carries a stack trace or
  filesystem path.

### Results

Every tool declares an `outputSchema` and returns `structuredContent`, with the
JSON also serialized into a text block for compatibility. Paths, candidate lists
and edge sets are structured data; returning prose would discard that.

### HTTP transport

- `Origin` validated on every request, 403 on mismatch. This is a MUST, and the
  defence against DNS rebinding.
- Bound to `127.0.0.1`, never `0.0.0.0`.
- **RFC 9457 Problem Details** for the non-MCP surface (actuator, health) only.
  The MCP endpoint answers in JSON-RPC and must not be "fixed" to use 9457.

Both transports are built and both are integration-tested.

## Correlation and logging

- **A UUIDv7 request id per JSON-RPC call**, per RFC 9562. JDK 25 has no v7
  generator — `randomUUID()` is version 4 — so `support/UuidV7` implements the
  layout in about fifteen lines: 48-bit big-endian millisecond timestamp, version
  nibble `0111`, variant bits `10`, random remainder. Unit-tested against the RFC:
  version, variant, timestamp round-trip, and ordering across a batch.
  A dependency to mint an identifier is disproportionate; `uuid-creator` becomes
  the answer only if guaranteed intra-millisecond monotonicity is ever needed.
- **W3C Trace Context** (`traceparent`) via Micrometer Tracing on the HTTP
  transport. stdio has no header layer, so there is nothing to propagate there —
  the request id is the only correlation available, and being time-sortable means
  the log sorts by arrival for free.
- Both in MDC, on every log line. Structured logging is Spring Boot's built-in
  ECS format, no dependency.
- **The request id appears in `isError` result text.** When Claude shows you a
  failure you can hand the id straight to `grep`. This is the difference between
  debuggable and not.

## Error handling

The governing idea: the caller is a language model, so a partial result it can see
beats an exception it can only retry.

- **Wikidata failures degrade rather than propagate.** `expand` returns what it got;
  the tool reports the shortfall. Bounded fan-out (semaphore of 8), exponential
  backoff on 429 and 5xx, give up after a few attempts and report.
- **One bad claim does not kill an expand.** Domain records validate hard — QID
  format, confidence range, `validTo` before `validFrom`, codec separators. Adapters
  catch per assertion, skip, and count. A single malformed claim must not abort a
  fan-out of fifty.
- **Log append and graph apply are deliberately not atomic, and log comes first.**
  If the in-memory apply fails the log is ahead of the graph, which is the correct
  failure direction because a restart replays it right. The reverse loses the claim.
- **Replay failure at boot is fatal**, naming the sequence number. Skipping a bad
  record would mean the graph no longer matches the log, and every guarantee in
  ADR 19 rests on it doing so.
- **Unmapped `P31` falls back to `CONCEPT`** and records the raw class QID, so the
  whitelist grows from real data rather than guesses.
- `expand_entity` carries a call budget, satisfying MCP's rate-limiting MUST.

### Privacy

Wikidata's API policy invites a descriptive User-Agent with contact information.
We send the **repository URL, not Rob's email address**. Affinity notes are personal
data under ADR 16 and are never logged, at any level.

## Deliberate deferrals

Each is a real gap, recorded rather than solved:

- **Validity conflicts are first-writer-wins** (ADR 20). The disagreement survives in
  the log even though the projection picks one.
- **QID redirects.** Wikidata merges entities; if we already hold the superseded QID
  that is a genuine identity conflict. Log it, do not auto-merge.
- **No retraction tooling.** Expressible against the log, not yet exposed.
- **`assert_edge`** stays held back.
- **Taste layer is rating plus note only.** `CLAUDE.md` floats first-heard-where and
  seen-live-when; the note field absorbs them until a real need appears.
- **MCP 2026-07-28 migration**, blocked on the Java SDK.

## Testing

- **Contract tests, not per-adapter tests.** One abstract `GraphStoreContract` runs
  against `TinkerGraphStore` *and* `JenaGraphStore` — the bake-off made permanent,
  and what keeps ADR 18's "keep the reference implementation working" from decaying
  into a lie. Same shape for `AssertionLog`.
- SQLite integration runs on a temp file, so **no Testcontainers** — the store choice
  makes the container unnecessary rather than skipped.
- The Wikidata adapter tests against a stub server on the JDK's own `HttpServer`,
  replaying recorded responses. Zero dependencies; WireMock 4.x is still beta.
- **One live smoke test against real Wikidata**, tagged and excluded from CI, run by
  hand. Recorded fixtures cannot tell you Wikidata changed its API — they pass
  forever against a dead endpoint. This is the positive control for the ingest path.
- MCP gets `@SpringBootTest` coverage over both transports, driving real tool calls,
  including the stdout-purity assertion above.
- ArchUnit asserts the rules below. JaCoCo at line >80%, branch >65%.

### ArchUnit rules, and the ADRs they defend

| Rule | Defends |
|---|---|
| Only `ingest` calls `GraphStore.record` / `AssertionLog.append` | ADR 19 |
| Nothing in `src/main` references `System.out` | ADR 28 |
| No `System.err.println` or `java.util.logging`; SLF4J only | ADR 30 |
| Adapters depend on `port`/`domain` only, never each other or upward | ADR 32 |
| `domain` depends on nothing outside `java.*` and itself | ADR 18 |
| Domain types are records, enums or sealed | ADR 11 |
| `wikidata` must not depend on any Spring package | ADR 25 |
| Spring annotations only in `app` and `mcp` | ADR 12 |
| `@Tool` names match MCP's charset and length rules | ADR 26 |
| `NodeKind.values().length == 6` (plain unit test) | ADR 21 |

This table describes the target state once slices 1 and 2 land. As of increment 0,
only the rows that don't depend on `ingest`, `wikidata`, `app`, or `mcp` existing
are actually enforced — see ADR 32's Status column for the current breakdown.

Not mechanically checkable, and therefore review concerns: ADR 20's bitemporal
separation, ADR 23's confidence conventions, and ADR 16's rule that affinity notes
never reach a log.

## Version set

Verified against Maven Central on 2026-08-24, not recalled:

| | |
|---|---|
| Gradle | wrapper pinned and committed |
| Java | toolchain 25, `release 21` |
| JUnit | 6.1.3 |
| ArchUnit | 1.5.0 (`archunit-junit6`) |
| AssertJ | 3.27.7 (4.0.0 is at M1) |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 (MCP SDK 2.0.x, protocol 2025-11-25) |
| sqlite-jdbc | 3.53.2.1 |
| TinkerPop | 3.7.3 (unchanged) |
| Jena | 5.3.0 (unchanged) |

Increment 0 confirms Spring Boot 4.1's Java baseline before `release 21` is final.

## Delivery

Six sequential PRs into a new private `robsartin/segue` repository. Private because
the taste layer is personal data and ADR 16 treats it as such.

| # | Increment | Content |
|---|---|---|
| 0 | Build uplift | Gradle wrapper, version catalog, JUnit 6, Spotless, JaCoCo, ArchUnit, GitHub Actions, LICENSE. `bakeoff/` retired: `DomainSelfTest` → JUnit, `Fixture` → test sources, `BakeOff` → `GraphStoreContract`. Stale `dev.rob.affinity` paths in `CLAUDE.md` and `README.md` corrected. |
| 1 | Path ranking | `paths()` port change, `PathRanking`, both adapters. Closes ADR 23's open issue. |
| 2 | Assertion log | `NodeAssertion`, `LoggedAssertion`, `AssertionLog` port, SQLite adapter, `GraphProjector`, boot replay. |
| 3 | Ingest | `SourceAdapter` and `EntityResolver` SPIs, Wikidata adapter, property whitelist, `P31` mapping, `P580`/`P582` qualifiers, virtual-thread fan-out. |
| 4 | MCP server | Spring Boot app, five world tools, both transports, correlation, structured logging to stderr. |
| 5 | Taste layer | `AffinityStore`, `note_affinity`, its own tables. |

Each keeps `main` green and is reviewable on its own.

## Open question this design does not answer

Whether conversational seeding is fast enough to be pleasant. That is the point of
building it, and the answer arrives at increment 4. If it is no, the graph, the log
and the ingest path are all still correct and reusable behind a different interface —
which is why the MCP layer is deliberately thin.
