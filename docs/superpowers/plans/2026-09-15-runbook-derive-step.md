# The `--known --add` chapter's derive step, corrected against a real run — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close issue #332. The developer guide's "Adding what your list names that the graph has
never held: `--known --add`" chapter (added by #328) told the owner to derive the non-touring list
with a `grep` over an already-resolved mapping. The first real run of the chapter, issue #330
(2026-09-15), went a different way, because the owner's real list is a Setlist Scout export with a
`status` column and **no id column at all** — the grep step assumed a resolved mapping that never
existed. This plan corrects the chapter's step 1 and the one sentence in its intro paragraph that
carries the same wrong assumption.

**Architecture:** nothing. **This plan changes no production behaviour and touches no method
body, signature or constant.** One document, two paragraphs:

- `docs/developer-guide.md` — the intro paragraph of "Adding what your list names that the graph
  has never held: `--known --add`" (one sentence), and its step 1, "Derive the file." (replaced in
  full).

**Tech Stack:** Java 25, Gradle (plain `./gradlew`), JUnit 5, AssertJ, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-09-15-runbook-derive-step-design.md` — read it first,
especially *Why the "review rows carry no qid" clause is dropped, not carried forward* (a
production-code finding this plan reports but does not fix) and *Command or prose* (the reshape
step is prose, not a runnable command, and why).

## Global Constraints

- **No behaviour changes, and no test is written for behaviour.** That is the honest exception
  this project allows for pure prose, stated here rather than left implied. Nothing in this plan
  edits a method body, a signature or a constant. If a step seems to require one, stop and report.
- **What verifies the prose**, named rather than assumed: `DocumentationLinksTest` (every relative
  link resolves to a file and a heading), `DeveloperGuideExpandPromotionsExamplesTest` (the
  chapter's seventeen `./gradlew` examples — `graphCensus`, `expandPromotions`, `rate` — stay the
  exact same sequence in the exact same order; every `expandPromotions` example still parses
  through `ExpandCli.parse`; no tilde stands where `$HOME` belongs), and
  `DeveloperGuideEnumerationsTest` (run as a control that neither edited paragraph sits inside a
  table, diagram or count this class derives from the tree — it should stay unmoved).
- **Nothing here can red on the prose itself, so the task carries a positive control for each
  guard relied on.** Each control plants a defect, runs the guard that reads this file, **quotes
  the real failure text in the task report**, and removes the plant before the real edit. A
  control that did not fire has proved nothing and the task must stop and report. A compile error
  is never a red.
- **`docs` is a declared input of the `test` task** (`build.gradle.kts:150`,
  `inputs.dir("docs").withPropertyName("docs")...`), so this edit re-runs the suite. The per-task
  verification loop therefore runs **without** `--rerun-tasks`, and the report says each task
  **ran** rather than printed `UP-TO-DATE`. Only the final gate before commit adds
  `--rerun-tasks`.
- **No ADR changes.** ADR 40 already is the decision that `resolveNames` resolves one list to a
  mapping and a review file on its own run; ADR 66's 2026-09-15 amendment for #328 already is the
  decision that `--known --add` reads a mapping the seed tool wrote. Neither decision changes;
  the chapter was describing ADR 40's decision wrongly. This plan touches no file under
  `docs/adr`.
- **No commit hash anywhere under `docs/adr`. No `.superpowers/` path in any committed file. No
  qid. No figure from the owner's graph** — the run this plan cites is cited as "issue #330
  (2026-09-15)", never restated.
- **No over-claims.** The words "settles", "establishes", "proves" and "by construction" do not
  appear in any text this plan adds. What was measured is cited as measured; what follows from the
  code is cited to the code.
- **Every markdown link this plan's verbatim text adds to `docs/developer-guide.md` stays whole on
  one line, exactly as the guide already requires.** Inside this plan document itself, every
  occurrence of that same link text sits inside a fenced code block or backtick-wrapped as inline
  code — never as a live markdown link at the plan's own path
  (`docs/superpowers/plans/2026-09-15-runbook-derive-step.md`), because `DocumentationLinksTest`
  resolves a relative link against the *linking file's own directory*, and a path correct from
  `docs/developer-guide.md` is not correct from two directories deeper. This was checked by
  running `DocumentationLinksTest` with this plan committed (Step 4 below) before this plan's own
  commit lands.
- **Mikado**: the gate is green before every commit. **Stage by explicit path, git stderr
  visible — never `git add -A`, never `2>/dev/null` on `git add`.** Read `git status` before
  every commit. Commits end, after a blank line, `Co-Authored-By: Claude Fable 5.1
  <noreply@anthropic.com>`.
- Gate, **blocking, never backgrounded**, before the commit:
  `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`.
- **Only JDK 25 is installed and Gradle launches on it.** Plain `./gradlew`; never
  `/usr/libexec/java_home -v 21` (it silently returns 25).
- **Never run a writing dev task** — `expandPromotions`, `graphCensus` against a real database,
  `own`, `ownClaim`, `retractEntity`, `rate`, `evaluate`, `resolveNames`, or any seeding task.
  `~/.segue/segue.db` is never read, written, copied or created. Nothing in this plan runs any dev
  tool at all — the `resolveNames` and `expandPromotions` lines this plan writes into the guide
  are prose the owner will type later, never executed here.
- Work only in `/Users/sartin/code/segue/wt-332`, on branch `332-ready`. You are the sole
  committer there.

---

## Task 1 — the chapter's intro paragraph and step 1, corrected

**Files:** `docs/developer-guide.md`

### Step 1 — read the target section and confirm both anchors

- [ ] `grep -n "resolved them to ids\|Derive the file\|The census over it" docs/developer-guide.md`
- [ ] Confirm one occurrence of each: the intro-paragraph sentence, `**1. Derive the file.**`, and
      `**2. The census over it**` immediately after step 1's closing sentence — that ordering is
      what pins where step 1 ends.

### Step 2 — positive control: watch the order guard fire (RED)

The one guard in this repository that reads this chapter's *substance* is the example-order
check, and it is also the control that neither paragraph edit below moves a command line.

- [ ] In `docs/developer-guide.md`, temporarily delete this line from the `--known --add` section
      (leave its fence):

```
./gradlew rate --args="--known $HOME/rejected.csv --db $HOME/.segue/segue.db"
```

- [ ] Run, blocking: `./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest'`
- [ ] **Observe a real assertion failure** from
      `shouldRunEveryStepInOrderWhenTheChapterIsRead` — an AssertJ `containsExactly` diff whose
      actual list is missing the trailing `rate --known`. **Quote the actual diff in the task
      report.** If it passes, this guard is not reading the section this task edits; stop and
      report.
