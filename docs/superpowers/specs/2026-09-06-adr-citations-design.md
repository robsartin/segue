# Design: a guard for commit-hash citations in `docs/adr/` (issue #274)

Date: 2026-09-06. Branch `274-ready`, off `main` at `7e2651c`.

## The problem, as the code has it

`docs/adr/` cites commit hashes as evidence of ordering — "the rule was committed as `…` before
the reading existed". This repository squash-merges, so a branch commit is rewritten into a single
commit on `main` and the original object is never in `main`'s history. In a fresh clone those
hashes resolve to nothing.

Measured on 2026-09-06 against `main` at `7e2651c`:

```
grep -rhoE '`[0-9a-f]{7,40}`' docs/adr | sort -u
```

returns **thirteen distinct hashes** across **fourteen (file, hash) pairs** — `a7c3455` is cited in
two files. A case-insensitive sweep of the whole `docs/` tree returns exactly the same set: there
are no uppercase-hex citations, and **zero false positives** — no backticked decimal run, count or
identifier of seven or more characters anywhere under `docs/`.

Seven of the thirteen are unreachable from `main` (`git merge-base --is-ancestor <sha> 7e2651c`).

## Two places where the issue's premise did not survive checking

The issue says "GitHub still serves every one of them through the pull request that carried it".
Resolved with `gh api repos/robsartin/segue/commits/<sha>` and `.../pulls`, that is false twice:

1. **`fdd420d` (ADR 59) is not on GitHub at all.** `gh api repos/robsartin/segue/commits/fdd420d`
   answers `422 No commit found for SHA: fdd420d`, and
   `gh api "search/commits?q=repo:robsartin/segue+hash:fdd420d"` reports `total_count: 0`. The
   object exists only in this working clone (`git cat-file -t` says `commit`;
   `git rev-parse` expands it to `fdd420df4723803afc768ee07f442d643284038b`, subject
   `#221: a merge the owner corrected retires its stand-in and its carry`, committer date
   2026-09-03T15:49:26-05:00). No branch contains it. It was never pushed. Its work landed on
   `main` as `0783492` through PR #226.
2. **`74e757f` (ADR 45) is served but is associated with no pull request.**
   `gh api repos/robsartin/segue/commits/74e757f` answers (committer date 2026-09-05T00:58:30Z,
   subject `Write the rule for the second evaluation reading before it is taken (#245)`), but
   `.../pulls` returns an empty array — the branch was force-pushed when the commit was rebased,
   and the association went with it. PR #267 is the pull request that carried the work.

Also: the numbers the issue puts in parentheses (`#242`, `#245`, `#270`, `#272`) are **issue**
numbers, not pull requests. The pull requests are one or more ahead. The amendment must carry the
pull-request numbers, resolved below.

## The resolved table (transcribe, do not re-look-up)

`.commit.committer.date` is the time GitHub records for the object; the pull request is
`.../pulls[0].number`.

