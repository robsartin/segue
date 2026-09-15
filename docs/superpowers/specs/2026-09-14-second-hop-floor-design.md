# The second-hop rows inherit the never-expanded floor — design

Issue #326. Written 2026-09-14 against the code on `main` after #319 (`graphCensus`'s three nested
`no known neighbour` rows and `expandPromotions --second-hop`) and #323 (the first `--second-hop`
run and the censuses before and after it). Prose only: two dated ADR amendments and four
developer-guide edits, plus one javadoc sentence. **No behaviour changes, and no production code
changes beyond that one sentence.**

## What was measured, and what it makes true

The run and both censuses are on issue #323 (2026-09-14); no figure from any of them is restated
here or in anything this issue commits. What matters is the shape: the census's `distinct to expand`
row fell after the run and did not reach zero.

That is not a shortfall in the run. `SecondHop.toExpandBeside` — the rule both the census's three
nested rows and a `--second-hop` run's population are read off — excludes a neighbour only once
`domain.Expanded` already covers it, and `Expanded.seedOf` reads a seed out of two Wikidata reference
shapes and nothing else: a forward statement id, or a reverse-discovered edge's own reference. A
neighbour a `--second-hop` run visits and expands is not guaranteed to clear that bar. Two shapes of
"visited but not covered" are already named in `Expanded`'s own class javadoc, for a different
population: a `MusicBrainzSourceAdapter` edge (its reference is `artist/<mbid>#<type>:<mbid>`, never
a qid-shaped fragment) and a Wikidata forward statement whose claim carries no `id` and falls back to
`ClaimMapper`'s `<property>:<objectQid>` reference. Either one leaves the visited neighbour still
uncovered, so `distinct to expand` keeps counting it — for good, since no further `--second-hop` run
can add what Wikidata never stated.

This is the same finding ADR 63's and ADR 66's 2026-09-12 amendments for #315 already made about the
`known list` section's `never expanded` row and `expandPromotions --known`, on a population that rule
never visits. #326 is that finding, met on the population `--second-hop` does visit: the three nested
rows the 2026-09-13 amendment for #319 added — `with someone to expand beside`, `with no one`,
`distinct to expand` — are governed by the identical `Expanded.covers` call, so they inherit the same
floor for the same reason.

## What changes

### ADR 63 — a dated amendment (2026-09-14, issue #326)

Records that the three rows added by the 2026-09-13 amendment for #319 are read off
`SecondHop.toExpandBeside`, which is gated on `Expanded.covers` — the same call and the same rule the
`never expanded` row and the 2026-09-12 amendments for #311/#313/#315 already govern — and that the
census and run on #323 measured the same residual on this population: a visited neighbour whose
expansion recorded only a MusicBrainz-backed edge or a Wikidata forward claim with no id leaves no
seed reference, so the three rows floor rather than empty. Names the developer guide's `--second-hop`
chapter as the authority on the operator's procedure, rather than restating it. Carries the three
declined alternatives the dispatch names, each with its own reason: a visited-marker row in the log
(the reverse pass's own node-claim shape is the trap `Expanded`'s class javadoc already names); reading
the MusicBrainz adapter's own references (the seed's MBID is not a qid, the fold keeps no
MBID-to-qid map, and MusicBrainz's `forward`/`backward` direction puts the seed on either endpoint of
the edge the reference is built from); and a "visited" count in the census (nothing in the log carries
it — the log records what an expansion asserted, not that it ran). Records that `domain.SecondHop`,
`KnownListCensus` and `CensusReport` emit exactly what they emitted before, and that the only edit
under `src/main` this issue makes is one javadoc sentence.

### ADR 66 — a dated amendment (2026-09-14, issue #326)

Short, and narrower than ADR 63's: it overtakes one clause in the 2026-09-13 amendment's *Fixed at
the start, and why re-runs need no state* paragraph. That paragraph says "an entity this run expanded
is covered by `Expanded` on the next read of the log" as a flat fact; the census and run on #323 show
it is true only when the expansion recorded a reference `Expanded.seedOf` reads, and not for the
residual named above. The amendment names the overtaken clause and points at ADR 63's 2026-09-14
amendment for the reading, using the same "this entry records which sentence here it overtakes"
convention ADR 63's own 2026-09-12 amendment for #313 already uses on a sentence in the 2026-09-12
amendment for #311.