- [ ] Restore the deleted line exactly. Re-run the same command and see it green.

### Step 3 — positive control: watch the link guard fire (RED)

- [ ] In `docs/developer-guide.md`, inside the `--known --add` section's step 1 (anywhere between
      `**1. Derive the file.**` and the following `**2. The census over it**`), temporarily insert
      this line on its own:

```
See [ADR 999](adr/0999-does-not-exist.md) for nothing.
```

- [ ] Run, blocking: `./gradlew test --tests '*DocumentationLinksTest'`
- [ ] **Observe a real assertion failure** from
      `shouldResolveEveryTargetWhenTheDocumentationLinksAreFollowed`, naming
      `developer-guide.md` and `missing file docs/adr/0999-does-not-exist.md`. **Quote it in the
      report.** If it passes, this guard is not reading this section; stop and report.
- [ ] Remove the inserted line. Re-run the same command and see it green. This is the control that
      says the one new link Step 5 below adds is actually checked.

### Step 4 — the intro paragraph, one sentence (GREEN)

- [ ] Replace exactly this text in `docs/developer-guide.md`:

```
The three chapters above all expand something the graph already holds. This one covers the
entities it does not: the rows the original names list carried that Setlist Scout rejected as
non-touring — the authors, thinkers and comedians `seed.SeedRow`'s note on `status` calls the
relations this graph is short of. The seed tool resolved them to ids in the same mapping as the
touring acts ([ADR 40](adr/0040-bulk-seeding-as-a-dev-tool.md)) and stopped there, because
nothing in this repository adds entities in bulk. `--add`, given beside `--known`, adds an id the
file names that the graph holds no node for — the same fetch-and-record the `add_entity` MCP tool
does, through the shared `expansion.EntityAddition` — and then expands it, in the same pass and
in the file's order. Without `--add` that id is refused as an unknown entity, exactly as it
always has been.
```

with:

