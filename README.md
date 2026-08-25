# Segue — engine bake-off

One domain model, one port, two graph engines, four queries. The point is to
decide **empirically** whether Gremlin or RDF/SPARQL is the better home for a
provenance-first affinity graph, before committing to either.

## The bet this tests

Your design has three properties that most graph examples don't:

1. **Multigraph** — Nick Cave both *wrote* and *scored* The Proposition. Two
   relationships, one pair of nodes.
2. **Provenance-first** — every edge carries who claimed it, when, and how much
   you trust them. Corroboration across sources is a first-class signal.
3. **Bitemporal** — when a fact was true in the world is independent of when you
   learned it.

Engines differ sharply on 2 and 3. That's what the four queries measure.

## Layout

```
domain/     records + the Wikidata-derived edge vocabulary. No dependencies.
port/       GraphStore — the seam that makes the engine choice reversible.
tinker/     Apache TinkerPop / Gremlin, on the in-memory TinkerGraph.
jena/       Apache Jena, one named graph per assertion.
```

`NodeKind` has six constants — PERSON, GROUP, WORK, PLACE, EVENT, CONCEPT — and
that is deliberate. "Musician", "novelist", "director" are roles, expressed as
edges. One Nick Cave node is all three at once and the enum never grows.

## Running it

```bash
./gradlew check    # format, tests, coverage, arch rules
```

No infrastructure: TinkerGraph and Jena's TxnMem dataset are both in-process.

## Run it as an MCP server

A Spring Boot MCP server exposing five tools (ADR 26) over **both** transports
ADR 28 commits to. Build the jar once:

```bash
./gradlew bootJar
```

Which transport you get is a launch-time choice, and the two are mutually
exclusive: the `stdio` profile starts no HTTP listener at all, and without it no
process reads stdin.

### stdio — for a client that launches segue as a subprocess

```bash
java -Dspring.profiles.active=stdio -jar build/libs/segue-0.1.0-SNAPSHOT.jar
```

The server speaks newline-delimited JSON-RPC over stdin/stdout and logs
structured JSON to stderr — nothing else is allowed to touch stdout (ADR 28,
ADR 30). Point an MCP client at it with a config block like:

```json
{
  "mcpServers": {
    "segue": {
      "command": "java",
      "args": [
        "-Dspring.profiles.active=stdio",
        "-jar",
        "/absolute/path/to/segue-0.1.0-SNAPSHOT.jar"
      ]
    }
  }
}
```

### Streamable HTTP — for a client that connects to a running segue

No profile: HTTP is what a plain launch gives you.

```bash
java -jar build/libs/segue-0.1.0-SNAPSHOT.jar
```

The MCP endpoint is `http://127.0.0.1:8080/mcp`; `SEGUE_HTTP_PORT` moves it.
A client config block for it looks like:

```json
{
  "mcpServers": {
    "segue": {
      "type": "http",
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

To drive it by hand, remember the two things every Streamable HTTP client has to
do: send `Accept: application/json, text/event-stream` on every POST, and echo
back the `Mcp-Session-Id` the `initialize` response returns.

```bash
curl -si -X POST http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

**It is deliberately reachable only from this machine.** The server binds to
`127.0.0.1`, and it refuses any request whose `Origin` or `Host` is not loopback
— 403 and 421 respectively. That is not belt-and-braces: binding to loopback
alone would still let a web page open in your own browser POST to
`localhost:8080`, which is the DNS-rebinding attack. There is no authentication,
because there is nothing to authenticate to a server only you can reach. Making
segue reachable from anywhere else is a code change to the allowlist plus a
decision about auth — see `docs/adr/0037-streamable-http-transport-on-the-servlet-stack.md`.

### Either way

Five tools are exposed — `search_entities`, `add_entity`, `expand_entity`,
`get_entity`, `find_paths` — documented in `docs/adr/0026-mcp-tool-surface.md`.
The tool surface, the protocol revision and the graph behind them are identical
on both transports. `SEGUE_DB` overrides where the assertion log lives (defaults
to `~/.segue/segue.db`).

## The four queries, and why each one

| | Query | Why it's here |
|---|---|---|
| Q1 | Shortest paths, each hop citing its sources | The payoff feature. Also where the engines diverge most. |
| Q2 | Everything source X said after time T | The blast-radius query for when a source turns out wrong. |
| Q3 | Relationships valid on a given date | Band tenures. Tests that time travel actually works. |
| Q4 | Edges backed by ≥N distinct sources | What stops model hypotheses becoming facts. |

## Findings

**Q1 — paths. Gremlin wins decisively.**

| | non-comment lines at slice 0 |
|---|---|
| Gremlin | 27 |
| Jena | 81 |

Gremlin's implementation is one traversal:

```java
g.V().has(ENTITY, P_QID, fromQid)
 .repeat(__.bothE().otherV().simplePath())
 .until(__.or(__.has(P_QID, toQid), __.loops().is(P.gte(maxHops))))
 .has(P_QID, toQid)
 .path()
```

SPARQL 1.1 property paths can test *that* two entities are connected —
`?a (afp:X|^afp:X)* ?b` — but there is no standard way to get the path **back**.
So a citable explanation requires hand-rolled depth-first enumeration, a
neighbour cache to stop it being quadratic (one SPARQL round trip per node
expanded), and a reconstruction pass. That's the 81 lines, and none of it is
incidental.

**And the failure mode matters more than the line count.**

