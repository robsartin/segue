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
graph-database question — is complete, and so are the increments built on it:
Wikidata ingest, the MCP server on both transports, and (increment 5) the taste
layer. Remaining work is tracked as GitHub issues; recommendations are the
notable thing NOT built, because they need routes to filter and issue #32 is why
there currently are none.

## Build and run

```bash
./gradlew check           # format, tests, coverage, arch rules — the full CI gate
./gradlew test            # tests only
./gradlew spotlessApply   # fix formatting
./gradlew liveTest        # tagged live tests against the real Wikidata API; excluded from check
./gradlew resolveNames --args="--list $HOME/names.csv"   # bulk name→QID (ADR 40); needs network
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
port/     GraphStore, AssertionLog, AffinityStore, SourceAdapter, EntityResolver
          — the seams.
tinker/   Gremlin adapter (the chosen one).
jena/     RDF adapter (reference implementation, keep it working).
sqlite/   SqliteAssertionLog — the append-only log persisted to one file (ADR 24)
          — and SqliteAffinityStore, the taste layer's own table in that same
          file (ADR 33, ADR 39).
wikidata/ The first source: resolution and expansion. Plain Java, no Spring.
seed/     The bulk seeding tool (ADR 40): a name list to name→QID, run as
          `./gradlew resolveNames`. Dev-side, plain Java, resolves and reports —
          it never opens a store and is deliberately NOT an MCP tool.
ingest/   IngestService (the only write path) and GraphProjector (boot replay).
support/  Plain-Java cross-cutting helpers with no project dependencies of their
          own — currently UuidV7, the RFC 9562 v7 id generator used for request
          correlation.
mcp/      The six MCP tools (EntityTools, GraphTools, TasteTools), SegueService
          (the facade they call), CorrelationId. Spring-only package (ADR 32) —
          annotated with the starter's @McpTool, but plain enough to unit test.
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
- **Affinity is not an assertion, and the two layers never meet below
  `SegueService`.** A rating lives in the `affinity` table behind `AffinityStore`,
  carries no `Provenance` and no corroboration, and never reaches the graph or the
  log. `note_affinity` is the only writer; `IngestService` never sees a rating.
  Two ArchUnit rules enforce it in both directions
  (`affinityNeverTouchesTheWorldFactLayer`,
  `theWorldFactLayerNeverTouchesAffinity`). ADR 33 and ADR 39.
- **A rating is a required integer 1-5; the note is optional; re-rating
  overwrites.** Negative affinity is 1-2, not a separate concept. One row per
  entity keyed by qid, with `updated_at` — there is no history table, and taste
  drift is deliberately not retained (ADR 39).

## Gotchas already paid for
- **Never put real affinity data in the repository.** ADR 33 makes taste personal data, and this
  repo is PUBLIC — the protection is that the data lives at `${user.home}/.segue/` and never
  enters git, not that the repo is closed (issue #37 corrected an ADR bullet claiming otherwise).
  Ratings and notes in test fixtures, ADR examples, CLAUDE.md snippets and commit messages must be
  invented, not Rob's. `*.db` is gitignored; that covers the file, not a quoted example.

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
- **The vocabulary modelled only collaboration, and that broke literature.** Every
  Wikidata-backed edge type was people working *together* — co-credits on one work,
  membership of one group — which models film and music well and single-authored novels
  not at all. Three SF novelists added and expanded shared no node and `find_paths`
  returned zero routes for every pair (issue #32). **Fixed (ADR 38) by registering exactly
  one property: `RECEIVED_AWARD` (P166), DIRECT because Wikidata states it on the
  recipient.** The three pairs now route through the Hugo, Nebula, Locus, Seiun and Bob
  Morane; a comedian who shares no award still connects to nobody.
  **Do not "complete" this with genre, occupation, movement or record label.** They were
  measured, not guessed: P106 → "novelist" is a **35,977**-item node, P136 → "science
  fiction" **16,552**, the biggest P264 label **11,350**, against **127** for P166 → "Hugo
  Award for Best Novel". A hub edge is perfectly confident, and
  `DESC(?sitelinks)` truncation would keep it over the specific edges. ADR 38 records five
  questions it deliberately leaves **OPEN** — the general hub-degree rule, whether shared-kind
  is an edge or a node attribute, the ADR 31 specificity dimension, the truncation conflict,
  and the roles-as-edges invariant. **Question 3 is now answered (issue #52, ADR 31's
  amendment); the other four are not.** Adding a property is answering question 1; do it in an
  ADR, not in passing.
- **A forward-heavy property spends the `maxNewEdges` bound before the reverse pass sees
  it.** ADR 36 concatenates forward claims first (they carry references and qualifiers;
  truthy triples do not), so a novelist's dozen P166 awards are kept ahead of every
  discovered work. Measured on William Gibson: at `maxNewEdges=15` the expansion is 12
  award edges and 2 others; at the default 200 it is 12 of 118. Not the `DESC(?sitelinks)`
  problem — awards never appear in a person's reverse answer at all — and deliberately not
  reworked. See the issue-#32 note in ADR 36.
- **`query.wikidata.org` holds only the main graph.** Scholarly articles live on
  `query-scholarly.wikidata.org`, so `AUTHORED` (P50) silently under-reports for an
  academic seed: Einstein returns 32 on the main endpoint against 117 in reality.
  No error, just a smaller number. ADR 36 records why segue documents this rather
  than federating, and what would trigger revisiting it.
- **`wbsearchentities` does not return P31**, so search results cannot be filtered
  by kind without one extra round trip per hit. `EntityResolver.search` therefore
  accepts a `kind` argument and deliberately does NOT apply it — a filter that
  cannot see the kind returns an empty list, which reads as "no such entity" rather
  than "cannot filter". ADR 26's `search_entities` MCP tool inherits
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
- **Affinity is personal data and never reaches a log line — including one
  nobody wrote.** ADR 30 puts a logger in every service class, so the reflex that
  makes the rest of `SegueService` good makes `noteAffinity` wrong; that method
  deliberately logs nothing at all, on the happy path and on all three refusals,
  and its error strings never echo the rating or the note back. Less obviously,
  **sqlite-jdbc logs every statement it executes through SLF4J at TRACE** — the
  SQL text, never the bound parameters. The affinity write is a `PreparedStatement`
  with `?` placeholders and must stay one: concatenating values into that SQL would
  put a rating and a note into a driver log line with no logging call in this
  repository at all. `AffinityIsNeverLoggedTest` asserts both halves — this
  project's loggers are silent, and no logger anywhere carries a value.
- **Wikidata moved many proper names to the `mul` language code**, so
  `wbgetentities&languages=en` returns an EMPTY labels object for exactly the
  best-documented entities — a famous novelist, a famous naturalist. Reading only
  `/labels/en` reported them as if Wikidata had never heard of them, which meant
  `fetch(qid)` returned empty and `add_entity` on such a person simply failed. Both
  callers now ask for `languages=en|mul` and `ClaimMapper.label` falls back. Found by
  bulk-seeding a real list (issue #49), not by any test — a fixture asserts whatever its
  author wrote, and every fixture here had an `en` label.
- **`KindMapper`'s whitelist did not cover how Wikidata says "band", and did not cover how it
  says "work" either.** Q215380 "musical group" is the one everybody assumes; acts typed as rock
  band, musical duo, a cappella group, orchestra, choir, string quartet, collective or group of
  humans all fell through to CONCEPT (issue #49). The same sweep run over works found the bigger
  hole: of the 1,416 CONCEPT nodes in a real graph that could ever be a route's INTERMEDIATE,
  **1,058 were works** — 667 of them "musical work/composition" alone, plus television series
  episodes, television specials, television films and short films (issue #52). Both sets were
  MEASURED against real data and added, which is the growth path the class's own note asks for.
  Add the next one the same way — from data, with the label AND description confirmed, never
  guessed. **Never add an award class.** ADR 38 puts awards in CONCEPT deliberately and ADR 31's
  specificity rule reads "high-degree CONCEPT" as "hub"; placing awards anywhere else turns that
  rule off without failing anything. `KindMapperTest` pins it.
- **The bulk seeding tool's input and output never enter this repository.** A list of who
  someone listens to, reads and watches IS the personal data ADR 33 governs, and this repo
  is PUBLIC. `*.csv` is gitignored beside `*.db`; every name in a test, fixture, doc or
  commit message is invented. ADR 40.
- **`P106` is read by the resolver and is still not an edge.** Issue #32 kept occupation
  out of the graph vocabulary because "novelist" is a 36k-degree hub. `seed` reads it to
  tell a musician from a minister — `P31` is Q5 for both — which creates no edge and does
  not reopen #32.

- **The taste layer's classes deliberately have no package of their own.**
  `AffinityRecord` sits in `domain`, `AffinityStore` in `port`,
  `SqliteAffinityStore` in `sqlite`, `TasteTools` in `mcp` — each where its
  layer's convention puts it. The ArchUnit rules therefore match on type name
  rather than on package, which is why they read differently from every other rule
  in `ArchitectureTest`. A fifth package for four classes would make the rule
  easier to write and the codebase harder to read.

## Known open issues

- ~~Shortest path is the wrong default ranking.~~ **Fixed (ADR 31, increment 1.)**
  `GraphStore.paths(from, to, maxHops)` returns every route untruncated and the shared
  `PathRanking` orders them weakest-confidence-first (hop count as tiebreak) above the
  port, so the trustworthy Cave→McCarthy route now outranks the model's 0.30 shortcut.
- ~~Career-recognition awards are hubs and dominate routes.~~ **Fixed (issue #52, ADR 31's
  specificity amendment.)** `PathRanking` now orders by hub-free-ness first and weakest
  confidence second: a route through a `CONCEPT` intermediate at or above
  `PathRanking.HUB_DEGREE` in-graph edges is demoted, because a Walk of Fame star is perfectly
  true and says nothing. Kind is what makes it safe — the busiest nodes are the expanded seeds
  themselves, and those are legitimate connectors. `SegueService` passes the degree lookup down
  as a `ToIntFunction<String>` so `domain` stays graph-free (ADR 18). Two things it does NOT do:
  it does not keep hub edges out of the graph, and it says nothing about a busy GROUP — the
  American Academy of Arts and Sciences connects 21 seeds through `MEMBER_OF` and is career
  recognition by another name. Left open on purpose.
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
    ExpandResult expand(NodeRecord seed, ExpandContext ctx);
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

- `search_entities(query, kind?, limit?)` → candidates with QIDs and disambiguation
- `add_entity(qid)` → upsert, returns id
- `expand_entity(qid, maxNewEdges?)` → runs adapters, returns new edges
- `get_entity(qid)` → node plus neighbours grouped by edge type, plus this
  entity's affinity if it has been rated (ADR 39: the taste layer's read path is
  here, and there is no seventh tool)
- `find_paths(fromQid, toQid, maxHops?)` → routes with per-hop citations
- `note_affinity(qid, rating, note?)` → taste layer, its own table

Hold back `assert_edge` (model-proposed hypotheses) until corroboration is
visibly working.

**Keep the taste layer separate from the world-facts layer.** "I like this" is a
claim about the user with its own dimensions (rating, first-heard-where, seen-live-when);
Wilco's lineup is a claim about the world. Separate tables so recommendations can
be re-derived by traversing the world graph filtered through affinity. Landed in
increment 5: `AffinityStore`, `SqliteAffinityStore`, `TasteTools`, and the
`affinity` field on `get_entity`. Recommendations themselves are NOT built —
they need routes to filter, and issue #32 is why twelve dogfooding pairs
currently return none.

### The open risk

Whether MCP is a pleasant *authoring* interface or whether a UI is wanted within
ten minutes. Conversational bulk seeding may be too slow. Slice 2 is designed to
find that out cheaply — better to learn it in three days than three months.
