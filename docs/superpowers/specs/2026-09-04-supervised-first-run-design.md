# A supervised first run — design

**Issue:** [#249](https://github.com/robsartin/segue/issues/249). **Scope: one developer-guide
chapter and the test that checks it. No production code, no ADR.** Branch `249-ready`, from
`da8efa9`.

## 1. What the code actually does today

The issue's premise holds, and every fact the runbook rests on was checked against the code at
`da8efa9` rather than taken from the issue.

| claim | where it is true | note |
| --- | --- | --- |
| `ownClaim` takes `mint`, `assert`, `merge`, requires `--db`, honours `--dry-run`, and prints the id it allocated | `own/OwnCli.parse`, `own/OwnRun` | the `Q00` shape and "no row has ever named" are `OwnRun.anIdNothingHasNamed` |
| `retractEntity` takes `--db`, `--qid`, `--reason`, `--dry-run`, and refuses "nothing to retract" | `retract/RetractCli.java:158`, `retract/RetractRun` | the refusal is on a missing **database**; the "nothing to retract" phrasing is `RetractCli`'s |
| the real run's closing note tells the owner to restart before ingesting again | `retract/RetractRun.java:112-119` | quoted verbatim in ADR 24's 2026-09-04 amendment for #234 |
| the boot pre-flight names the row and the repair | `ingest/GraphProjector.refuseRowsNamingAnEntityNoNodeStandsFor`, `:196` | message begins `replay refused: N row(s) name an entity no node stands for.` |
| minted ids are never recycled and the label stays in the log forever | ADR 19; the guide's "A mint costs an id" section | a retraction is a claim, not a deletion (ADR 44) |
| the two-writer rule is convention only | ADR 24; the guide's [Which rules are only convention](../../developer-guide.md#which-rules-are-only-convention) | **nothing detects a running server**; confirmed by grep — no production code asks |
| there is no dev-side bridge tool | `build.gradle.kts` registers `resolveNames`, `exportGraph`, `hoverableSvg`, `listRatings`, `recommend`, `rate`, `retractEntity`, `ownClaim`, `graphCensus`, `evaluate` and nothing else | MusicBrainz is reached only through `expand_entity` in the running server |
| the MusicBrainz adapter describes `PERSON` and `GROUP` only | `musicbrainz/MusicBrainzSourceAdapter.java:206` (`DESCRIBED`) | so the bridge step needs a person or a band as its seed (ADR 54, ADR 61) |
| the census line labels | `census/CensusReport.body` | the authority; this chapter names lines, never counts |

### Two corrections to the framing this issue was dispatched with

**First: `withdrawn` and `taste` do not move in this run, and saying they would is wrong.**
`edges / withdrawn` is read off `LogProjection.withdrawnEdges()`, which counts edges dropped because
a retraction emptied a **merge's** canonical id (`Equivalences.retractedStandIns`, #224). This
runbook makes no merge, so that line must stay where it is. Nothing here rates anything either, so
every line under `taste` must stay where it is. Both become *expectations of no movement* in the
chapter — which is the more useful assertion, because a movement there is a finding.

What does move under `claims` is `log rows`, `retractions`, `rows they removed`, `entities they
name`, and `local entities minted` — which goes **up at the mint and back down at the retraction**,
because `ClaimCensus` counts *surviving* `LocalEntity` rows (`ClaimCensus.java:78`, guarded by
`Retractions.survives`).

**Second: one test class cannot run all three parsers.** `OwnCli.parse`, `RetractCli.parse` and
`CensusCli.parse` are package-private in three different packages, deliberately — the existing
`DeveloperGuide*ExamplesTest` classes are split across `own`, `retract` and `census` for exactly
that reason, and each says in its javadoc that widening the seam to suit a test was refused. A
single `DeveloperGuideSupervisedRunExamplesTest` therefore **cannot** call all three.

It does not need to. `GuideExamples.of(taskName)` scans **the whole guide file**, not one chapter,
so the moment the new chapter exists its `ownClaim`, `retractEntity` and `graphCensus` lines are
already being handed to the three real parsers by the three existing tests — by construction, with
no new code. What is genuinely missing is a **chapter-scoped** check: today the supervised-run
chapter could be deleted whole and every one of those tests would stay green on the other chapters'
examples.

So the new class asserts what nothing else can — that the chapter exists, that its commands are the
right commands in the right order, and that it cites the decisions it leans on — and the plan proves
the parser link by **planting**: drop `--db` from one of the chapter's own lines and watch
`DeveloperGuideOwnClaimExamplesTest` go red naming that line. That is the honest chain, and the
alternative (a public delegating seam in production code) is the one this repository has already
refused twice.

## 2. Decision

### 2.1 The chapter

A new `## A supervised first run` in `docs/developer-guide.md`, placed after
[Claiming something no source has](../../developer-guide.md#claiming-something-no-source-has) and
before "How to read an ADR against the code" — it depends on all three tool chapters, so it comes
after the last of them. One `Contents` entry in the same position.

Ten numbered `###` steps, each one the owner executes:

| step | what | commands |
| --- | --- | --- |
| 0 | quit the MCP client; confirm no `segue` JVM | `pgrep -fl segue` (read-only, not a Gradle task) |
| 1 | the census before; paste it | `graphCensus` |
| 2 | mint, dry run then real; the owner chooses the label | `ownClaim mint` ×2 |
| 3 | one owner edge from the minted id to an entity the owner picks | `ownClaim assert` ×2 |
| 4 | boot once (start the client), `get_entity` the minted id, quit | none |
| 5 | retract the minted id, dry run then real; read the closing note | `retractEntity` ×2 |
| 6 | boot again — the pre-flight must pass | none |
| 7 | the census after; paste it | `graphCensus` |
| 8 | which lines moved and in which direction — **no numbers** | none |
| 9 | optional bridge: `expand_entity` on a PERSON or GROUP, quit, census | `graphCensus` |
| — | closing: "What to file from what you saw" | none |

Nine `./gradlew` lines in that order. Three rules the chapter holds itself to:

- **The owner runs every writing step.** The chapter says so in its first paragraph, and says that
  an agent reading it is reading a description, not a script.
- **No numbers in step 8.** A table of expected figures would be a second source going stale (and
  the run is what produces them). Lines and directions only.
- **Nothing is restated.** The three tool chapters keep their own explanations; this one links them.

### 2.2 The check

**`GuideExamples` gains chapter scoping**, in `arch`, where the extraction already lives:

- `public static Optional<String> chapterText(String heading)` — the `## heading` chapter's lines
  joined, empty when there is no such chapter.
- `public static GuideExamples inChapter(String heading, String taskName)` — `of`'s extraction
  restricted to that chapter's line range, empty when the chapter is absent.

`of(taskName)` keeps its signature and its answer; both new methods and it share one private scan
over one private `chapterRange`. Line numbers stay absolute, so a failure still names the guide line.

**`arch.DeveloperGuideSupervisedRunExamplesTest`**, five methods:

1. the chapter exists (`chapterText` is present) — the loud guard, because everything below reads
   empty rather than throwing when the heading is gone;
2. the chapter's commands, merged across the three tasks and sorted by line, are exactly
   `graphCensus`, `ownClaim mint --dry-run`, `ownClaim mint`, `ownClaim assert --dry-run`,
   `ownClaim assert`, `retractEntity --dry-run`, `retractEntity`, `graphCensus`, `graphCensus`;
3. no chapter line naming one of the three tasks is unreadable;
4. no chapter example writes a tilde;
5. the chapter's `adr/NNNN-` link targets include 0024, 0044, 0059, 0060 and 0063.

Assertion 2 is the substance: a runbook whose steps are in the wrong order, or that writes without a
dry run first, or that forgets a census, is wrong in a way no parser can see.

### 2.3 Rejected

- **A public `parse` seam so one class can drive all three parsers.** Refused in
  `DeveloperGuideOwnClaimExamplesTest`'s own javadoc ("widening it to suit a test would undo the
  reason it is a seam"), and unnecessary — the whole-file scan already reaches the new chapter.
- **Three new chapter-scoped test classes, one per package.** Triples the file count to re-assert,
  per package, what the whole-file tests already assert; the chapter-level facts (order, citations)
  would still need a fourth home.
- **Making `inChapter` throw on a missing heading.** It would turn the RED into an exception in four
  methods instead of an assertion in one. The loudness lives in assertion 1 instead, which names the
  heading, plus assertion 2, which cannot pass vacuously.
- **Expected counts in step 8.** A number here is a second source of truth about the owner's own
  graph, and this chapter cannot see that graph.
- **An ADR.** Nothing is decided. The chapter is a procedure over rules ADR 24, 44, 59, 60 and 63
  already fixed, and it cites each where it leans on it. ADR 19's "immutable, amend only" applies to
  none of them, because none changes.
- **Running any of it.** No step of the plan opens, reads, copies or creates `~/.segue/segue.db`, and
  no Gradle writing task is invoked. The test calls the parsers with `GuideExamples.INVENTED_HOME`
  only, exactly as its three siblings do.

## 3. Residual

- **The chapter has never been run.** It is written against the code and checked against the
  parsers; the first real execution is the owner's, and the closing section says so out loud and
  asks him to file what it got wrong.
- **Assertion 2 pins the command sequence, not the prose around it.** A step whose narrative is
  wrong while its commands are right stays green. That is the limit of a check over `--args` lines,
  and widening it into prose-matching would be the mirror-the-code failure ADRs are warned about.
- **`pgrep -fl segue` is a suggestion, not a check.** Nothing in the build verifies that no server
  is running; ADR 24 records that gap, and this chapter does not close it.
