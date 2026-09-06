---
status: Accepted
date: "2026-08-23"
topic: record-architecture-decisions
tags: [universal, adr, process]
supersedes: []
related: [keep-documentation-current]
---
# 1. Record architecture decisions with ADRs

## Context

Architecturally significant decisions — choices that shape structure, dependencies,
interfaces, or the way the team works — need a durable record. Without one, the *why*
behind a decision is lost: newcomers can't tell intent from accident, and past reasoning
gets re-litigated or silently reversed.

## Decision

We record architecturally significant decisions as **Architecture Decision Records
(ADRs)**, following Michael Nygard's lightweight convention.

- ADRs live in `docs/adr/`, one Markdown file per decision, named
  `NNNN-kebab-case-title.md` and numbered sequentially.
- Each ADR has a **Date**, a **Status** (`Proposed`, `Accepted`, `Deprecated`, or
  `Superseded by [NNNN](...)`), and the sections **Context**, **Decision**, and
  **Consequences**.
- ADRs are **immutable once Accepted.** A decision that changes is not edited; a new ADR
  supersedes it, and the old one's status is updated to point at its successor. This
  preserves a truthful history of what was decided and when.
- Reserve ADRs for decisions that are costly to reverse or that a future reader would
  otherwise find surprising; trivial choices don't need one.

## Alternatives considered

- **Commit messages and PR descriptions as the record** — the reasoning exists somewhere,
  but it isn't browsable by topic and gets buried as history grows.
- **A wiki or external docs tool** — lives apart from the code, so it drifts and goes stale
  instead of shipping in the same PR as the decision it documents.
- **Editing a decision in place when it changes** — overwrites the original reasoning,
  losing the truthful timeline of what was decided and when.

## Consequences

- The reasoning behind significant choices is preserved and discoverable next to the code.
- Superseding rather than editing keeps a truthful timeline at the cost of some duplication.
- Contributors must judge when a decision is significant enough to warrant an ADR.

