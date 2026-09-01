# The ADR index is machine-checked

Issue #170. Written 2026-09-01.

## What is wrong

`docs/adr/README.md` is the entry point to every decision this project has made, and it is
maintained by hand, append-at-tail, with nothing in the build reading it. Three branches appended
to it at once in one week and an entry was silently dropped three separate times, by three
different mechanisms; each loss was caught only by a person counting afterwards. An ADR that exists
on disk but not in the index is a decision nobody finds, and the failure is silent in both
directions — a dropped row breaks no link and fails no test.

This is the anatomy this repo keeps closing: **an operation that appears to have worked and quietly
had no effect.** A merge that reports "resolved" and loses a row is exactly that.

## A correction to the issue

The issue says the index is not ordered, citing ADR 34 "between 11 and 12". **That misreads the
file.** The index is grouped into sections the adr-toolkit baseline created (#1): Universal 1–8,
Language 9 10 11 34, Framework 12 13, App shape 14, Concern 15 16, Interaction 17, and everything
from 18 onward in Uncategorized. Within every section the numbers already ascend; 34 follows 11
because Language *ends* there. Whether "JVM quality gates" belongs in Language is a taxonomy
question, out of scope here, and ADR 34 is **not** moved by this work.

The invariant worth enforcing is therefore **ascending within each section** — and that is where the
real hazard lives. Every new ADR lands in Uncategorized, and a conflict resolution that keeps both
sides in marker order interleaves numbers there, which is precisely what #160 needed and would have
got wrong.

## The decision

A test, `AdrIndexTest` in `com.robsartin.segue.arch` beside `DeveloperGuideEnumerationsTest`, that
reads `docs/adr/` and `docs/adr/README.md` and asserts, each as its own test method:

1. **Every `NNNN-*.md` has exactly one index row.** Catches a dropped row.
2. **Every index row names a file that exists.** Catches a row that outlived its file, or a typo.
3. **No number is claimed twice**, in the index or on disk. Catches the 0055 collision without a
   human coordinating.
4. **Within each section, rows ascend by number.** Catches marker-order interleaving.
5. **Each row agrees with its file on three fields the hand edits drift on**: the number in the
   row equals the number in the filename equals the number in the file's `# N. Title` heading; the
   title in the row equals the heading's title; the `_Status_` in the row equals the front matter's
   `status:`. The index has a status column that nothing checks; a superseded ADR still reading
   `_Accepted_` in the index is the drift this catches.

Assertions 1–4 are the issue's. Assertion 5 is added because it is derivable and cheap, and because
the front matter and heading already exist — the index is a projection of them, and a projection
that nothing compares to its source is the shape ADR 19 warns about for data.

**The row format is load-bearing, and the test names the shape it parses**, exactly as
`DeveloperGuideEnumerationsTest` does: a row is `- [N. Title](NNNN-slug.md) — _Status_`; a section
is a `## ` heading; the description and `Related:` lines beneath a row are not parsed and not
asserted. An index edit that keeps the facts but changes the shape fails the test rather than
passing silently, which is the safe direction.

## The build must actually run it

`build.gradle.kts` declares `docs/developer-guide.md` as a test input because, measured rather than
assumed, an edit to a file Gradle does not know about leaves `test UP-TO-DATE` and the guard proves
nothing. `docs/adr/` is not declared. **Without `inputs.dir("docs/adr")`, this test would report
green on exactly the commits it exists to check.** That declaration is part of the change, carries
the same comment, and has its own positive control: edit only the README, run `check`, and confirm
`test` executed rather than reporting up-to-date.

## Positive controls — definition of done

A guard never seen to fail has never been tested (#93, #139). Each assertion is driven red on a
planted defect, the message quoted, and the plant reverted:

- delete a row → assertion 1 red, naming the file;
- add a row for a file that does not exist → assertion 2 red, naming the row;
- duplicate a number → assertion 3 red, naming the number;
- swap two adjacent rows inside Uncategorized → assertion 4 red, naming the section and the pair;
- change a row's `_Accepted_` to `_Superseded_` → assertion 5 red, naming the field;
- edit only the README and run `check` → `test` executes (the `inputs.dir` control).

## Shared code

`repositoryRoot()` and `read(Path)` are private to `DeveloperGuideEnumerationsTest`. A second
consumer exists now, so they move to a package-private helper both tests call rather than being
copied — the second copy of a rule is what a future editor misses.

## Recorded

ADR 1 governs ADRs and their index. It gains a **dated amendment** (never an edit) saying the index
is machine-checked, naming the test as the authority for what the index must contain — in the shape
ADR 32 uses to disclaim its own rule table — and recording the section-order correction above so
the next reader does not re-file the issue's misreading.

## Rejected

- **A global ascending-order assertion**, as the issue proposed. It would fail today on a correctly
  sectioned index and force either flattening the sections or moving ADR 34; both are taxonomy
  changes the test has no business making.
- **Generating the index from the front matter** instead of checking it. Better in principle — a
  generated projection cannot drift — but it changes how every ADR is added, needs a script the
  repository does not have, and the check gets 90% of the value with none of the workflow change.
  If drift keeps appearing after the check exists, that is the time to generate.
- **Checking the description and `Related:` lines.** Prose; the same reason
  `DeveloperGuideEnumerationsTest` declines to check the guide's prose.

## Correction (2026-09-01, during Task 1's review) — the index has a generator, and it would not reproduce today's file

"Generating the index … needs a script the repository does not have" was wrong in one respect and
right in a stronger one. The adr-toolkit that scaffolded `docs/adr/` (#1) carries `build_index`,
which produced this file's exact shape at baseline: groups in a fixed axis order (project,
universal, language, framework, app-shape, ui-tech, library, concern, interaction), ADRs in
**filename order within a group** — the very invariant assertion 4 enforces — and an
`Uncategorized` bucket for anything without a recognised axis, "so nothing silently vanishes".

Its rule for the axis is `tags[0]`. The seventeen baseline ADRs carry an axis word first
(`universal`, `language`, …), which is how the sections came to be. **The index has not been
regenerated since**: every ADR from 18 on was appended by hand into Uncategorized, and **42 of
those 43 carry `project` as their first tag** — the axis `build_index` renders first, as a
"Project" section the hand-maintained file does not have — so regenerating today would move all
42 out of Uncategorized. The 43rd is ADR 34, whose first tag is `language`, which is why a hand
filed it under Language: the tag, not an accident. The
generator lives outside this repository, would have to be vendored, and would re-file entries as
a side effect of checking them.

The decision stands, for a sharper reason: **the test asserts the file as it is maintained, not as
the generator would render it.** What the generator settles is the authority for the section set:
every `## ` heading must be one of the toolkit's display names or `Uncategorized`, which the test
may assert citing `adr_toolkit.index._AXIS_DISPLAY_NAMES` rather than a hand-written list. Whether
to regenerate — and so re-file — is a taxonomy decision for a later issue.