The first working run exposed a bug that only running could have found. The
obvious neighbour query for the RDF walk is `SELECT DISTINCT ?other` — which
walks **nodes**. Nick Cave reaches The Proposition two ways (`COMPOSED_FOR` and
`WROTE_SCREENPLAY_FOR`), and `DISTINCT ?other` collapses those into one
neighbour: one route silently disappeared, the engine backfilled with a longer
detour, and the reconstruction step had to *guess* which relationship it had
walked. It returned plausible answers, not errors.

Gremlin never had this bug and structurally cannot: `bothE().otherV()` steps
through edges by construction, so parallel edges are distinct paths without
anyone thinking about it. The RDF adapter now carries `(predicate, other,
direction)` through the whole enumeration — which is most of why its Q1 grew
from 27 lines to 81.

On a graph whose entire premise is "record everything, including several
relationships between the same two things," an engine whose natural traversal
idiom quietly drops parallel edges is inviting the wrong kind of failure.

**Q4 — corroboration. RDF wins on kind, not on line count.**

The whole query, executed and indexable by the engine:

```sparql
SELECT ?f ?p ?t (COUNT(DISTINCT ?src) AS ?n) WHERE {
  GRAPH ?g { ?f ?p ?t }
  ?g af:source ?src .
}
GROUP BY ?f ?p ?t
HAVING (COUNT(DISTINCT ?src) >= 2)
```

The Gremlin equivalent is fewer lines of Java but it is `g.E().toList().stream()`
— a **full edge scan in application memory**, because provenance is packed into
an opaque edge property the traversal engine cannot see. Same for Q2.

That opacity isn't laziness, it's structural: property-graph edges are
single-valued and nothing can point at an edge. The alternatives are to encode
(what this does — cheap paths, opaque provenance) or to reify every relationship
as a Claim vertex (queryable provenance, but every logical hop becomes three
graph hops and Q1 gets much worse). RDF named graphs avoid the choice entirely.

**Two things RDF gets for free that are easy to miss:**

- *No merge code.* Two sources claiming the same relationship are simply two
  named graphs holding the same triple. The Gremlin adapter needs explicit
  find-then-append logic for this, plus the codec.
- *Graph-level retraction.* Dropping everything a bad source said is a DELETE on
  the graphs it owns, with no risk of removing a claim another source also makes.
  In the property graph it's a scan-and-rewrite of every affected edge's blob.

**Entity IRIs are real Wikidata IRIs** in the Jena adapter
(`http://www.wikidata.org/entity/Q...`), so a Wikidata dump or a federated SPARQL
query loads into the same store with no identifier mapping at all. That is a
bigger deal for slice 1 than it looks.

## The recommendation

**Gremlin, unless you expect provenance queries to become the main event.**

Paths are the feature you're building this for, and the gap on Q1 is not close —
it's the difference between stating your intent and implementing a graph
algorithm. Q2 and Q4 degrading to full scans is real but survivable: a personal
affinity graph won't outgrow a scan over tens of thousands of edges for years,
and by then the assertion log lets you project into a second store for exactly
those queries.

Take RDF instead if, when you read the two adapters, the named-graph model makes
you think "that's what I meant" — the retraction and corroboration semantics are
genuinely better, and Wikidata ingest is free. The tell is whether you find
yourself wanting to *audit* the graph more often than *walk* it.

Either way the `GraphStore` port means this is an afternoon to revisit, not a
rewrite.

## Verification status

**Runs green.** Java 25 (Temurin), macOS aarch64, compiled at `release 21`.
`./gradlew check` runs formatting, the whole test suite, coverage and the
architecture rules. Both engines return identical results for all four queries, including
identical full route sets between Cave and Hillcoat.

The suite is layered: domain record invariants and the reference edge fold run
without a store; `GraphStoreContract` runs the four queries against both
`TinkerGraphStore` and `JenaGraphStore`, so the cross-engine comparison is a
merge gate rather than a program someone remembers to run; `ArchitectureTest`
enforces the ADRs mechanically.

Coverage is gated at 80% line/instruction and 65% branch, and comfortably
clears both; `build/reports/jacoco/` has the current numbers.

*(Historically: originally verified under Maven 3.9.13 with 22 hand-rolled
checks in a `main()` method. The build is now Gradle and those checks are real
tests.)*

Two real bugs were found by running it, neither caught by inspection:

1. `Property.map(LocalDate::parse)` — TinkerPop's `Property` is **not** a
   `java.util.Optional`. It has `orElse`, `orElseGet` and `ifPresent` but no
   `map`. Hard compile error, now a `dateProperty()` helper.
2. The `DISTINCT ?other` multigraph bug described above — which passed the
   original test suite, because that suite only compared the *shortest* path
   between engines rather than the full route set. The comparison is now
   signature-based over every route.

The SPARQL was independently replayed against an equivalent rdflib dataset
(`verification/`) and agrees with the Java in every case.

**The QIDs in `Fixture` are placeholders** in the Q9000xx range, not real
Wikidata identifiers. Slice 1 replaces them via `wbsearchentities`; nothing
depends on their values.

## Deliberately not here

This README covers slice 0 — the part that answers the engine question, which
is complete. The `SourceAdapter` SPI, the Wikidata ingest, and the MCP server on
both transports (see "Run it as an MCP server" above) have since landed as later increments,
tracked as GitHub issues and ADRs rather than narrated here. The open risk
remains #4 from the original plan: whether MCP is a pleasant *authoring*
interface or whether you want a UI within ten minutes.
