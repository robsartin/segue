# Every documentation link resolves, and a test says so

Issue #168. Written 2026-09-02.

## The defect, measured

Nothing in the repository checks that a documentation link resolves. `DeveloperGuideEnumerationsTest`
checks the guide's enumerations against the code, `AdrIndexTest` checks the index's rows against the
ADR files, and the new examples tests parse the runbooks' commands — but a link to a filename that
does not exist passes every gate. It is the documentation twin of an ArchUnit fence naming no class.

A sweep of `README.md` and every `docs/**/*.md` on 2026-09-02, skipping links inside fenced blocks and
inline code:

| relative links | broken | where |
|---|---|---|
| 739 | 2 | `docs/developer-guide.md` lines 343 and 459, both `adr/0042-store-p31-and-rederive-kind.md`; the file is `0042-store-p31-and-rederive-kind-at-projection.md` |

Two things the sweep found about the *checker* matter more than the two links:

- **The anchor slug rule is a parser feeding a guard.** A first-cut slugger reported the guide's link
  to "Expanding a top candidate demotes it — 'expand the top candidates' is an anti-pattern" as
  broken. It is not: GitHub lowercases, drops every character that is not a letter, digit, space,
  hyphen or underscore, turns spaces into hyphens, and does *not* collapse the doubled hyphen the em
  dash leaves behind. A slugger that gets this wrong either cries wolf on that heading or, worse,
  passes a genuinely wrong anchor. Duplicate headings in one file get `-1`, `-2` suffixes.
- **A link inside inline code is not a link.** ADR 1 line 26 shows the template
  `Superseded by [NNNN](...)` inside backticks. Three such spans exist today; a checker that does not
  skip code spans reds on a template, and the fix somebody reaches for is to weaken the check.

## The decision

**Fix the two links, and add `DocumentationLinksTest` in `arch`** (beside the other document-against-
tree tests, on `RepositoryTree`) that walks `README.md` and every `docs/**/*.md` — the committed spec
and plan records included, because a link from one of them to a scratch path is exactly the class of
defect this closes — and asserts, for every `[text](target)` outside fenced blocks and inline code
spans, with `http`, `https` and `mailto` targets skipped:

- a `path` resolves to a file, relative to the linking document;
- an `#anchor` resolves to a heading in the target file (the same file when there is no path) under
  GitHub's slug rule, duplicates suffixed;
- the failure names the linking file and line, the target, and which half failed.

**Vacuity guard without a count.** The test asserts it checked at least one link in `README.md` and
at least one in `docs/developer-guide.md`, both known to carry them. A numeric floor would be a
count in a test, which is the drift this week keeps removing.

**Positive controls, definition of done.** (1) Change one link's filename → red naming file:line and
the missing target. (2) Drop a word from an anchor → red naming the anchor. (3) The em-dash heading
link at guide line 665 passes as committed; mangle it by one character → red — that is the slugger's
control. (4) Delete the inline-code skip from the test → ADR 1 line 26 reds on `...`; restore. Each
quoted, each reverted. The two real links fixed last, so the test is seen red on `main`'s guide first.

## Rejected

- **Fix the two links and stop.** The issue's own point: a reviewer had already found three, and a
  set claim is the deliverable. The sweep is what a test keeps true.
- **A third-party link checker in CI.** Another tool, another config, and the slug rule would live
  outside the repository's tests where the positive-control discipline does not reach it.
- **Only `docs/developer-guide.md`.** The ADRs link each other in every "Related" line and the README
  links the guide; a checker scoped to one file re-creates the gap one directory over.

## Recorded

No ADR: a gate addition records no decision. The guide's testing-strategy "Documentation" row gains
the new test, beside the three it already names.
