# Segue — working notes

A personal "things I'm interested in" graph. The name is the point: a segue is
how one thing leads into the next, and the payoff feature is exactly that move —
given two things you like, show the route between them with receipts.
 Nodes are entities (people, groups,
works, places, events, concepts) across ANY domain — music, film, literature,
speakers, whatever. Edges are relationships, each carrying the provenance of who
claimed it. The payoff feature is **explanation**: "you like this because
X → Y → Z", with every hop citable to a source.

Slice 0 — the domain model plus a two-engine bake-off that answered the
graph-database question — is complete. Ingest and the MCP interface are not
built yet; the increments that build on slice 0 are tracked as GitHub issues.

## Build and run

```bash
./gradlew check           # format, tests, coverage, arch rules — the full CI gate
./gradlew test            # tests only
./gradlew spotlessApply   # fix formatting
./gradlew liveTest        # tagged live tests against the real Wikidata API; excluded from check
```

Gradle, not Maven. The wrapper is pinned to 9.7.1 and committed; **Gradle 9.1.0 is the
minimum that runs on Java 25**. The build uses a toolchain of JDK 25 and compiles at
`release 21`.

Versions live in `gradle/libs.versions.toml`, never in `build.gradle.kts`.

TinkerPop and Jena both target older JDKs than 25 and both run fine on it —
verified by every `GraphStoreContract` run, not assumed. **Don't name their versions
here**; `gradle/libs.versions.toml` is the only place they live, and a number repeated
in prose goes stale the first time Dependabot lands a bump.

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

Full reasoning: `docs/adr/0018-graph-engine-gremlin.md`. All decisions are recorded in
`docs/adr/`; the slice 1 and 2 design is `docs/design/2026-08-24-slice-1-2-design.md`.

## Architecture

```
domain/   records + Wikidata-derived edge vocabulary. NO third-party deps.
port/     GraphStore, AssertionLog, SourceAdapter, EntityResolver — the seams.
tinker/   Gremlin adapter (the chosen one).
jena/     RDF adapter (reference implementation, keep it working).
sqlite/   SqliteAssertionLog — the append-only log persisted to one file (ADR 24).
wikidata/ The first source: resolution and expansion. Plain Java, no Spring.
ingest/   IngestService (the only write path) and GraphProjector (boot replay).
support/  Plain-Java cross-cutting helpers with no project dependencies of their
          own — currently UuidV7, the RFC 9562 v7 id generator used for request
          correlation.
mcp/      The five MCP tools (EntityTools, GraphTools), SegueService (the facade
          they call), CorrelationId. Spring-only package (ADR 32) — annotated
          with the starter's @McpTool, but plain enough to unit test.
app/      SegueApplication, SegueConfiguration (all wiring lives here),
          SegueProperties, application.yaml. The other Spring-only package;
          owns the stdio/HTTP transport profiles.
```

Tests mirror this, plus `fixture/` (the Nick Cave neighbourhood, test-only) and
`arch/` (the ArchUnit rules that enforce the ADRs).

The engine bake-off is now `GraphStoreContract` — an abstract test run against both
adapters, so the cross-engine comparison is a merge gate rather than a program.

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
  `JenaGraphStore.paths`.