```
The three chapters above all expand something the graph already holds. This one covers the
entities it does not: the rows the original names list carried that Setlist Scout rejected as
non-touring — the authors, thinkers and comedians `seed.SeedRow`'s note on `status` calls the
relations this graph is short of. The seed tool resolves each of those rows to an id of its own,
on a run over just the rows this chapter wants
([ADR 40](adr/0040-bulk-seeding-as-a-dev-tool.md)), and stops there, because nothing in this
repository adds entities in bulk. `--add`, given beside `--known`, adds an id the file names that
the graph holds no node for — the same fetch-and-record the `add_entity` MCP tool does, through
the shared `expansion.EntityAddition` — and then expands it, in the same pass and in the file's
order. Without `--add` that id is refused as an unknown entity, exactly as it always has been.
```

### Step 5 — step 1, "Derive the file." (GREEN)

- [ ] Replace exactly this text in `docs/developer-guide.md`. **Both blocks below are fenced with
      four backticks on purpose**, because each one contains its own three-backtick `bash` example
      block; a three-backtick outer fence would close at that inner fence instead of at the real
      end, which is exactly the trap that would leave the `[ADR 33]`/`[Bulk seeding]` links below
      it unprotected from this plan's own `DocumentationLinksTest` run (see Global Constraints):

````
**1. Derive the file.** The mapping the seed tool wrote has a `status` column, and the rejected
rows are the ones you want. `SeedFiles` quotes a field only when it holds a comma, a quote or a
newline, so `status` is never quoted and always sits between two commas — but a **name** with a
comma in it is quoted, which shifts the columns of that row. Match the literal field, not the
third comma-separated one, and write the result outside the working tree:

```bash
grep ',REJECTED,' "$HOME/names-resolved.csv" > "$HOME/rejected.csv"
```

