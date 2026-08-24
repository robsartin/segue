# Segue — working notes

A personal "things I'm interested in" graph. The name is the point: a segue is
how one thing leads into the next, and the payoff feature is exactly that move —
given two things you like, show the route between them with receipts.
 Nodes are entities (people, groups,
works, places, events, concepts) across ANY domain — music, film, literature,
speakers, whatever. Edges are relationships, each carrying the provenance of who
claimed it. The payoff feature is **explanation**: "you like this because
X → Y → Z", with every hop citable to a source.

This repo is currently **slice 0**: the domain model plus a two-engine bake-off
that answered the graph-database question. Ingest and the MCP interface are not
built yet.

## Build and run

```bash
./gradlew run             # the bake-off, both engines
./gradlew selfTest        # zero-dependency domain + fixture checks
```

Gradle, not Maven. **Gradle 9.1.0 is the minimum for running on Java 25** (9.7.1
is current), so the wrapper is pinned. If `gradlew` is missing, bootstrap once
with a local Gradle:

```bash
gradle wrapper --gradle-version 9.7.1
```

Zero-dependency domain tests (useful when offline):

```bash
javac -d /tmp/out $(find src/main/java/dev/rob/affinity/domain src/main/java/dev/rob/affinity/port -name '*.java') \
      src/main/java/dev/rob/affinity/bakeoff/Fixture.java \
      src/main/java/dev/rob/affinity/bakeoff/DomainSelfTest.java
java -cp /tmp/out com.robsartin.segue.bakeoff.DomainSelfTest
```

Java 25 local, compiled at `release 21`. TinkerPop 3.7.3 targets Java 11/17 but
runs fine on 25.

## Decision already made: use Gremlin

Both `TinkerGraphStore` (Apache TinkerPop) and `JenaGraphStore` (RDF/SPARQL,
one named graph per assertion) exist and return identical results on all four
bake-off queries. **Gremlin won.** Two reasons, in order of importance:

1. The natural RDF neighbour query, `SELECT DISTINCT ?other`, walks *nodes* and
   silently collapses parallel edges — it returns plausible wrong answers on a
   multigraph. Gremlin's `bothE().otherV()` steps through edges by construction
   and cannot have this bug.
2. Path queries: 27 non-comment lines in Gremlin vs 81 in Jena, because SPARQL
   property paths can test connectivity but cannot return the path.

RDF is genuinely better at provenance (corroboration is one `GROUP BY`; free
merge semantics; graph-level retraction; Wikidata IRIs need no mapping). Keep
`JenaGraphStore` as a working reference. Revisit only if auditing the graph
becomes more common than walking it — the assertion log makes that a replay, not
a rewrite.

Full reasoning: `README.md`, and `decisions/graph-engine.md` in the Interest
Finder project.

## Architecture

```
domain/   records + Wikidata-derived edge vocabulary. NO third-party deps.
port/     GraphStore — the seam that keeps the engine choice reversible.
tinker/   Gremlin adapter (the chosen one).
jena/     RDF adapter (reference implementation, keep it working).
bakeoff/  Fixture, BakeOff, DomainSelfTest.
```

## Design invariants — do not violate without a deliberate decision

- **`NodeKind` has exactly six constants** (PERSON, GROUP, WORK, PLACE, EVENT,
  CONCEPT). "Musician", "director", "novelist" are ROLES expressed as edges.
  Wanting to add MUSICIAN or FILM means the model is being used wrong.
- **Edge vocabulary is borrowed from Wikidata properties**, not invented. In the
  real system these are DB rows, not a Java enum — `EdgeTypes` is a spike stand-in.
- **Adapters emit `AssertionRecord`, never `EdgeRecord`.** The append-only
  assertion log is the source of truth; the graph is a derived projection.
- **Validity dates live on the assertion, not the edge** — sources are allowed to
  disagree about when something was true.
- **Two independent time dimensions**: `validFrom`/`validTo` (true in the world)
  vs `provenance.assertedAt` (when we learned it). Never conflate them.
- **Model-generated edges use a `llm:` source prefix** and stay quarantined until
  a real source corroborates. `EdgeRecord.isUncorroboratedHypothesis()`.

## Gotchas already paid for

- TinkerPop's `Property` is **not** a `java.util.Optional`. It has `orElse`,
  `orElseGet`, `ifPresent` — but no `map()`.
- Never write `SELECT DISTINCT ?other` for graph traversal in the Jena adapter.
  Carry `(predicate, other, direction)`. See the class comment on
  `JenaGraphStore.shortestPaths`.
- `label` is reserved in TinkerPop (it's the element label). The vertex property
  for a display name is `name`.
- **When comparing engines, compare full result SETS, not the first element.**
  Comparing only the shortest path is what let the multigraph bug pass CI.
- The QIDs in `Fixture` are placeholders (Q9000xx), not real Wikidata ids.

## Known open issues

- **Shortest path is the wrong default ranking.** In the fixture the shortest
  Cave→McCarthy route is an unverified model guess (confidence 0.30) while the
  trustworthy route is three hops (1.00). `PathResult.weakestConfidence()` exists
  but nothing ranks by it. Fix before the graph gets big.
- Validity conflict resolution is first-writer-wins in both adapters. Deliberately
  deferred, not solved.
- Provenance in the Gremlin adapter is packed into an opaque edge property
  (`ProvenanceCodec`), so Q2 and Q4 are full edge scans. Fine at personal scale;
  the alternative is reifying every relationship as a Claim vertex, which makes
  paths much worse.

## Next steps

### Slice 1 — SourceAdapter SPI + Wikidata ingest

```java
public interface SourceAdapter {
    String id();
    boolean supports(NodeKind kind);
    List<AssertionRecord> expand(NodeRecord seed, ExpandContext ctx);
}
```

Wikidata first, deliberately — no API key, cross-domain by construction, and it
supplies both the QID identity spine and the edge vocabulary. Use
`wbsearchentities` for resolution and `wbgetentities` for claims; map a whitelist
of ~15 properties to edge types; qualifiers P580/P582 become
`validFrom`/`validTo`. Virtual threads for the claim fan-out. This also retires
the placeholder QIDs in `Fixture`.

Design rule: adding a source must not require touching the graph layer.

### Slice 2 — MCP server

Spring Boot with the MCP server starter; streamable HTTP plus a stdio profile.
Six tools, no more:

- `search_entities(query, kind?)` → candidates with QIDs and disambiguation
- `add_entity(qid)` → upsert, returns id
- `expand_entity(entityId, sources?, maxNew?)` → runs adapters, returns new edges
- `get_entity(entityId)` → node plus neighbours grouped by edge type
- `find_paths(from, to, maxHops)` → routes with per-hop citations
- `note_affinity(entityId, rating, note)` → taste layer, its own table

Hold back `assert_edge` (model-proposed hypotheses) until corroboration is
visibly working.

**Keep the taste layer separate from the world-facts layer.** "I like this" is a
claim about the user with its own dimensions (rating, first-heard-where, seen-live-when);
Wilco's lineup is a claim about the world. Separate tables so recommendations can
be re-derived by traversing the world graph filtered through affinity.

### The open risk

Whether MCP is a pleasant *authoring* interface or whether a UI is wanted within
ten minutes. Conversational bulk seeding may be too slow. Slice 2 is designed to
find that out cheaply — better to learn it in three days than three months.