- `label` is reserved in TinkerPop (it's the element label). The vertex property
  for a display name is `name`.
- **When comparing engines, compare full result SETS, not the first element.**
  Comparing only the shortest path is what let the multigraph bug pass CI.
- The QIDs in `Fixture` are placeholders (Q9000xx), not real Wikidata ids.
- **Wikidata states creative relations on the WORK, not the person.** Fetching an
  entity returns only claims stated on it, so expanding a film finds its director
  while expanding a person does not find their films. `EdgeType.wikidataInverted`
  fixes the stored direction, not the discovery problem. **Fixed (ADR 36, issue
  #20):** `WikidataSourceAdapter` runs two passes — `ClaimMapper` over the claims
  stated ON the seed, then `ReverseClaims`, one SPARQL query to the Query Service
  asking which items point AT it. Nick Cave went 4 edges → 88, his band 0 → 106.
  Don't "simplify" the adapter back to one call; the second pass is the whole fix.
- **A truthy `wdt:` triple has no references and no qualifiers.** That is why every
  reverse-discovered edge is graded 0.80 rather than ADR 23's referenced 1.00, and
  why it carries no `validFrom`/`validTo` — the Blixa-Bargeld-1983-to-2003 window
  ADR 20 uses as its example only survives the forward direction. It is also why
  `wdt:` reproduces the deprecated-statement filter for free. Not a bug, a trade.
- **Some Wikidata properties are inverses of each other, and ingesting both ends
  records one relationship twice.** P527 "has part" is the inverse of P463 "member
  of" and of P361 "part of", so once the reverse lookup existed every band
  membership became two edges — two identical `find_paths` routes, one relationship
  under two `get_entity` type groups, two slots against one `maxNewEdges` bound
  (issue #33). The fix is `EdgeType.fallbackOnly`: P527 is left out of the reverse
  query and its forward claims are dropped whenever the reverse pass ran, so it
  contributes only on the degraded path — where a band still expands to its roster
  with the Query Service down. **Registering a property that is Wikidata's inverse
  of one already in `EdgeTypes` reintroduces this**; mark it `fallbackOnly` or do
  not register it. See ADR 36's issue-#33 amendment.
- **`query.wikidata.org` holds only the main graph.** Scholarly articles live on
  `query-scholarly.wikidata.org`, so `AUTHORED` (P50) silently under-reports for an
  academic seed: Einstein returns 32 on the main endpoint against 117 in reality.
  No error, just a smaller number. ADR 36 records why segue documents this rather
  than federating, and what would trigger revisiting it.
- **`wbsearchentities` does not return P31**, so search results cannot be filtered
  by kind without one extra round trip per hit. `EntityResolver.search` therefore
  accepts a `kind` argument and deliberately does NOT apply it — a filter that
  cannot see the kind returns an empty list, which reads as "no such entity" rather
  than "cannot filter". ADR 26's `search_entities(query, kind?)` MCP tool inherits
  this, and its tool description must say so.
- **The live smoke test caught a wrong QID on its first run** — the plan used
  Q214601 for Nick Cave, which is actually David Tennant (Nick Cave is Q192668).
  The lesson is not just the fact: every fixture-backed test would have carried
  that error forever, because the fixture asserts whatever its author wrote. Run
  `./gradlew liveTest` deliberately when touching ingest.
- **stdout is the MCP protocol channel on the stdio transport, and nothing else.**
  All logging goes to stderr. This is enforced twice: an ArchUnit rule
  (`nothingWritesToStandardOut`) forbids `System.out` anywhere in `src/main`, AND
  `StdioPurityTest` launches the built application as a real subprocess and
  asserts every line it writes to stdout parses as JSON. The two are not
  redundant — the ArchUnit rule cannot see into a misbehaving dependency or into
  the framework's own startup output (proved by temporarily flipping
  `spring.main.banner-mode` to `console` under the `stdio` profile: the ArchUnit
  suite stayed green while `StdioPurityTest` went red on the banner's first
  line). Only running the process for real catches that class of failure.
- **The MCP protocol revision is pinned to 2025-11-25**, not the current
  2026-07-28, because that is what Spring AI's MCP Java SDK actually speaks
  (ADR 27). Migrating is a tracked follow-up blocked on the Java SDK, not an
  oversight — don't "fix" the pinned version without checking the SDK first.
- **`search_entities`'s `kind` argument does not filter anything.** Wikidata's
  search endpoint cannot report an entity's kind at search time, so `kind` is
  accepted but ignored and the tool description says so explicitly — a model
  reading only the schema, not the description, would otherwise assume it works.
- **`logback-spring.xml` is Boot-only** — it is loaded by Spring Boot's
  `LoggingApplicationListener`, not by Logback's own classpath scanning (which
  only looks for `logback.xml` / `logback-test.xml`). A plain JUnit test with no
  Spring context never triggers that listener, so Logback silently falls back to
  its built-in default `ConsoleAppender`, which targets **stdout** — the exact
  channel this project cannot allow logging on. A logging-configuration test
  must be `@SpringBootTest`; a non-Spring test that asserts on log output is
  validating Logback's factory default, not this project. See
  `LoggingTargetsStderrTest` and its task-3 report for the two ways the naive
  version of that test lied.
- **Never put an `application.yaml` in `src/test/resources`.** Spring Boot
  resolves `classpath:/application.yaml` to the *first* match on the classpath,
  and test resources come first, so such a file does not override a key — it
  shadows the whole of `src/main/resources/application.yaml`. The suite spent
  increment 4a booting contexts that had never seen the real MCP server name,
  transport protocol or bind address, which is the opposite of what an
  integration test is for. Test-wide property overrides go in `tasks.test`'s
  `systemProperty` (system properties outrank config data, and override exactly
  the key they name); per-test ones go in `@DynamicPropertySource`, which
  outranks both.
- **Spring AI 2.0.1's Streamable HTTP auto-configuration cannot start on its
  own.** `webMvcStreamableServerTransportProvider` takes
  `McpServerStreamableHttpProperties`, and nothing registers that class —
  `McpServerAutoConfiguration` enables only `McpServerProperties` and
  `McpServerChangeNotificationProperties`. `SegueConfiguration` enables it.
  Re-check on the next Spring AI bump. In the same area: the starter's
  *effective* default protocol is the deprecated `SSE`, not `streamable`, no
  matter what the property metadata claims — so `spring.ai.mcp.server.protocol`
  is set explicitly (ADR 37).

## Known open issues

- ~~Shortest path is the wrong default ranking.~~ **Fixed (ADR 31, increment 1.)**
  `GraphStore.paths(from, to, maxHops)` returns every route untruncated and the shared
  `PathRanking` orders them weakest-confidence-first (hop count as tiebreak) above the
  port, so the trustworthy Cave→McCarthy route now outranks the model's 0.30 shortcut.
- Validity conflict resolution is first-writer-wins in both adapters. Deliberately
  deferred, not solved.
- Provenance in the Gremlin adapter is packed into an opaque edge property
  (`ProvenanceCodec`), so Q2 and Q4 are full edge scans. Fine at personal scale;
  the alternative is reifying every relationship as a Claim vertex, which makes
  paths much worse.

## Next steps

The authoritative plan is `docs/design/2026-08-24-slice-1-2-design.md` and the ADRs it
cites; the increments are GitHub issues. What follows is orientation, not specification —
where it disagrees with an ADR, the ADR wins.

### Slice 1 — SourceAdapter SPI + Wikidata ingest (landed)

**Two** SPIs, not one — see `docs/adr/0025-source-adapter-spi.md`. Resolution and
expansion are different capabilities with different implementors: a similarity source
expands but has nothing to resolve.

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

Wikidata first, deliberately — no API key, cross-domain by construction, and it
supplies both the QID identity spine and the edge vocabulary. Uses
`wbsearchentities` for resolution and `wbgetentities` for claims, maps a
whitelist of properties to edge types, and turns qualifiers P580/P582 into
`validFrom`/`validTo`. Backlink discovery landed with ADR 36: one SPARQL query
per expansion to the Query Service, covering the whole vocabulary at once and
returning each neighbour's label and P31 inline.

Design rule: adding a source must not require touching the graph layer.

Retiring the placeholder QIDs in `Fixture` was deliberately left undone — it
touches every test's expected counts and deserves its own change. The
neighbour-QID fan-out is now mostly moot for Wikidata: the reverse lookup already
returns identity for the entities it discovers, so `expandEntity` fetches only
the ones a source could not describe. A bounded virtual-thread fan-out for that
remainder is still unbuilt, and is a smaller job than it was.

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