**Why a short overtake rather than a full `--known`-shaped decision.** #315 gave `--known` a whole
second decision on ADR 66 (idempotent-in-the-graph, the declined marker, the operator's procedure)
because none of that existed anywhere yet. Here, the procedure belongs in the developer guide (ADR
66's own convention already says the guide is the authority and is "not restated" in the ADR), and
the declined alternatives belong to ADR 63 this time — two of the three are about the census's own
counting rule rather than the expander's write behaviour, and the third is the same log-shape
argument ADR 63's #311/#315 amendments already carry for a different row. What ADR 66 is left owing
is the one sentence of its own prose that the new reading shows incomplete, and that is what the
amendment fixes.

### The developer guide, four edits, all in `docs/developer-guide.md`

- **"Expanding every promotion", the `--second-hop` variant's "How to read the three rows"
  paragraph.** One sentence added: all three rows inherit `never expanded`'s floor, for the reason
  above, citing ADR 63's new amendment.
- **The same variant's paragraph after the dry run.** The sentence "`no known neighbour` should be
  down, `distinct to expand` should be down... **A smaller run is a later run**" is replaced with a
  "When to stop running this at all" paragraph in the `--known` variant's own shape: read off the
  run's own block — `added nothing`, `refused`, `failed` at zero and nothing named under
  `unavailable` — rather than a second run, with the fallback case (something failed or was
  unavailable) handed to the `--known` variant's own `considered`-comparison rule.
- **"Looking at the shape of your graph", `### What the two sub-sections mean`.** One sentence added
  to the paragraph already describing the three nested rows, for parity with the treatment
  `never expanded` got in the same section from the #315 plan, pointing back at the runbook chapter.
- **"What to file from what you saw".** The sentence naming which runs have sent something back to
  this chapter (#293, #315) gains #319 as a run and #326 as a correction, in the same shape.

No test in `src/test` restates any of the corrected sentences (verified by grep — see *Premise
corrections* below), so no test file needs a matching edit the way #315's Task 3 needed one.

### One javadoc sentence

`census.KnownListCensus.Population`'s `@param distinctToExpand` currently says the count is "before
any run"; one sentence is added saying that once a `--second-hop` run meeting the same block
condition above has visited everything it counts, what is left is the same floor `neverExpanded`
already names, and citing why: `SecondHop.toExpandBeside` excludes a neighbour only once
`Expanded.covers` it too.

## Verification, and the honest exception

**No behaviour changes, so no test is written for behaviour.** What verifies the documents:

- `AdrIndexTest` — every ADR file has exactly one index row agreeing on number, title and status; an
  amendment changes none of those.
- `AdrCitationsTest` — no commit hash reaches `docs/adr` outside its allowlist; a backticked 7-40
  character hex run anywhere in the two amendments would fire it. Neither amendment adds one.
- `DocumentationLinksTest` — every relative link the two amendments and the four guide edits add
  resolves to a file and a heading.
- `DeveloperGuideExpandPromotionsExamplesTest` — the chapter is present, every `--args` line parses
  through `ExpandCli.parse`, no tilde stands where `$HOME` belongs, and the chapter's nine `./gradlew`
  lines stay exactly the same twelve-item sequence in the same order — none of the four prose edits
  touches a command line.
- `DeveloperGuideCensusExamplesTest` — the same, for the census chapter's `graphCensus` lines
  (untouched by this issue, run as a control that the census chapter is otherwise unmoved).
- `JavadocCitationsTest` and `javadoc -Werror` inside `./gradlew check` — the one javadoc edit.
- `docs` is a declared input of the `test` task (`build.gradle.kts:150`), so an edit under `docs/`
  re-runs the suite rather than leaving it `UP-TO-DATE`. The plan proves that per task, without
  `--rerun-tasks`.

Every task plants a defect in the file it edits, watches the guard that reads that file fire, quotes
the real failure text, and removes the plant before the real edit — the same positive-control
discipline #315's plan used, because nothing here can red on the prose itself.

## Premise corrections

1. **No test in `src/test` restates the sentence being replaced.**
   `grep -rn "should be down\|smaller run is a later run" docs/ src/test` finds the sentence only in
   `docs/developer-guide.md` itself (twice, both lines of the one paragraph being replaced) and in
   two already-committed planning documents for #319 (`docs/superpowers/plans/2026-09-13-second-hop.md`,
   `docs/superpowers/specs/2026-09-13-second-hop-design.md`), which this issue does not touch — they
   are the historical record of what #319 shipped, not live prose. Unlike #315's Task 3, this plan
   needs no matching test-message edit.

2. **ADR 66's 2026-09-13 amendment for #319 is not merely silent about the floor — one of its own
   clauses reads as false in the residual case.** The issue's *Shape* section asks only that ADR 63
   record the floor and its alternatives; it does not name this. Read against the code, "an entity
   this run expanded is covered by `Expanded` on the next read of the log" (the *Fixed at the start*
   paragraph) states the covering as unconditional, and the census and run on #323 show it holds only
   for an expansion that recorded a reference `Expanded.seedOf` reads. This plan corrects that one
   clause with a short overtake on ADR 66, on the precedent ADR 66's own Consequences already use for
   three other falsified statements ("each is corrected rather than deleted").

3. **The MBID-to-qid direction is confirmed from the interface, not assumed.** `MusicBrainzIdentity`
   declares `mbidFor(qid)` (qid → MBID) and `identitiesFor(mbids)` (MBID → `BridgedIdentity`,
   carrying a qid); neither result is retained anywhere the fold or `Expanded` could read afterwards,
   and `MusicBrainzSourceAdapter.toAssertion` builds `sourceRef` from the seed's MBID and the
   relation's own `forward`/`backward` direction, never from a qid. The "reading MusicBrainz
   references" alternative in ADR 63's amendment cites this precisely rather than the shorthand the
   dispatch used.

## Not this design

Changing `Expanded`'s rule, `SecondHop`, `KnownListCensus`'s counting, or `CensusReport`'s output —
the issue's own *Not this issue* rules all three out, and nothing measured here argues for any of
them: the block stays byte-identical, which is what makes the guarantee ADR 63's original decision
gives ("safe to paste") worth having across runs. Also not this design: a label-only home for the
floor inside the census block itself. `CensusReport`'s guarantee is that the block is byte-identical
run to run and with or without a flag, and "floor" is not a fact the block could print honestly
before the first qualifying `--second-hop` run — the same reason #315 declined a label for
`never expanded`. Restating that here rather than proposing a new one is the answer to that open
question in the dispatch: **none**, and this is why.
