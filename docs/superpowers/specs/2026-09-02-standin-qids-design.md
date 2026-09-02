# Every stand-in identifier is one Wikidata cannot allocate, and a test says so

Issue #171. Written 2026-09-02, against `origin/main` at `69c812a`.

## The defect, measured

[ADR 58](../../adr/0058-stand-in-identifiers-cannot-be-allocatable.md) moved `Fixture`'s own family
to the leading-zero form and said, in its own consequences, that the repository is not clean. This
is that measurement, retaken on `69c812a`, and the shape of the work it implies.

### Two scans, and why the difference matters

ADR 58 counted `\bQ\d+\b` over every file `git ls-files src/test` reports — javadoc mentions
included, deliberately, because a stand-in named in prose denotes a real entity just as firmly as
one in a literal. That count is still the honest description of the problem. It is **not** the right
input to a test that fails the build, and the gap is the first thing this sweep found:

| scan | distinct allocatable-form ids | files |
|---|---|---|
| whole file, `\bQ\d+\b` (ADR 58's definition) | 211 | 91 |
| Java string literals with comments stripped, plus non-Java resources whole | **207** | **87** |
| Java literals that are *exactly* a qid, plus non-Java resources whole | 205 | 84 |

The four ids the second scan drops — `Q11571`, `Q12345`, `Q1219310`, `Q2526255` — appear only in
javadoc, and three of the four appear there *because the javadoc is explaining this rule*. A guard
reading whole files reds on the prose that documents it, and the fix somebody reaches for is to
weaken the guard. This is the documentation twin of the inline-code span in
[the doc-links sweep](2026-09-02-doc-links-design.md).

The third scan is one step too far. It misses exactly two tokens, and they are instructive in
opposite directions: `Q4`, which is the question number in `GraphStoreContract`'s
`@DisplayName("Q4: three edges have two independent sources…")` and is not an identifier at all;
and `Q999999998`, which is a genuine stand-in embedded in a JSON-RPC request string at
`StdioPurityTest:123`. **A guard that reads tokens inside literals sees both**, so it needs one
reviewed allowlist entry for the question numbers and misses no stand-in. A guard that reads only
whole-literal qids needs no entry for `Q4` and silently loses a stand-in. The allowlist entry is the
cheaper mistake, because it is visible.

**The set this spec works from is the second scan: 207 allocatable-form ids across 87 files.**

### Which of them collide, without asking Wikidata

Wikibase allocates item ids from one sequential counter starting at `Q1`, so **every number below
the counter's high-water mark has been minted**, and a number later deleted is the same collision
arriving a step later — ADR 58's own `Q900014` is the worked example. ADR 58's dated resolution run
(2026-08-31) brackets the frontier: 248 of 263 tokens resolved, and the ones that did not are
`Q99999998`, `Q99999999` and `Q999999996`–`Q999999999`. So the frontier sits between 10⁸ and 10⁹.

**Every stand-in family in this repository except one sits below 10⁶ — three orders of magnitude
under the frontier — and is therefore allocated by construction.** No lookup is needed and none was
made. The exception is the high-range band, which is the wager ADR 58 named and rejected: it does
not collide today, and "does not collide today" is not a property that holds.

### The 207, triaged

**100 stand-ins across 60 files, 754 occurrences.** **107 deliberately real across 48 files, 344
occurrences** — class ids (`Q5`, `Q515`, `Q11424`), the entities `WikidataLiveSmokeTest` is about,
and the recorded `src/test/resources/wikidata/*.json` responses.

| band | ids | files | what it is |
|---|---|---|---|
| A `Q9001xx` | 26 | 27 | the largest family; `SharedSubjectRouteTest`'s javadoc admits it |
| B `Q9002xx` | 18 | 13 | routing hubs |
| C `Q9003xx` | 11 | 10 | candidate sweeps |
| D `Q9004xx`–`Q9008xx` | 15 | 8 | taste tools, path ranking, one JSON resource |
| E `Q9009xx`, `Q9010x` | 5 | 16 | filler entities, minted by prefix |
| F `Q900042` | 1 | 1 | `InventedRatings.CANONICAL` — see the blocker |
| G `Q900`–`Q906` | 7 | 15 | merge canonical sides *and* export view nodes — see the blocker |
| H `Q1`,`Q2`,`Q3`,`Q404`,`Q405`,`Q999`,`Q999999` | 7 | 8 | arbitrary small ids |
| I `Q100001`–`Q100004` | 4 | 1 | `GraphStoreContract` |
| J `Q99999998`,`Q99999999`,`Q999999996`–`Q999999999` | 6 | 7 | the high-range wager |

**`src/main` has nothing to migrate.** It carries 162 allocatable-form literal ids across 10 files
and not one stand-in: `Q901` in `seed/Expectations` is Wikidata's real *scientist* class, `Q12345`
is the exemplar in five error messages and one usage string, `Q192668` is Nick Cave in
`EntityTools`' tool description. That `Q901` is a real class id in production while the same string
is a stand-in node in `DotWriterTest` and a merge canonical in `EquivalencesTest` is the collision
this issue is about, stated in one line.

### What guards this today, and the proof that nothing does

`FixtureQidsDenoteNothingTest` reflects over `Fixture`'s public static `String` fields. That is its
whole scope. Both halves were measured:

- **The instrument is alive.** A field `PLANTED_CONTROL = "Q900016"` added to `Fixture` reds:
  `FixtureQidsDenoteNothingTest > every Fixture qid is one Wikibase's grammar refuses, so Wikidata
  can never allocate it FAILED / java.lang.AssertionError at FixtureQidsDenoteNothingTest.java:65`,
  `2 tests completed, 1 failed`. Reverted.
- **It sees nothing outside `Fixture`.** The same id planted in `DotWriterTest`, replacing `Q906`:
  `./gradlew test --tests 'com.robsartin.segue.arch.*' --tests 'com.robsartin.segue.fixture.*'` →
  `BUILD SUCCESSFUL`. Reverted.

No ArchUnit rule and no regex-over-sources test covers the other 60 files. `ArchitectureTest`,
`PackageListsTest`, `AdrIndexTest`, `DeveloperGuideEnumerationsTest` and `DocumentationLinksTest`
all check other things.

### The mechanical shape: a per-family `sed` is not sound

Five findings, each measured, in rough order of how quietly they would break:

1. **Twenty ids are built at runtime from a prefix literal.** `"Q9001" + String.format("%02d", i)`,
   `"Q9002" + (10 + i)`, `"Q9009" + i`, `"Q90031" + i`, `"Q9010" + i`, and
   `InventedWorld:105`'s `"Q9009" + (Math.abs(qid.hashCode()) % 90 + 10) + index`. The entire
   `Q90xx` "family" — 7 tokens across 12 files — **is these prefixes, not ids**. Rewriting
   `Q900101` → `Q0900101` leaves `"Q9001"` untouched and the ids it mints still colliding.
   The prefix has to move with the family, and **one predicate moves with the prefix**:
   `PathRankingTest:320` reads `qid -> qid.startsWith("Q9002") ? PathRanking.HUB_DEGREE : …`.
   Change the constructor and not the predicate and every node stops being a hub, silently.
2. **Six sites are invisible to any scan over `Q\d+`.** `"Q" + i` and `"Q" + (i + 1)`
   (`PathRankingTest:368-369`), `"Q" + (700 + i)` and `"Q" + (100 + i)` twice
   (`SegueServiceTest:304,339,370`), `"Q" + next++` (`MusicBrainzSourceAdapterTest:575`). They mint
   `Q1`…`Qn`, `Q100`…, `Q700`… at runtime — all real, all below the frontier. **No source-text
   guard can ever see these**, which is a limit of the mechanism and has to be said out loud in the
   guard's own javadoc rather than discovered later.
3. **Tests assert a stand-in's exact rendered string.** `DotWriterTest` asserts
   `"\"Q902\" [label=\"The Paper Kettles\", shape=box…"` and `tooltip=\"Q900901\"`;
   `WhatAHoverShowsTest` asserts `"Q901->Q902"`; `ViewSelectorTest` asserts
   `hasMessageContaining("Q900999")`; `SegueServiceTest` asserts `result.detail()).contains("Q404")`.
   Measured: replacing `Q906` with `Q900016` in `DotWriterTest` reds *"node shape is chosen by
   NodeKind, so six kinds read apart at a glance"* — `27 tests completed, 1 failed`. A whole-file
   rewrite handles these, and only because it is whole-file: a rewrite scoped to constructor calls
   would leave every expectation behind.
4. **One resource file carries a stand-in.** `src/test/resources/wikidata/bad-seeds-reverse.json`
   has a hand-inserted row, `"http://www.wikidata.org/entity/Q900790"` labelled *Where the Wild
   Roses Grow*, asserted at `WikidataSourceAdapterTest:319` by `containsExactly("Q900790")`. It is
   the only one: every other qid in the ten `wikidata/*.json` recordings and the one
   `musicbrainz/*.json` is a real recorded value and must not move. **No SQL fixture and no
   expected-output file carries a qid at all.**
5. **Nothing parses the number, but four production sorts compare it as a string.** There is no
   `parseInt`, no `substring(1)`, no numeric comparison over a qid anywhere in `src/main` or
   `src/test`. But `SortOrder.RATING`, `SortOrder.RECENT`, `KnownList.promoted`
   (`Comparator.naturalOrder()`) and `Deck`'s known-card sort (`thenComparing(Card::qid)`) each end
   in a lexicographic qid tiebreak, and each is documented as existing so two runs produce
   byte-identical output. A leading zero moves an id to the front of every string comparison
   (`"Q0…" < "Q1…" < "Q9…"`). **Within a family relative order survives** — same prefix, same width
   — **but any tie broken across families flips.** `DeckTest` already mixes migrated `Q09…` fixture
   ids with unmigrated `Q9001xx`, `Q9005xx` and `Q9009xx`, which is the concrete reason the gate
   runs after every family rather than once at the end.

Nothing in `src/main` changes. `Qid.PATTERN` is `Q\d+` and so are the seven copies outside `domain`
(`SegueService`, `RetractCli`, `WikidataFacts`, `QidList`, `ClaimMapper`, `ReverseClaims`,
`WikidataEntityResolver`); every one already accepts a leading zero.

### The blocker: bands F and G cannot take a leading zero

`SameAs.declared(localQid, canonicalQid)` calls `LocalEntity.checkUnallocatable(localQid)` **and
`Qid.checkAllocatable(canonicalQid)`**. A merge's canonical side must be an id Wikidata could
really allocate — `InventedRatings` lines 46–50 document exactly that, and ADR 59 requires it, since
the canonical side is by definition the id a source turned out to have.

So `Q900042` (band F) and `Q900`–`Q905` where they stand as canonical sides — `EquivalencesTest`
lines 116–120, `KnownListTest:143,155`, `OwnerClaimTest:78`, `MergeWiringTest:41`,
`MergeCarriesEverythingTest` — are stand-ins ADR 58 forbids that production code requires to stay
in the shape ADR 58 forbids. There is no leading-zero form available to them. **This is a decision,
not a rename, and it is the one part of #171 that cannot be dispatched until it is taken.**

## The decision

**Migrate every stand-in to leading-zero form by prepending one zero — `Q900100` → `Q0900100`,
`Q900` → `Q0900`, `Q404` → `Q0404` — family by family, and replace `FixtureQidsDenoteNothingTest`'s
reflection over fifteen constants with a scan of every test source, carrying a reviewed allowlist of
the deliberately-real ids.**

Prepending is the whole rule. It is what `LocalEntity`'s javadoc already predicts for band A
(*"issue #171 will migrate `Q900100` … as `Q0900100`"*), it keeps every family readable as itself,
and it makes the change reviewable by eye. Renumbering into a single new range was rejected below.

**The guard is `StandInQidsDenoteNothingTest` in `arch`**, on `RepositoryTree`, beside the other
tests that check the tree against a rule. It reads every file `git ls-files src/test` reports;
for a `.java` file it strips line and block comments and takes every `\bQ\d+\b` inside a string
literal, and for every other file it takes every `\bQ\d+\b`; it fails on any token matching
`Q[1-9]\d{0,9}` that is not in the allowlist, naming file, line and id. `FixtureQidsDenoteNothingTest`
stays: it holds the *shape* rule for `Fixture`'s own constants and the `Qid.looksLikeAQid` half,
which a text scan cannot assert.

**The allowlist is the deliverable with lasting value**, and it is a `Map<String, String>` of id to
reason, not a `Set`. Four kinds of entry, each of which has to say which kind it is:

- a class id the mapper or the labeller uses (`Q5`, `Q515`, `Q11424`, and the rest of
  `KindMapper`/`ClassLabels`/`RecognitionInstitutions`' vocabulary);
- an entity a live test or a recorded response is genuinely about (`Q192668`, `Q1051182`, …);
- `Q42` in `OwnerClaimTest`, which is deliberately allocatable *because the test asserts it is
  refused* — a negative control that would be destroyed by migrating it;
- `Q1`–`Q4` where `GraphStoreContract` uses them as question numbers in a `@DisplayName`, which the
  scan cannot tell from an identifier and a human can.

**Bands F and G's canonical sides.** Recorded as an open question rather than decided here, because
the three answers differ in what they cost and only one of them is free of an ADR. See "Open" below.

**Band J migrates, except where being unallocated is the subject.** `Q999999999` is load-bearing in
`FixtureQidsDenoteNothingTest`'s javadoc, which contrasts it (`resource-not-found`) with `Q0900001`
(`invalid-path-parameter`) — that contrast is the fact ADR 58 turns on, and erasing it would leave
the ADR's own evidence unrepresented. Those sites are allowlisted with that reason. The rest —
`KindMapperTest`'s unmapped P31 values, the resolver's 404 subject — migrate, because there the id
is a stand-in for "something the source does not know" and the leading-zero form says so better.

**Positive controls, definition of done.** (1) Plant an allocatable stand-in in a test file that is
not `Fixture` → the guard reds naming that file, line and id; today the same plant is
`BUILD SUCCESSFUL`, and the plan quotes both. (2) Remove one entry from the allowlist → the guard
reds on the real id it was covering, proving the allowlist is load-bearing rather than decorative.
(3) Point the scan at an empty directory → the vacuity guard reds, proving a green is not an empty
sweep. (4) Delete the comment-stripping step → the guard reds on `FixtureQidsDenoteNothingTest`'s
own javadoc, which is the false-positive class this design exists to avoid. Each quoted, each
reverted.

## Rejected

- **Renumber every stand-in into one new family.** Tidier to read, and it would collapse ten bands
  into one. Rejected on blast radius and on review cost: prepending a zero is verifiable by eye in a
  diff, where a renumber is a table a reviewer has to trust. It would also throw away the local
  meaning the bands carry — `Q9002xx` is *hubs* in four different test files — for no gain the
  grammar does not already give.
- **A single sweep across all ten bands.** Explicitly what ADR 58 warned against and what
  `SharedSubjectRouteTest`'s javadoc says was declined once already: band A alone spans 27 files and
  five bands share `InventedWorld.java`, so one commit would be green only at the end. The
  lexicographic-tiebreak finding makes it worse than a review problem — a cross-family tie flipping
  in `DeckTest` would surface as an unrelated ordering failure in a diff too large to bisect by eye.
- **Fix the stand-ins and leave the guard scoped to `Fixture`.** The cheapest option, and it leaves
  the next contributor free to add `Q900016` in good faith — which is exactly the failure ADR 58
  was written about, and which the planted control above proves is still available today.
- **A `Set` allowlist rather than id-to-reason.** Half the size. Rejected because an allowlist
  without reasons is a list of numbers nobody can review, and "this test uses a real Wikidata id" is
  meant to become a deliberate act rather than a default. A reason is the only part that makes a
  future entry arguable.
- **Extend `FixtureQidsDenoteNothingTest` instead of adding a test.** Rejected: that test asserts
  two things about fifteen reflected constants, one of which (`Qid.looksLikeAQid`) has no text-scan
  equivalent. Folding a repository sweep into it would give one class two unrelated oracles.
- **A `Q\d+` scan over whole files, ADR 58's own definition.** Rejected on the measurement above:
  it reds on four javadoc mentions, three of which exist to explain this rule.

## Open — the controller decides before bands F and G are dispatched

**What shape a merge's canonical side takes.** Three answers:

1. **Allowlist them, reason: "a merge's canonical side must be allocatable."** No ADR, no production
   change, smallest diff. It leaves fabricated `SameAs` claims pointing at real Wikidata entities —
   `Q901` really is *scientist* — which is the class of falsehood ADR 58 exists to stop, now written
   down as deliberate rather than accidental.
2. **Use an eleven-digit id, e.g. `Q10000000000`.** `Qid.ALLOCATABLE` is `Q[1-9]\d*`, **unbounded**;
   Wikibase's grammar is `Q[1-9]\d{0,9}`, capped at ten digits. So an eleven-digit id passes
   `Qid.checkAllocatable` and is refused by the grammar, for the same kind of reason a leading zero
   is. **This is not the option ADR 58 rejected**: that one was `Q2147483648`, which is ten digits,
   matches the grammar, and is unallocatable only because of `Int32EntityId::MAX` — a storage width.
   Costs a new ADR or an ADR 58 amendment, because it admits a second unallocatable shape.
3. **Narrow `Qid.ALLOCATABLE` to `Q[1-9]\d{0,9}` and then take option 2.** Closes a hole ADR 58
   never named — segue currently accepts an eleven-digit qid Wikidata cannot represent — but it is a
   production change inside `domain`, and it would make option 2's ids illegal, so the two are
   alternatives and not steps.

**Band H's target form.** Prepending gives `Q01`, `Q02`, `Q0404`. Legal, unallocatable, mechanically
uniform with every other band — and `Q01` reads as a typo rather than a convention. The alternative
is renumbering those seven into `Q0900xxx`, which reads better and loses `Q404`. This draft
prepends; it is a taste call and reversible.

**Whether this needs an ADR.** Under option 1, no: a widened gate and a rename record no decision,
and ADR 58 already holds the rule. Under options 2 or 3, yes. Either way ADR 58's consequence *"the
repository is not clean"* becomes false when this lands, and an immutable ADR is corrected by a
dated amendment saying what discharged it.

## Recorded

`CLAUDE.md`'s *"Do not read that as 'the test fixtures are clean'"* bullet (lines 247–252) is
retired and its first bullet's `Fixture` range citation left alone. `SharedSubjectRouteTest`'s
javadoc paragraph admitting its ids denote something is deleted with the ids it describes. The
developer guide's testing-strategy table gains the new test on the Architecture or Documentation
row. **No count is restated in any of them** — ADR 58 holds the numbers and this spec holds the
retake, both dated.

## Controller rulings (2026-09-02)

1. **The merge canonical side (Task 10) awaits the owner.** The planner's three options are all
   defensible; (2) and (3) change what `Qid.ALLOCATABLE` means and need an ADR, so the choice is the
   owner's. Task 10 stays blocked; its unblocked half (the export `ViewNode` ids) proceeds with the
   other bands. Controller's recommendation to the owner: (3) — narrow `Qid.ALLOCATABLE` to Wikibase's
   grammar `Q[1-9]\d{0,9}`, which closes a hole ADR 58 never named, then use an eleven-digit canonical
   for fixtures, unallocatable by the grammar rather than by a storage width.
2. **Band H takes the leading-zero form (`Q0` + the original digits), never a renumbering.** Every
   migrated id stays traceable to what it was, which is what makes the band-by-band diffs reviewable
   and the exclusion list's shrinkage mechanical.
3. **Tasks 1–9 and 11 proceed in order, one implementer at a time, gate green after each band**
   (Mikado). Task 12 waits for Task 10.
