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