**Amendment (2026-09-01, issue #170): the index is now machine-checked, and `AdrIndexTest` is the
authority for what it must contain.**

`AdrIndexTest` (`src/test/java/com/robsartin/segue/arch/AdrIndexTest.java`) reads `docs/adr/` and
`docs/adr/README.md` on every build. As with ADR 32's rule table, this ADR does not restate what
its methods check — the test is the list, not this ADR. Its methods:
`shouldGiveEveryAdrFileExactlyOneRowWhenTheIndexIsParsed`,
`shouldNameOnlyExistingFilesWhenTheIndexIsParsed`,
`shouldClaimEachNumberOnceWhenTheIndexAndTheDirectoryAreRead`,
`shouldAscendByNumberWithinASectionWhenTheRowsAreGrouped`, and
`shouldAgreeWithTheFileOnEveryFieldWhenARowIsComparedToIt`.

Issue #170 read the index as unordered, citing ADR 34 sitting between 11 and 12. That misread a
sectioned index — see `shouldAscendByNumberWithinASectionWhenTheRowsAreGrouped`. ADR 34 stays where
it is: its first tag is `language`, the axis a row's section is placed by, so its position is by
rule, not by accident.

The day it landed, the test caught real drift: ADR 41's index row had dropped the backticks its own
heading carries around `seed`, fixed in `0a29f45`.

The index's shape — its sections, and the order within them — comes from the adr-toolkit that
scaffolded `docs/adr/` at this project's baseline: `build_index` groups ADRs by axis, the axis is
each ADR's first tag, sections render in a fixed axis order, and anything without a recognised axis
falls to `Uncategorized` so nothing silently vanishes. This file has not been regenerated since
that baseline. Every ADR from 18 on was appended by hand into `Uncategorized`, and 42 of those 43
carry `project` as their first tag — the axis `build_index` renders first, as a Project section this
file has never had. Regenerating today would move all 42. So `AdrIndexTest` asserts the file as it
is maintained, not as the generator would render it; whether to regenerate, and re-file, is a
taxonomy decision left to a later issue.

What the test deliberately does not check: the description and `Related:` prose beneath each row,
and the section names themselves — those display names are the toolkit's `_AXIS_DISPLAY_NAMES` to
own, not a list for this ADR to hand-copy into a second source (issue #190).

**Amendment (2026-09-06, issue #274): under a squash merge a commit hash is not an ordering witness.
An amendment cites the pull request and the push time; `AdrCitationsTest` is the authority for which
hashes may appear in `docs/adr/` at all.**

This repository squash-merges. A branch commit is rewritten into one commit on `main` and the
original object is never in `main`'s history, so a hash an amendment cited as evidence of ordering —
"the rule was committed as … before the reading existed" — resolves to nothing in a fresh clone.
Read on 2026-09-06 against `main` at `7e2651c` (the squash-merge commit of pull request #273, a
commit on `main` and therefore permanent in a way a branch commit is not), seven of the thirteen
hashes cited across `docs/adr/` were unreachable. They are resolved here, once, so a reader of any of those ADRs has one place to look.

| Hash | What it witnessed | Pull request | Committer date (UTC), as GitHub records it |
|---|---|---|---|
| `0a29f45` | ADR 41's index row regaining the backticks its heading carries (issue #170) — cited in this ADR's 2026-09-01 amendment | PR #191 | 2026-09-01 22:21:25 |
| `9937f86` | the calibration rule, written before the first reading was taken (issue #242) — ADR 45 | PR #243 | 2026-09-04 23:56:02 |
| `74e757f` | the second reading's rule, first push (issue #245) — ADR 45 | PR #267, which no longer lists it: the branch was force-pushed when the commit was rebased, and the association went with it | 2026-09-05 00:58:30 |
| `33a0dd63c5fca13efc5cfdd38ecac6dab67df4ba` | the same rule, rebased (issue #245) — ADR 45 | PR #267 | 2026-09-06 15:25:48 |
| `e0e398b` | the folded-table note (issue #270) — ADR 45 | PR #271 | 2026-09-06 17:57:42 |
| `3c2171e` | the ratings-moved note (issue #272) — ADR 45 | PR #273 | 2026-09-06 20:47:30 |
| `fdd420d` | the widened stand-in rule, reproduced in a fix-round review (issue #221) — ADR 59 | **none.** It was never pushed; the work landed on `main` as `0783492` through PR #226 | not on GitHub; committer date on the machine it was made on, 2026-09-03 20:49:26 |

**One of the seven is worse than unreachable.** `fdd420d` is not on GitHub either:
`gh api repos/robsartin/segue/commits/fdd420d` answers `422 No commit found for SHA`, and a commit
search over this repository returns nothing. It exists only in the working clone it was made in, so
ADR 59's citation of it is evidence nobody else can open. The reproduction it witnesses is described
in full in ADR 59's own prose, which is the part that survives; the hash is not, and this is the
clearest possible statement of why the rule below exists.

**The rule from here on.** An amendment that needs to prove *when* something was decided cites **the
pull request number and the push time** (the committer date GitHub records for the commit). Both survive the squash, both are readable by anyone with
the repository, and together they order an amendment against anything else dated. A commit hash may
appear **only alongside them**, as a convenience for a reader who has the object — never alone, and
never as the sole witness.

**Why the earlier citations are left as they are.** ADRs are immutable; that is this ADR's own
Decision, and it does not have an exception for citations that turned out to be weak. The lines
carrying these thirteen hashes are not edited and not deleted. Nor is the evidence gone: six of the
thirteen are reachable from `main` today (`0783492`, `a7c3455`, `2e01341`, `a79c6ca`, `fd88813`,
`cd1d8dc`), and six of the seven unreachable ones are still served by GitHub's commits API. Only
`fdd420d` is beyond reach, and this amendment says so rather than leaving a reader to discover it.

**What checks this.** `AdrCitationsTest`
(`src/test/java/com/robsartin/segue/arch/AdrCitationsTest.java`) reads every `*.md` in `docs/adr/` on
every build and fails on any backticked hexadecimal run of seven to forty characters that it does not
already name, together with the file it is cited in. As with `AdrIndexTest` above, this ADR does not
restate that list — the test is the list. Nor does the test restate this table: it holds hash and
file, this table holds hash, pull request and push time, and neither is a copy of the other to go
stale against.