| Hash | What it witnessed | Pull request | Push time (UTC) |
|---|---|---|---|
| `0a29f45` | ADR 41's index row regaining its backticks (issue #170) | PR #191 | 2026-09-01 22:21:25 |
| `9937f86` | the calibration rule, written before the first reading (issue #242) | PR #243 | 2026-09-04 23:56:02 |
| `74e757f` | the second reading's rule, first push (issue #245) | PR #267, no longer associated | 2026-09-05 00:58:30 |
| `33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba` | the same rule, rebased (issue #245) | PR #267 | 2026-09-06 15:25:48 |
| `e0e398b` | the folded-table note (issue #270) | PR #271 | 2026-09-06 17:57:42 |
| `3c2171e` | the ratings-moved note (issue #272) | PR #273 | 2026-09-06 20:47:30 |
| `fdd420d` | the widened stand-in rule, reproduced in a fix-round review (issue #221) | none — never pushed; the work landed as `0783492` through PR #226 | not on GitHub; local committer date 2026-09-03 20:49:26 |

The six reachable ones need no resolution and are not in the amendment's table: `0783492` (PR #226),
`a7c3455` (PR #231), `2e01341` (PR #219), `a79c6ca` (PR #235), `fd88813` (PR #236), `cd1d8dc`
(PR #160).

## What is built

### 1. One dated amendment to ADR 1

ADR 1 is the ADR about recording decisions, so the resolution lives there once rather than in each
cited ADR. It carries the table above (the seven unreachable), states the rule from here on — an
amendment proves ordering with the **pull request number and the push time**, and a commit hash only
alongside them — and records why the earlier citations are left as they are: ADRs are immutable
(this ADR's own Decision), and the evidence still exists everywhere but `fdd420d`.

**The table is not a mirror of the test.** The amendment holds hash → pull request → push time,
which lives nowhere else in the tree. `AdrCitationsTest` holds hash → file. Neither restates the
other, so neither is a drift generator. The amendment says so out loud, the way the 2026-09-01
amendment says `AdrIndexTest` is the list.

### 2. One sentence in the developer guide

At the end of `## How to read an ADR against the code` (docs/developer-guide.md), the section that
already tells a reader an ADR is amended, dated, saying what it corrects. No new heading, so the
Contents list is untouched, and `DeveloperGuideEnumerationsTest` does not parse this section.

### 3. `AdrCitationsTest` in `src/test/java/com/robsartin/segue/arch/`

Locating the docs the way `AdrIndexTest` and `DocumentationLinksTest` already do:
`RepositoryTree.root().resolve("docs/adr")` and `RepositoryTree.read(path)`, iterating with
`Files.list` inside try-with-resources. No second way of finding the tree.

`build.gradle.kts` already declares `inputs.dir("docs")` on `test` (line ~150), so an ADR-only
commit re-runs this suite. Nothing to add there; the guard would otherwise be stale-green exactly
as `AdrIndexTest` was before #170.

#### The regex

```java
private static final Pattern CITATION = Pattern.compile("`([0-9a-fA-F]{7,40})`");
```

Settled points:

- **The backticks are part of the pattern**, not a word boundary. A hash must be inside a code span
  to be a citation; `git merge-base --is-ancestor 7e2651c` written as bare prose is not one. This is
  what keeps issue numbers (`#274` — never backticked, and `#` is not hex) and ADR numbers (`45`,
  two characters, below the floor) out.
- **ADR filenames do not match.** `` `0045-recommend-by-normalised-lift-with-routes.md` `` has four
  hex characters and then a hyphen, so the closing backtick the pattern needs is never adjacent.
- **`` `main` ``, `` `seed` ``, `` `NodeKind` ``, `` `INFLUENCED_BY` `` do not match** — `m`, `s`,
  `k`, `n`, `_` are not hex.
- **A backticked `deadbeef` in prose *does* match, deliberately.** Eight hex characters in a code
  span is indistinguishable from a short hash, and a guard that guessed at the difference would be
  the lenient matcher this repository refuses (`DocumentationLinksTest`'s "a shape it cannot read
  fails loudly"). Today the corpus has none; if one is ever written, the guard names it and the
  author either allowlists it or drops the backticks. The planted control in test 1 uses exactly
  this token, so the behaviour is committed rather than assumed.
- **A pure-decimal token of 7–40 digits would match too** (a CI run id such as `33655745937`, if it
  were ever backticked in an ADR). Rejected alternative: require at least one `a`–`f`. That lets an
  all-digit commit hash through in silence, which is the failure this guard exists to prevent;
  a noisy false positive with a message naming the file and the token is the cheaper error. The
  sweep above shows there are none today.
- **Case-insensitive.** Rejected alternative: lowercase only. Git writes lowercase, but an uppercase
  citation pasted from elsewhere would then pass unseen — the lenient matcher again. Measured: zero
  uppercase matches under `docs/` today, and no backticked all-caps identifier in the tree is made
  only of `A`–`F` and digits.
- **Fenced code blocks are not stripped**, unlike `DocumentationLinksTest`. A hash cited inside a
  shell transcript goes stale the same way; there are none today.

#### What is scanned

Every `*.md` in `docs/adr`, `README.md` included — a hash pasted into the index is a citation too.
`AdrIndexTest`'s narrower `^(\d{4})-[a-z0-9-]+\.md$` filter is deliberately not reused: it exists
there to exclude the index, which is precisely what must not be excluded here.

#### The allowlist, and what the guard asserts

The allowlist is a set of **(file, hash) pairs**, not a set of hashes, split into named lists whose
names carry the meaning:

- `REACHABLE_FROM_MAIN` — the six `main` can reach.
- `UNREACHABLE_RESOLVED_BY_ADR_1` — the seven the ADR 1 amendment resolves, in the ADRs that cite
  them.
- `IN_THE_ADR_1_AMENDMENT` — the pairs the amendment itself adds (task 2).

Rejected alternative: a flat `Set<String>` of hashes with the file in a comment. It cannot say
*where* a hash may appear, so moving a citation into a new ADR — or the ADR 1 amendment re-citing
six of them — would pass silently, and the comment would rot unchecked.

The assertion is **exact equality between the pairs found and the pairs allowlisted**, sorted by
file then hash, with AssertJ's `containsExactlyElementsOf`. That settles the open question directly:
**yes, the allowlist also asserts that every allowlisted pair still appears.** A stale entry — a
citation deleted from an ADR, or an ADR renamed — is a red, not a quiet pass. The cost is that any
new citation, including one the ADR 1 amendment itself adds, requires a deliberate allowlist edit.
That is the point: the rule from here on is to stop citing bare hashes, and the guard makes each
exception a decision somebody typed.

Duplicate citations of the same hash in the same file (`33a0dd…` appears three times in ADR 45,
`0783492` twice in ADR 44) collapse to one pair.

#### The seam, and the RED that cannot leave a dirty ADR

The scan is split into a pure function over text:

```java
static List<String> hashesIn(String markdown)
```

and a directory walk that pairs its output with each file name. Both tests below need
`hashesIn`, so the seam is a real need, not speculative structure.

**The RED is planted in a Java string literal, never in an ADR file.** The rejected alternatives:

- *Plant a citation in a real ADR and restore it afterwards.* If the run is interrupted — or the
  implementer's `git add <path>` catches the file — a falsified ADR is committed. This repository
  stages by explicit path precisely because a stray file is easy to sweep in.
- *Copy the ADRs into a `@TempDir` and plant there.* Safe, but it needs a directory parameter on the
  scan and a filesystem for something that is a text question.

A string literal has neither risk and neither cost. Three loops, each with a real observed failure:

1. **The planted citation.** `hashesIn` stubbed to `List.of()`, so the first test reds on an
   assertion (`expected to contain deadbeef, but was []`), not a compile error. Then the regex.
2. **The false-positive control.** A string of near-misses — a two-character `` `45` ``, a bare
   `#274`, `` `main` ``, an ADR filename, an unbackticked hash, a six-character token, a
   forty-one-character token — must yield nothing. Green on arrival against a correct regex, so its
   red is proved by a **planted defect in the regex** (`{7,40}` widened to `{2,40}`), observed
   firing on `` `45` ``, then restored. Written out as steps.
3. **The corpus.** Written with an **empty allowlist**, so the first run reds enumerating all
   fourteen real pairs — a real failure, for the right reason, that also proves the scan reaches
   every file. Filling in the fourteen is the minimum change that makes it pass.

**The planted control on the allowlist** (required by the brief): after green, delete the
`0058-stand-in-identifiers-cannot-be-allocatable.md` / `cd1d8dc` entry, run, observe the test name
exactly that pair as missing, restore, re-run green. It touches only the test file.

## Task order, and how the prose task is verified

1. The guard, RED → GREEN over today's corpus (fourteen pairs, thirteen hashes), plus both controls.
2. The ADR 1 amendment and the guide sentence. **No unit test, said out loud:** this is prose, and
   there is no behaviour to assert. It is verified by three things instead, each named in the plan
   with its expected output — (a) the guard from task 1 reds when the amendment lands, naming the
   seven new pairs it did not expect (`9937f86`, `74e757f`, `33a0dd…`, `e0e398b`, `3c2171e`,
   `fdd420d`, `0783492`, `7e2651c` in `0001-record-architecture-decisions.md` — eight pairs; the
   amendment's own `0a29f45` is already allowlisted from this ADR's 2026-09-01 amendment), and
   goes green only once they are added under `IN_THE_ADR_1_AMENDMENT`; (b) `DocumentationLinksTest`
   resolves the guide's new relative link; (c) `AdrIndexTest` confirms the ADR 1 row still agrees
   with the file. The mid-task red is the verification, and it is observed, not assumed.
3. The full gate.

Green at every committed step: task 2's allowlist edit ships in task 2's commit, not later.

## Out of scope (from the issue)

Rewriting any existing amendment. Changing the merge workflow. ADR 1's original Decision is not
edited — the amendment is appended, as its own Decision requires.