`QidList` reads that file exactly as it reads the mapping — the first comma-separated field that
is exactly a qid — so no reshaping is needed and the review rows, which carry no qid of their
own, are passed over. **The file is personal data**: a list of who someone reads and watches is
what [ADR 33](adr/0033-taste-layer-separation.md) governs, `*.csv` is gitignored beside `*.db`,
and the protection is where the file lives rather than what git ignores (issue #37).
````

with:

````
**1. Derive the file.** The list is whatever names the owner holds for the domain, in the seed
tool's own `name,kind,status` shape ([Bulk seeding](#bulk-seeding)). If it comes from a Setlist
Scout export, reshape it first: the export's `category` column is the seed tool's `kind`, and only
the rows whose `status` is the one you want belong in the reshaped file — `REJECTED` for the
non-touring names this chapter is about. **A Setlist Scout export carries no id**, so nothing
about it is a qid `QidList` or `--known` can read yet; resolving it is what this step is for.

```bash
./gradlew resolveNames --args="--list $HOME/rejected-names.csv --mapping $HOME/rejected.csv"
```

The run writes the mapping at `--mapping` and, beside the *list* rather than the mapping, a
review file, plus a summary in the log, exactly as the seed tool always does. **Read the review
file before anything past this step**: it is names, never pasted here, and each line is accepted
or corrected by hand — only what lands in the mapping is what the rest of this chapter reads.
`QidList` reads the mapping exactly as it reads a bare list — the first comma-separated field
that is exactly a qid.

**Why this is not a grep over an already-resolved mapping.** If the touring and non-touring rows
had been resolved together in one run, the mapping's own `status`-`REJECTED` rows would already
carry ids, and `QidList` reads the first field that is exactly a qid regardless of `status` — so
a row like that is already on the known population the moment the mapping exists, with nothing
left here to derive. What makes this a step at all is filtering *before* the resolution run: an
export naming no id names nothing `QidList` can read, and `resolveNames` is what supplies one.
**The file is personal data**: a list of who someone reads and watches is what
[ADR 33](adr/0033-taste-layer-separation.md) governs, `*.csv` is gitignored beside `*.db`, and
the protection is where the file lives rather than what git ignores (issue #37).
````

- [ ] Confirm the `**2. The census over it**` heading that follows is unedited and still names
      `$HOME/rejected.csv` — the new step 1 writes its mapping to that same path, so nothing past
      this point needs to change.

### Step 6 — what in the tree makes each new sentence true

- [ ] Confirm each, by reading the named file, and record the check in the task report:

| sentence | what makes it true |
| --- | --- |
| "The seed tool resolves each of those rows to an id of its own, on a run over just the rows this chapter wants" | `seed.SeedRun.run` takes one `List<SeedRow>` and resolves it in one pass; nothing in `seed` merges two runs' outputs. `seed.SeedRow`'s javadoc: "`status` is carried through untouched and never filtered on" |
| "The list is whatever names the owner holds for the domain, in the seed tool's own `name,kind,status` shape" | `seed.SeedFiles.INPUT_HEADER = "name,kind,status"`; `SeedFiles.readList` throws if the file does not start with that header |
| "the export's `category` column is the seed tool's `kind`" | issue #330's own account of the run, cited by number |
| "only the rows whose `status` is the one you want belong in the reshaped file" | `seed.SeedRow`'s javadoc: "REJECTED means 'does not tour' ... the rejected rows are disproportionately the authors and thinkers whose relations this graph is short of" |
| "A Setlist Scout export carries no id" | issue #332's own premise, and issue #330's account of the run (reshape, then resolve) |
| the `resolveNames --args="--list … --mapping …"` command line | `seed.SeedCli.parse`: `case "--list" -> list = Path.of(value);` and `case "--mapping" -> mapping = Path.of(value);`, looped generically over flag/value pairs |
| "a review file, plus a summary in the log, exactly as the seed tool always does" | `seed.SeedCli.parse`: `review == null ? sibling(list, "-review.csv") : review` — a sibling of the *list*; `seed.SeedCli.main` logs `summary.lines()`, `"mapping: {}"` and `"review:  {}"` |
| "`QidList` reads the mapping exactly as it reads a bare list — the first comma-separated field that is exactly a qid" | `support.QidList.read`: `for (String field : line.split(",")) { ... if (QID.matcher(candidate).matches()) { qids.add(candidate); break; } }`, with no header handling |
| "the mapping's own `status`-`REJECTED` rows would already carry ids, and `QidList` reads the first field that is exactly a qid regardless of `status`" | `seed.SeedFiles.OUTPUT_HEADER = "name,kind,status,qid,label,confidence,reason"` — `status` and `qid` are two different columns, and `QidList.read` never reads `status` at all |
| "an export naming no id names nothing `QidList` can read" | `QidList.read` throws `"no QID in " + path + ...` when the file has no field matching `Q\d+` |
| link `[Bulk seeding](#bulk-seeding)` | `docs/developer-guide.md` line 1332, `## Bulk seeding`; this test's own Step 3 proved the guard reads a link added in this section |
| link `[ADR 33](adr/0033-taste-layer-separation.md)`, carried over unedited | already resolves before this task (`docs/adr/0033-taste-layer-separation.md` exists); Step 3's control is what proves the guard reads this region, not this specific link |
| link `[ADR 40](adr/0040-bulk-seeding-as-a-dev-tool.md)`, carried over unedited | same file, same reasoning |

### Step 7 — verify (GREEN) and commit

- [ ] Blocking, without `--rerun-tasks`:
      `./gradlew test --tests '*DeveloperGuideExpandPromotionsExamplesTest' --tests '*DocumentationLinksTest' --tests '*DeveloperGuideEnumerationsTest'`
- [ ] Confirm every task **ran** rather than printed `UP-TO-DATE`, and that the order check and the
      link check both passed — that pair is the control that the prose edits changed no command
      and broke no link.
- [ ] Blocking: `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks`
- [ ] `git status`, then `git add docs/developer-guide.md` (stderr visible), then commit:

```
Runbook: the --known --add chapter's derive step needs a resolution run first (#332)

The chapter assumed a resolved mapping already existed and told the owner to
grep it for the non-touring rows. The owner's real list is a Setlist Scout
export with a status column and no ids at all: the first real run (#330)
reshaped and filtered the export, then resolved it, before this chapter's
own census could read anything. Step 1 and the intro paragraph's one wrong
sentence are corrected to match; steps 2 onward are untouched, because the
new resolveNames example writes its mapping to the same path they already
name.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

---

## Done when

- [ ] One commit on `332-ready` (this task), green at the gate.
- [ ] `git diff main...332-ready --stat` shows one file: `docs/developer-guide.md`.
- [ ] No commit hash, `.superpowers/` path, qid or graph figure in anything committed.
- [ ] The task report quotes the real failure text of both positive controls (Steps 2 and 3).
- [ ] The spec's two out-of-scope findings — the `QidList` class-javadoc discrepancy against
      `seed.Adjudicator.decide`'s `REVIEW` returns, and the decision to show the reshape step as
      prose rather than a command — are named in the final report to the caller, not silently
      dropped.
