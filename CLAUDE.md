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
layer. Remaining work is tracked as GitHub issues. Recommendations — long the
notable thing NOT built — landed with ADR 45, as the fifth dev-side tool rather
than a seventh MCP tool.

## Build and run

```bash
./gradlew check           # format, tests, coverage, arch rules — the full CI gate
./gradlew test            # tests only
./gradlew spotlessApply   # fix formatting
./gradlew liveTest        # tagged live tests against the real Wikidata API; excluded from check
./gradlew resolveNames --args="--list $HOME/names.csv"   # bulk name→QID (ADR 40); needs network
./gradlew exportGraph --args="--view neighbourhood --qid Q42 --out $HOME/one.graphml"  # ADR 41; read-only; the --out extension picks the format
./gradlew listRatings --args="--sort recent --out $HOME/ratings.txt"   # ADR 43; read-only; the OUTPUT IS PERSONAL DATA
./gradlew retractEntity --args="--qid Q12345 --reason 'wrong entity' --dry-run"   # ADR 44; appends a retraction; --dry-run reports and writes nothing
./gradlew recommend --args="--known $HOME/known.csv --out $HOME/next.txt"   # ADR 45; read-only; ranks what you do NOT have, with routes; the OUTPUT IS PERSONAL DATA
./gradlew rate --args="--known $HOME/known.csv"   # ADR 46; loopback page at 127.0.0.1:8090; WRITES ratings only, no un-rate
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
export/   The graph exporter (ADR 41): four bounded views — route,
          neighbourhood, subgraph, full — to DOT or GraphML, run as
          `./gradlew exportGraph`. Dev-side, plain Java, READ-ONLY, and NOT an
          MCP tool. Selection (ViewSelector) knows no format; writers know no
          graph. A sibling of seed rather than part of it, because seed may not
          open a store and this reads one.
ratings/  The taste-layer reader (ADR 43): every rating with its label, note and
          updated_at, sortable by rating or recency, run as `./gradlew
          listRatings`. Dev-side, plain Java, READ-ONLY, offline, and NOT a
          seventh MCP tool. Its output IS personal data; *.txt is gitignored.
          The tightest fence of the six dev tools — sqlite only, no engine, no
          projection, no network.
retract/  The retraction tool (ADR 44): appends one Retraction claim so the
          projection stops showing an entity and its edges, run as `./gradlew
          retractEntity`. Dev-side, plain Java, offline, and NOT a seventh MCP
          tool. The only dev tool that writes a WORLD-FACT claim — exactly one
          kind of row, through IngestService, holding no GraphStore at all. (ADR
          46's `rate` is the other dev tool that writes, but only ever to the
          taste layer, through AffinityStore — never through IngestService.)
recommend/ The recommender (ADR 45): ranks entities ABSENT from a supplied known-list by
          candidate-degree-normalised lift, excludes hub intermediates through PathRanking.isHub,
          weights edge types, and explains every candidate with real find_paths routes. Run as
          `./gradlew recommend`. Dev-side, plain Java, READ-ONLY, offline, NOT a seventh MCP tool.
          Since issue #85 it WEIGHTS by rating — Recommendations.regardFor over the note-free
          AffinityStore.readRatings — under a fence written at the CALL SITES:
          theRecommenderReadsRatingsAndNeverNotes bans find and readAll and the AffinityRecord
          type, while still letting it read scores.
rate/     The rating deck (ADR 46): a loopback page on 127.0.0.1:8090 that deals one
          entity per keystroke — known entities by degree, a recommend candidate every fifth
          card — `1`-`5` rates and advances, `s`/space skips, `b` goes back. Run as `./gradlew
          rate`. Dev-side, NOT a seventh MCP tool, and — with retract — one of only two dev
          tools that WRITE: AffinityStore.put alone, fenced by four ArchUnit rules, one of
          which (theRatingDeckLogsNoRating) names a single exception — the class that
          constructs what it writes. No un-rate: AffinityStore has no delete, so going back
          re-rates rather than withdrawing.
ingest/   IngestService (the only write path) and GraphProjector (boot replay).
support/  Plain-Java cross-cutting helpers with no project dependencies of their
          own — UuidV7, the RFC 9562 v7 id generator used for request correlation;
          QidList, the QID-file reader `export`, `recommend` and `rate` share (it moved here
          from `export` in ADR 45, so a shared reader would not force a dependency between
          siblings that must not have one — `rate` depending on `recommend` directly, for its
          candidate sweep, is the one dev-tool pair that already does, by design); and
          ClassLabels, the offline P31 label table `export` and `rate` share, which moved here
          from `export` in ADR 46 for the same reason QidList did.
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
- **Retraction is a new claim, never a deletion.** A `Retraction` row is appended
  and the log is never edited; both projections omit what it retracts. The unit is
  the ENTITY (its node claims and every edge touching it), it reaches backwards
  only by log position, and it carries no `Provenance` — it is a first-person act
  like affinity, so it holds a reason and a `retractedAt` and nothing else. There
  is no un-retract: re-add the entity and the newer claims stand. ADR 44.
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
- **The score is ordinary data; the note is not** (issue #85, amending ADR 33).
  A model may read a rating, weight recommendations by it and discuss it — it is
  the known-list at higher resolution, and that list is already handed over. A
  note is free text nothing constrains, so it never appears in an MCP tool result:
  `AffinityView` has no note field, `note_affinity` does not echo the words it was
  given, and `./gradlew listRatings` is the only reader. Three ArchUnit rules hold
  it (`onlyTheRatingsToolReadsANote`, `theRecommenderReadsRatingsAndNeverNotes`,
  `onlyTheRecommenderReadsEveryRating`) plus `NoteNeverLeavesThroughAToolTest`,
  which discovers every `@McpTool` method by classpath scan rather than naming
  them. **Adding a taste dimension now means deciding which side of that line it
  falls on before adding it.**

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
- **Awards did not fix a technical bookshelf, and `ABOUT` (P921) is the second — and so far
  last — property admitted ADR 38's way.** Clean Code, SICP, The Pragmatic Programmer and
  Design Patterns share no author and state no P166 at all, so ADR 38's repair reaches none of
  them. **P921 is DIRECT, not `inverted` — issue #111 was filed saying `inverted` and that was
  wrong** (its body has since been corrected): `inverted` means the STORED direction reverses the
  STATED one (P50: Wikidata says
  `book P50 person`, segue stores `person AUTHORED book`), and P921 states `book P921 subject`
  which is exactly what segue stores. Not `fallbackOnly` either: P921 states no P1696, and its
  P7087 inverse *label item* Q70782961 is not a registrable property. **The admission turned on
  one number: science fiction is 16,552 as a genre (P136) and 228 as a main subject (P921)** —
  the same concept 72× smaller, because P136 is what a work is *in* and P921 only what it is
  *about*. Retargeted at `Q80006` computer programming and `Q80993` software engineering, which
  is what the books actually state — **not** the subjects a person would name; Tanenbaum's
  Computer Networks carries no P921 and Effective Java is not in Wikidata, so **the shelf is
  not covered and the ADR says so**. Weighed as a fourth tier, `ABOUTNESS = 0.1`, below
  `RECOGNITION`. **Never expand a subject node** — one `expand_entity` on a broad CONCEPT pulls
  up to 500 edges; nothing in the code stops it yet, that is issue #112. ADR 47.
- **A forward-heavy property spends the `maxNewEdges` bound before the reverse pass sees
  it.** ADR 36 concatenates forward claims first (they carry references and qualifiers;
  truthy triples do not), so a novelist's dozen P166 awards are kept ahead of every
  discovered work. Measured on William Gibson: at `maxNewEdges=15` the expansion is 12
  award edges and 2 others; at the default 200 it is 12 of 118. Not the `DESC(?sitelinks)`
  problem — awards never appear in a person's reverse answer at all — and deliberately not
  reworked. See the issue-#32 note in ADR 36.
- **Do not raise `maxNewEdges` to "complete" an act that reports `truncated`.** It was
  measured on three of them (issue #71) and completing an act does not improve its routes:
  the tail past the bound is the part of a catalogue that connects to nothing, because
  `DESC(?sitelinks)` already kept the connective neighbours. One of the three added several
  hundred edges and changed no route at all. The figures, the route comparisons and the three
  rejected mechanisms are in ADR 36's issue-#71 amendment.
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
- **A better `KindMapper` does not reach nodes the graph already has unless something re-records
  them.** `expandEntity` used to record a neighbour's identity only when the node was absent, so
  every node's kind was frozen at whatever the mapper said on the run that discovered it and the
  same class of entity ended up with two kinds depending on when it arrived — 73% of the CONCEPT
  nodes with degree ≥ 2 in a real graph were works or groups the mapper had since learned to
  classify, and ADR 31's hub rule was vetoing routes through them (issue #55). It now re-records
  identity an adapter supplied inline (`ExpandResult.neighbors`) whether or not the node exists;
  ADR 19 already says a changed belief is a new claim and `upsertNode` is last-writer-wins, so
  this was a bug against the ADRs rather than a new decision. Two limits that are the whole
  design: it never *fetches* identity for an existing neighbour (that would be hundreds of round
  trips per expansion), and it does not touch `nodesAdded` — a correction is not a discovery.
  **It is also not the same rule as `described.putIfAbsent`**, which resolves two sources
  disagreeing within one call and stays first-writer-wins. Same-source-across-runs and
  two-sources-within-one-call are different questions; don't unify them. **A mapper improvement
  now also reaches existing nodes at the next boot**, because the claim stores its P31 — see the
  next bullet; before ADR 42 it needed a re-seed.
- **A node claim stores the raw `P31` beside the kind derived from it, and both projections
  re-derive the kind from it, always.** `KindMapper.rederive` is the single rule;
  `GraphProjector` (boot replay) and `LogProjection` (the exporter's fold) both call it, so a
  graph and a picture of that graph cannot disagree about what a node is. **This is what makes a
  `KindMapper` improvement free**: it corrects every affected node at the next boot, offline,
  instead of costing a 17-minute re-seed (issues #55 and #60, ADR 42). A claim that states no
  classes keeps its recorded kind; a claim that states classes takes the mapper's answer *even
  when that answer is CONCEPT*, or the whitelist becomes a ratchet where additions propagate and
  corrections never do. The log is never rewritten — re-derivation is the projection's job.
  The list is one space-separated column (`instance_of`, beside `node_kind`) and one packed
  literal on a graph vertex; no escaping, because `NodeRecord` validates every value as a QID.
  It stays a **list** because it records what the source said and a source's answer has an
  order — but that order no longer decides anything; see the next bullet.
- **When an entity states classes belonging to more than one kind, a fixed PRECEDENCE picks the
  winner — never the order they arrived in.** `KindMapper` took the first class it recognised,
  and P31 order is noise: the entity JSON is oldest-statement-first and `ReverseClaims` collects
  the classes into a `LinkedHashSet` keyed on SPARQL row order. So a film that Wikidata ALSO
  (wrongly, unsourced, but really) calls a city was stored city-first and became a `PLACE` — and
  nothing flagged it, while every kind-keyed rule quietly misfired: ADR 31's hub demotion is
  `CONCEPT`-only, DOT colours by kind, `SourceAdapter.supports(kind)` gates expansion (issue
  #87). The ranking is **PERSON, WORK, GROUP, EVENT, PLACE, CONCEPT**, argued per rung in
  `KindMapper.PRECEDENCE` and pinned in both directions by `KindMapperTest`. **Do not resolve
  this with a `P279` subclass walk** — it is a network call and both projections re-derive kinds
  offline, and it could not settle "city vs film" anyway, since neither subclasses the other.
  Adding a `NodeKind` constant fails a static check until it is ranked.

- **ADR 42 shipped a schema change with no migration, and that was a one-off.** It was
  affordable only because every one of the 265,046 assertions was a regenerable Wikidata world
  fact and `affinity` had 0 rows. **A rating is first-person data with no external source**, so
  the next schema change needs a real migration path. Deleting the file also reset every
  `assertedAt` to the re-seed (ADR 20 treats assertion time as real); that was noise on a
  two-day-old graph and will not be next time.

- **The bulk seeding tool's input and output never enter this repository.** A list of who
  someone listens to, reads and watches IS the personal data ADR 33 governs, and this repo
  is PUBLIC. `*.csv` is gitignored beside `*.db`; every name in a test, fixture, doc or
  commit message is invented. ADR 40.
- **`P106` is read by the resolver and is still not an edge.** Issue #32 kept occupation
  out of the graph vocabulary because "novelist" is a 36k-degree hub. `seed` reads it to
  tell a musician from a minister — `P31` is Q5 for both — which creates no edge and does
  not reopen #32.

- **The exporter reads and cannot write, and that is an ArchUnit rule, not a promise.**
  `theExporterOnlyReads` forbids `export` from calling `GraphStore.record`, `GraphStore.upsertNode`
  or `AssertionLog.append` AND from depending on `IngestService` at all. The second half is the one
  no other rule covers: `onlyIngestAppliesClaimsToTheGraph` already blocks the three calls from
  everywhere outside `ingest`, but without the `IngestService` ban a class in `export` could route a
  claim through the one legitimate writer and break nothing. `GraphProjector` is deliberately NOT
  banned — the bounded views need a traversal, so the tool replays the log into a throwaway
  in-memory `TinkerGraphStore` exactly as the app does at boot; nothing durable changes. Also:
  `SqliteAssertionLog`'s constructor CREATES the file and schema if absent, so `ExportCli` checks
  `Files.exists` first rather than conjuring an empty database and exporting nothing.

- **The exporter's `--out` extension is an argument, not decoration.** `--format` used to default
  to GraphML with the file name never read, so `--out route.dot` wrote GraphML into `route.dot`,
  reported success, and failed minutes later inside Graphviz with `syntax error in line 1 near '>'`
  — the XML declaration (issue #57). `.dot`/`.gv` now mean DOT and `.graphml`/`.xml` mean GraphML,
  case-insensitively and from the file name only, whenever `--format` is absent; the residual
  default for an unrecognised extension is **DOT**, because it renders in one already-installed
  command where GraphML needs Gephi. A `--format` that CONTRADICTS the extension is **refused**,
  naming both — not resolved by precedence, because obeying the flag rewrites the misnamed file
  that caused the issue and obeying the extension overrules something typed on purpose, and nothing
  in the parser can tell which of the two is the typo. **Don't add a `--force`**; it exists only to
  recreate the failure. Adding a third format means adding its extensions to `OutputFormat` too.
  ADR 41's issue-#57 amendment.

- **`AffinityStore` has TWO bulk reads, and each belongs to exactly one package.** `readAll`
  returns whole rows, notes included, and is the `ratings` dev tool's alone
  (`onlyTheRatingsToolReadsEveryRating`); `readRatings` returns a note-free `Map<String, Integer>`
  and belongs to the two dev tools that weight and deal by it, `recommend` and — since ADR 46 —
  `rate` (`onlyTheRecommenderReadsEveryRating`, issues #85 and #101). The reason the
  second one is still fenced has CHANGED — a score is no longer too personal to leave, but ADR 26
  pins the surface at six tools and nothing on it needs the whole table.
  ADR 39 declined a bulk `list_affinity` MCP tool on ADR 16 data-minimisation grounds, and what it
  accidentally also did was lock out the OWNER, who cannot regenerate a rating from any source.
  ADR 43 changed the caller, not the surface: the port gained a bulk read, `./gradlew listRatings`
  uses it, and the rule fails the build if anything outside `..ratings..` calls it.
  Don't "expose it on `get_entity`" or add a field carrying it — `ToolSurfaceTest` counts TOOLS and
  would not notice a new field, which is exactly why the fence is at the call site. Also new there:
  `theRatingsToolOnlyReads` is the only rule in the project that forbids **`AffinityStore.put`**.

- **`AffinityOverlay` is fenced by a rule written years before it.**
  `affinityNeverTouchesTheWorldFactLayer` matches taste-layer types by NAME (simple name contains
  "Affinity"), not by package, so the exporter's affinity decorator is covered automatically. Don't
  rename it to dodge that; the fence is the right one.

- **`find_paths` capped at 50 routes and reported the capped count as the answer.** A pair with
  two hundred routes said "50 route(s)" with nothing marking the shortfall, so a model reading it
  would report fifty as the number of routes; it happened twice on the real graph before anyone
  noticed (issue #65). It now returns `partial` and names the true count. **The general rule is
  the one everything else here already followed**: a bound that can bite must be reported by the
  result that hit it, and it must be OBSERVED rather than assumed — `findPaths` compares the ranked
  size against the raw one, exactly as `ReverseClaims` fetches `maxNewEdges + 1`. The detail also
  says the survivors are the BEST routes, because ADR 31 ranks before the cap applies, and a
  truncated answer that kept the best is worth far more than one holding an arbitrary fifty. No
  ADR: ADR 27 already required this and ADR 31's cap is unchanged, so it was a bug against the
  decisions rather than a new one.

- **A retraction is honoured by the FOLD, never applied to a store.** `Retractions` in `domain`
  is the one rule, and `GraphProjector` (boot replay) and `LogProjection` (the exporter's fold)
  both call it — the same two-call-site shape ADR 42 gave `KindMapper.rederive`, and
  `BothFoldsAgreeTest` is what stops them drifting. `IngestService.apply` THROWS if handed a
  retraction: it is unreachable through either projection, and silently ignoring it would leave a
  graph holding edges somebody took back out. Consequences worth knowing: "replayed N assertions"
  is deliberately no longer the row count; a running server is stale until it restarts, because
  `GraphStore` has no remove and ADR 41 already refused to widen the port for a dev tool; and
  retraction does NOT cascade, so the neighbours a wrong expansion discovered stay as edgeless
  nodes. `Labels.forQids` (the ratings tool) deliberately does not apply the rule — see ADR 44's
  consequences.

- **ADR 44 is the migration ADR 42 promised.** `SqliteAssertionLog` adds a `reason` column with
  `ALTER TABLE ... ADD COLUMN`, guarded by `PRAGMA table_info` rather than a version table, and it
  was tested against a copy of the live 131,672-row database as well as from a hand-written old
  schema. `source_id` and `confidence` are `NOT NULL` and mean nothing for a `RETRACT` row, so they
  carry fixed padding (`(retraction)`, `1.0`) that `readRow` never reads back — relaxing a
  `NOT NULL` in SQLite means rebuilding the whole table.

- **A recommender that counts connections rediscovers fame, and the fix is which degree you divide
  by.** Measured on the real 123,752-node graph before anything was built (issue #82, ADR 45): raw
  counting and Adamic-Adar both returned the most famous entities in the graph, because a candidate
  connected to everything shares its intermediates with everything. **Dividing by the CANDIDATE's own
  degree** turns popularity into surprise, and it needs a **degree floor** (12, `--min-degree`)
  because a normalised score otherwise rewards whatever is thinnest — the experiment's cosine variant
  put a degree-2 node first. `--scorer` keeps raw/adamic-adar/resource-allocation/lift as a dial, and
  running two of them is how you see what the normalisation does. **Plain PageRank is the wrong
  tool** (it measures the fame this is escaping) and personalised PageRank is refused for a different
  reason: it cannot produce the routes, and a score with no receipts is not a segue recommendation.
- **Hub intermediates are EXCLUDED from recommendations, not demoted, and it is `PathRanking.isHub`
  — now public for exactly this.** Discounting let the Rock and Roll Hall of Fame decide the top of
  the experiment's ranking. Routing demotes a hub route because "what connects me to the hall of
  fame" has an answer; recommending excludes one because "you were both inducted" is not a
  recommendation. One implementation, two readings — **never write a second copy of that judgement**.
  116 intermediates were excluded on the real run.
- **Edge type carries more of the recommendation signal than any further tuning of the degree
  maths.** `RecommendationWeights`: influence 1.0, collaboration 0.5, recognition 0.2, one
  significant figure, and only the ORDER is measured. Halving collaboration is what dissolved the
  co-membership artefact (a band member reached through 28 songs by one group — one fact counted 28
  times); `RECEIVED_AWARD` is a fifth and deliberately **not zero**, because ADR 38 admitted P166
  precisely for single-authored work where there is no collaboration to find. Adding an `EdgeType`
  now fails `RecommendationWeightsTest.everyRegisteredTypeIsNamed` until it is weighed.
- **Direction is read on the candidate's own hop, and NOWHERE else** (issue #84, ADR 45's amendment).
  Undirected, a small band citing ten famous acts and an ancestor those acts cite are the same shape,
  and the small band wins on lift because its degree is smaller — it was rank 1. A hop the candidate
  is the SUBJECT of is now worth a fifth (`RecommendationWeights.asEvidenceAbout`, `SELF_STATED`):
  being cited is a fact somebody else stated, citing is a self-description. **Demoted, never
  excluded** — "who came from the things you like" is still a segue. **Do not extend it to the hop
  out of your own entities**: the bands that cite your list are the same bands that cite its
  ancestors, so that would demote exactly what this keeps (`CandidateSweepTest
  .directionIsAskedOnlyOfTheCandidatesOwnHop`). Only `INFLUENCED_BY` and `BASED_ON` carry a direction
  of esteem; every other type's direction says which end is the person, the work or the prize. The
  ADR has the per-type table and the before/after measurement — top 25 items citing more than they
  are cited went 18 → 2, and the all-inbound ancestors scored identically.
- **The recommender reads ratings and cannot read a note.** That was the one fence no sibling tool
  had until ADR 46: `rate` now reads the same `readRatings` and is held off the note by
  `theRatingDeckNeverReadsANote`. What is still particular to `recommend` is the SHAPE — `find`
  banned and `AffinityRecord` unnameable, where `rate` has no `find` ban and lets `RateServer` name
  the record it constructs. It
  used to be banned from `AffinityStore` as a type; issue #85 narrowed that to
  `theRecommenderReadsRatingsAndNeverNotes` — `AffinityRecord` banned as a type, `find` and
  `readAll` banned as calls, `readRatings` (a `Map<String, Integer>`) allowed. The old argument is
  intact where it still bites: `find` is available everywhere else, and eight hundred single-qid
  lookups are a bulk read spelled slowly. **`RecommendCli` is the only class in the package that
  touches the store**; everything below it takes regard as a function.
- **The affinity weighting is centred on 3, not proportional to the rating.** `regardFor` gives a 5
  weight 5/3, a 1 weight 1/3, and an UNRATED entity weight 1.0 — because most of the known-list is
  unrated (it comes from ADR 40's file, not the taste layer), and a proportional weighting would
  bury everything unrated the moment the first rating was written. An empty table therefore
  reproduces ADR 45's measured ranking exactly. **None of this has met a real rating**: the
  `affinity` table has zero rows, and `AffinityWeightedRecommendationTest` demonstrates the
  movement on invented ratings in a scratch database. Whether 5/3 is the right strength is
  unmeasured — settle it the way the degree floor was settled, by running two and reading both.

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
  as a `ToIntFunction<String>` so `domain` stays graph-free (ADR 18). It does NOT keep hub edges
  out of the graph — a hub route is still returned, merely last.
- ~~A busy GROUP is a hub too and nothing demotes it.~~ **Fixed (issue #66, ADR 31's second
  amendment.)** An intermediate that STATES a recognition class — `Q955824` learned society,
  `Q414147` academy of sciences, `Q178790` labor union — is a hub whatever its degree and whatever
  kind it was mapped to, so "we were both elected to this" no longer outranks "we made this
  together". **Neither degree nor edge type could have done it**, and both were measured on the
  real graph before the class was chosen: the institutions carry 6-33 edges and the bands that must
  keep working carry 11-19 (the Writers Guild of America West and Mötley Crüe both carry exactly
  11), and `MEMBER_OF` reaches the Traveling Wilburys, the Eagles and Monty Python as readily as it
  reaches an academy. **Never add a broad organization class to `RecognitionInstitutions`** —
  `Q163740` nonprofit and `Q43229` organization are worn by every institution AND by ABBA and the
  Vienna Philharmonic. The table lives in `wikidata` beside `KindMapper` and reaches `domain` as a
  `Predicate<String>`, the same way the degree does; there is deliberately no degrees-only overload,
  because half the rule silently prefers the academy.
- ~~One general hub measure should replace those two special cases.~~ **Looked for, measured, and
  refused (issue #88, ADR 31's fourth amendment.)** Seven measures computed from the graph — degree
  and every monotone function of it, degree percentile within kind, dominant edge-type share,
  clustering coefficient, neighbourhood kind mix, degree over neighbour degree — were run against a
  38-node gold set on a copy of the real graph, and **every one overlaps**. The reason is one
  sentence: *a film and an award are the same shape*. `The Great Buck Howard` has five edges, one
  edge type, all-person neighbours and no triangles; `Disney Legends` has twenty-two, one edge type,
  all-person neighbours and no triangles. Only what the node IS separates them, which is the kind in
  the first rule and the stated class in the second. **The recommender's candidate-degree
  normalisation cannot be borrowed** — ranking compares routes between one FIXED pair, so any score
  normalised by the endpoints orders identically to the unnormalised one; it is provably inert, not
  weakly useful. The best candidate (`kind != WORK`, degree ≥ `HUB_DEGREE`, ≥ 90% person-or-group
  neighbours) demotes **Metallica and Immanuel Kant** and loses the 21 institutions below
  `HUB_DEGREE`, so it is worse on both rules' own acceptance cases. **Do not re-open this with a
  measure that was only run against the hubs** — that candidate passes any such test.
- **The class table is the mechanism, so it goes stale, and it did.** Between #66 and #88 the graph
  went 54,448 → 123,752 nodes and grew institutions that NEITHER rule could see — a hall of fame at
  500 edges that ranking actively preferred, because every competing academy route was marked a hub
  and it was not. Four classes added (hall of fame, professional association, scientific society,
  writers union) with the counts in ADR 31. Two traps recorded there and fenced by
  `RecognitionInstitutionsTest`: **an award class must never go in** (`Q618779`, `Q11448906` — ADR
  38 registered P166 so a novel could route through its prize), and **fit the meaning, not the
  population** — every node stating `Q45400320` in the real graph is an academy and the class means
  *open-access publisher*. **Hub demotion is partial by construction**: the American Association for
  the Advancement of Science and the Polish Academy of Learning both carry 500 edges and state
  nothing but publisher and organization classes, so nothing safe reaches them.
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
  entity's RATING if it has been rated (ADR 39: the taste layer's read path is
  here, and there is no seventh tool; issue #85 removed the note from it)
- `find_paths(fromQid, toQid, maxHops?)` → routes with per-hop citations
- `note_affinity(qid, rating, note?)` → taste layer, its own table

Hold back `assert_edge` (model-proposed hypotheses) until corroboration is
visibly working.

**Keep the taste layer separate from the world-facts layer.** "I like this" is a
claim about the user with its own dimensions (rating, first-heard-where, seen-live-when);
Wilco's lineup is a claim about the world. Separate tables so recommendations can
be re-derived by traversing the world graph filtered through affinity. Landed in
increment 5: `AffinityStore`, `SqliteAffinityStore`, `TasteTools`, and the
`affinity` field on `get_entity`. Recommendations landed later, in `recommend/`
(ADR 45), and the affinity half of that sentence landed with issue #85:
`Recommendations.regardFor` weights each known entity by its rating, so a
candidate reached by three things rated 5 outranks one reached by six rated 2.
The table still holds zero rows, so that is demonstrated on invented ratings and
has never met a real one.

### The open risk

Whether MCP is a pleasant *authoring* interface or whether a UI is wanted within
ten minutes. Conversational bulk seeding may be too slow. Slice 2 is designed to
find that out cheaply — better to learn it in three days than three months.
