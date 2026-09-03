# The MusicBrainz probe, committed, so ADR 55's magnitudes can be re-derived

Issue #167. Written 2026-09-02.

## The gap

ADR 55 declines two changes — `subgroup` maps to nothing (#142), the adapter returns no
`neighbors()` (#143) — and argues that a decline carries a higher burden than a fix "because nothing
downstream will ever exercise it". It then records, in its own last bullet, that the instrument
behind every magnitude in it "was a scratch `liveTest`, left nothing behind".

**It did.** Measured on 2026-09-02, before anything was written:

- `git rev-list --all --objects` over all 434 commits returns **no** path matching
  `probe|census|subgroup|tally` with a `.java`, `.md`, `.txt` or `.csv` extension.
- The branch that produced ADR 55, `142-143-musicbrainz`, has five reflog entries
  (`887b65b` created from main, then `2d7ae7a`, `adc353a`, `724c092`, `389b2eb`, then a merge).
  None adds a probe file. Its tree holds the same five `*LiveTest.java` files `main` holds.
- `gh gist list` is empty. `git stash list` is empty. The four unreachable commits in the repository
  are a trial merge, a `91-musicbrainz-adapter` WIP stash, and two CI commits; none carries a probe.
- `gh api repos/robsartin/segue/pulls/159/{comments,reviews}` and
  `gh api repos/robsartin/segue/issues/159/comments` are all empty, and issue #167 has no comments.
  The review that raised #167 was not posted to GitHub.

So two texts survive and no code does: **ADR 55's two tables**, and **PR #159's description**, which
carries the same figures in the same shape. Both are attestations. Neither regenerates.

The falsifications ADR 55 turns on are reproducible from the committed tree — 136 `MEMBER_OF` and 34
`PART_OF` group-to-group assertions are a query over any copy of the log, and the erasure is pinned
by `MusicBrainzNeighbourIdentityTest`. What cannot be re-derived is every magnitude that made the
declines *proportionate*: 2 of 959, 828, 200 seeds, 120 bridged, 461 → 203/44/214, median 1 / p90 4 /
max 54, 58 occurrences over 57 distinct nodes, 2 of 90.

## What the tree says, measured on 2026-09-02

**Every input the probe needs is already shipped and already reachable from a test.**

- **Seeds.** `sqlite.SqliteAssertionLog(Path dbFile)` takes an explicit path and `readAll()` returns
  `List<LoggedAssertion>`. `NodeAssertion(qid, kind, label, instanceOf, provenance)` carries the
  `node_kind` ADR 55 says it sampled on — "the `node_kind` on the latest node claim in that log, not
  the kind `GraphProjector` re-derives at projection time".
- **Presence in the graph.** `ingest.GraphProjector.project(AssertionLog, GraphStore, IdentityMerge)`
  replays a log into a store, honouring `Retractions.survives`; `RecommendCli` already does exactly
  this with `IdentityMerge.NONE` over a real six-figure log. `graph.node(qid).isEmpty()` is
  `SegueService`'s own definition of `isNew`, verbatim.
- **The bridge.** `app.WikidataMusicBrainzIdentity` implements `musicbrainz.MusicBrainzIdentity`
  through `P434`, batched via `qidsFor(Collection<String>)`, with `mbidFor(String)` for the seed.
- **Expansion.** `MusicBrainzClient.artistRelations(String mbid)` returns **every** artist-target
  relation, whitelisted or not — the census is a frequency count over `ArtistRelation::type` on that
  return value, and needs no adapter. `MusicBrainzSourceAdapter.expand` and `WikidataSourceAdapter`
  supply the assertions and the `neighbors()` the `isNew` breakdown partitions.
- **The three buckets are `SegueService.expandEntity`'s own branches**, lines 252–312: `described`
  is a `Map<String, NodeAssertion>` filled `putIfAbsent` from every adapter's `neighbors()`;
  `isNew` is `graph.node(neighbor).isEmpty()`; the fetch fires only when `isNew && described` misses.
  The probe re-walks those branches rather than modelling them.
- **Offline instruments exist.** `musicbrainz.StubMusicBrainzServer` (package-private, JDK
  `HttpServer`, queue-per-request), `musicbrainz.StubIdentity` (a fixed MBID↔QID map), and
  `MusicBrainzClient.readingFrom(Path)` and `new MusicBrainzClient(URI)`, both public.
- **`liveTest` needs no build change to include a new `@Tag("live")` class** — it is
  `includeTags("live")` over the whole test source set, never up-to-date. It does **not** set
  `--enable-native-access=ALL-UNNAMED`, which `tasks.test` and every SQLite-touching `JavaExec` task
  do. A probe that opens SQLite under `liveTest` would meet the restricted-method warning.
- **`PackageListsTest` parses `build.gradle.kts` for `tasks.register<JavaExec>("x") {` only**, and
  asserts every line mentioning `JavaExec` is one of those. A new `Test` task is invisible to it.
  `build.gradle.kts` is a declared input to `test`, so registering one re-runs the suite.
- **`DocumentationLinksTest` checks every relative link in `docs/**/*.md`**, this file included. The
  house style in `docs/superpowers/` is backticked paths, not markdown links; this spec keeps it.
- **`*.csv` and `*.txt` are gitignored**, with `*.db`. A committed fixture may not use those
  extensions, and may not be a database.

**One discrepancy found, reported rather than documented away.** ADR 55's last-but-one consequence
calls the whitelist "a two-entry `Map.of`". `MusicBrainzSourceAdapter`'s javadoc on the same field
says "The whitelist. One entry", and the code is `Map.of(MEMBER_OF_BAND, EdgeTypes.MEMBER_OF)` — one
mapping, two arguments. The reading that makes ADR 55 true is "a `Map.of` with two arguments", which
is not what "two-entry" means. This changes no decision and is not this issue's to fix; it is
recorded here and in the report.

## The decision

**Commit the probe as a test class with two runs over one engine: a live run tagged `live`, and a
fixture run inside `./gradlew check`.** The engine is shared; only its inputs differ.

### 1. The table shape, fixed here, because the definition of done is a shape

One task prints five blocks, in this order, mirroring ADR 55:

| block | columns | rows |
| --- | --- | --- |
| **1. Sample** | `what \| count` | seeds requested, seeds `PERSON`, seeds `GROUP`, bridged via `P434`, artist relations returned, seeds with a resolved neighbour, resolved neighbours |
| **2. Census** | `relation type \| count` | one per distinct relation type, descending by count then by type, then `TOTAL` |
| **3. Neighbours** | `what the neighbour was \| count \| share \| fetch spent today?` | `already in the graph` / `new, but described in the same call` / `new and undescribed`, then `TOTAL` |
| **4. The saving per expansion** | `median \| p90 \| max` | one row |
| **5. What filling `neighbors()` would cost** | `what \| count` | new neighbours created class-less, erasure occurrences, distinct nodes erased, of those carrying a non-empty `instanceOf` today, seeds the shared bound cut |

Block 2 is ADR 55's 16-row census; block 3 is its three-row `isNew` table with its fourth column
kept verbatim; block 4 is "median of 1 fetch, p90 of 4, max of 54"; block 5 is the 214 / 58 / 57 /
57 / 2-of-90 sentences that ADR 55 states in prose and never tabulates — tabulated here because a
sentence is not re-derivable.

### 2. What it asserts: structure, never a value

The numbers move as the graph grows, so **every assertion is an invariant over the printed table**,
and the same checker runs in both runs:

- Block 2's counts sum to block 1's relation total; no relation type appears twice; every count ≥ 1.
- Block 3's three buckets **partition** block 1's resolved-neighbour total, and its shares sum to
  100% within one percentage point of rounding.
- Block 1: `bridged ≤ seeds`; `seeds PERSON + seeds GROUP = seeds requested`;
  `seeds with a resolved neighbour ≤ bridged`.
- Block 4: `median ≤ p90 ≤ max`, and `max` equals the largest per-seed count of block 3's third
  bucket — the two are the same quantity read two ways, so a disagreement is an arithmetic bug.
- Block 5: `distinct erased ≤ erasure occurrences`; `non-empty instanceOf ≤ distinct erased`;
  `seeds the bound cut ≤ seeds with a resolved neighbour`; `class-less creations` equals block 3's
  third bucket.
- **Privacy, asserted rather than reviewed.** ADR 51 says no test can enforce its rule in general,
  and that is true of an ADR's prose. It is *not* true of one program's own output: the emitted text
  must match no QID (`Q\d+`), no MBID (the UUID shape), and must consist only of the block headings,
  MusicBrainz relation-type vocabulary, integers and percentages. ADR 33's "aggregates only" becomes
  a checkable property of this instrument, which is narrower than ADR 51's rule and does not weaken it.

### 3. The database is a parameter, and the real one is refused

The probe never has a default. `ProbeDatabase.require(String property)` resolves
`-Dsegue.probe.db=<path>` and **refuses** — with a sentence naming the property and the copy step —
in four cases:

1. the property is absent or blank;
2. the path does not already exist (`SqliteAssertionLog`'s constructor calls
   `createParentDirectories` and writes the schema, so a typo would otherwise create an empty
   database and the probe would report a table of zeros — the failure this repository keeps finding,
   where a dead instrument and an empty result look identical);
3. the path resolves — `toRealPath`, so a symlink cannot dodge it — to `support.DefaultDatabase`'s
   default, or to anything under `${user.home}/.segue`;
4. `SEGUE_DB` is set and the property is not: named and refused in the same breath, ADR 60's clause,
   for ADR 60's reason — an agent's shell inherits the owner's environment and a flag typed per
   invocation is the act that distinguishes them.

This is not `RequiredDatabase` reused: that class is fenced to the two claim CLIs and returns a
refusal sentence for a CLI's usage output. This is a test-source class with a different trigger. It
cites ADR 60 and holds no copy of `DefaultDatabase`'s rule — it *calls* `DefaultDatabase.resolve` to
know what to refuse.

**The live run is `@Tag("live")` and fails rather than skips when the property is missing.** A skip
reports success for a run that never happened, which is the same defect as an empty table.

### 4. The fixture run proves the pipeline offline, with a positive control

`./gradlew check` runs the whole engine with three substitutions and no network:

- **The log**: `src/test/resources/musicbrainz/probe-fixture.json` — a small, hand-written,
  human-readable set of node and edge claims. The test writes them into a `SqliteAssertionLog` in a
  `@TempDir` and hands the probe *that path*, so the `--db`-by-path route is the only route in both
  runs and the real `SqliteAssertionLog` and `GraphProjector` are exercised, not bypassed. JSON, not
  `.csv`/`.txt`/`.db`: those three extensions are gitignored.
- **MusicBrainz**: `StubMusicBrainzServer` behind `new MusicBrainzClient(stub.baseUri())`, extended
  with `enqueueBody(path, json)` so it answers `/artist/<mbid>` by path rather than by queue order.
  Additive — the existing queue behaviour stays, so `MusicBrainzClientTest` and the concurrency test
  are untouched.
- **The bridge and the Wikidata side**: `StubIdentity.of(map)` for `P434`, and a hand-built
  `SourceAdapter` returning a fixed `ExpandResult` whose `neighbors()` are the fixture's "described
  in the same call" set. The probe takes the Wikidata-side adapter as a constructor parameter; the
  live run passes the production `WikidataSourceAdapter`, which is what ADR 55's probe drove.

**The positive control is a fixture whose census is known by construction.** The fixture declares its
own totals in a `expected` object beside its claims — seeds, bridged, relations, per-relation-type
counts, the three buckets, the block-5 figures. The fixture test asserts the rendered table equals
those declared totals, so it can go red; the live test asserts only the block-2 invariants above,
which is the issue's own argument. **Breaking the fixture reds it**: add one `tribute` relation to a
served response and block 2's total no longer equals the declared total; move one neighbour from
absent to present in the log and block 3's first bucket disagrees. Both are planted, quoted and
reverted, and each is a different assertion going red — a fixture edit that reds nothing means the
control does not cover that column.

### 5. One Gradle task

`tasks.register<Test>("mbProbe")`: `includeTags("live")`, filtered to the probe class,
`--enable-native-access=ALL-UNNAMED` (which `liveTest` does not set and SQLite needs), never
up-to-date, and it forwards `segue.probe.db` from the invoking Gradle properties. A description
carrying the copy step, in the shape `retractEntity`'s and `ownClaim`'s descriptions use, including
`$HOME` rather than `~`. The class stays `@Tag("live")`, so `./gradlew liveTest` reaches it too and
`./gradlew check` never does.

## Rejected

- **Assert ADR 55's exact numbers in the live run.** The graph grows; the figures are dated
  measurements, not invariants. A test asserting 959 is red the first time anyone seeds anything,
  and the cheapest route back to green is to edit the expectation — which is how a guard dies.
  Structure is what survives, and the fixture run is where exact numbers are legitimate because
  they are true by construction.
- **A `JavaExec` dev tool rather than a test.** It would print the same table, and nothing would
  ever check that it still computes what it says. It would also add a package to
  `ArchitectureTest.DEV_TOOL_PACKAGES` and to `PackageListsTest`'s derivation, and pull the fences
  of ADRs 32 and 60 across an instrument whose whole purpose is to be run twice a year. The
  measurement is evidence for a decision, and evidence in this repository lives in a test.
- **Re-run the probe once and paste the table into ADR 55.** ADR 1 makes an ADR immutable and this
  is the third time (#164, #165, #167) the same shape has been filed: a claim whose instrument was
  used once and discarded. A second attestation replaces neither.
- **A committed SQLite fixture.** `*.db` is gitignored for the reason ADR 33 gives, a binary fixture
  is unreviewable, and the schema would freeze against `SqliteAssertionLog`. Building the database
  from a readable JSON at test time keeps the real writer in the path.
- **Reuse `support.RequiredDatabase`.** It is production code fenced to the two claim tools by
  `theClaimToolsTakeTheirDatabaseFromTheFlagAlone` and `theClaimToolsHaveNoDefaultDatabase`; widening
  it to serve a test would weaken a fence that exists because two copies of that rule is how #179 was
  found. The probe's refusal has a stricter trigger anyway — it must refuse an *existing* real
  database, which no CLI does.
- **Let the live run skip when `segue.probe.db` is absent.** A green run that never ran is the
  failure mode this repository has documented three times. It fails, and says how to give it a copy.
- **Copy `~/.segue/segue.db` from inside the probe.** Then the instrument opens the real database,
  which is the one thing both ADR 55 and this issue forbid. The owner copies it; the probe refuses
  anything that resolves to it.

## Recorded

**No new ADR.** ADR 55 records the decisions and stays immutable; it receives a **dated amendment**
saying that the probe behind its figures is now committed, naming the class and the task, and stating
that the amendment adds no figure and revises no decision. `docs/developer-guide.md` gains one
paragraph in "Why `liveTest` is separate" naming `mbProbe` and the copy step. ADRs 33, 51 and 60 are
cited, not amended.

## Controller rulings (2026-09-02)

1. **Keep block 5 (the cost of `neighbors()`).** The magnitudes are what make ADR 55's declines
   proportionate; a probe that regenerates the census but not the cost regenerates half the argument.
2. **Seed ordering is log order, stated in the probe's javadoc.** ADR 55 did not record it, so the
   *sample* will not reproduce; the *shape* will. The javadoc says exactly that.
3. **Default 200 seeds**, as ADR 55's run; the `--seeds` bound is a parameter, and the live task prints
   its elapsed time.
4. **Four tasks stand.** The offline fixture run with four planted breaks, each reddening a different
   column, is the gate; the live `mbProbe` task is the owner's to run — nobody in this session runs it,
   and the report says so as an unverified boundary rather than implying otherwise.
5. **ADR 55's "two-entry `Map.of`" versus the one-entry code is a disagreement to report, not to
   document away.** The last task appends a dated correction to ADR 55 saying which is right (read the
   code and its history: `git log -S` on the map) and citing the class as the authority for the
   contents.
6. **`ProbeDatabase`'s refusals (non-existent path; the default or anything under `~/.segue`;
   `SEGUE_DB`) are each proven with a control** in the offline test — a refusal never seen to fire is
   the defect this repo files issues about.
