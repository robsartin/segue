# Pin the two Wikimedia page classes the expansion brought into the top ten

Issue #294. Written 2026-09-08 against branch `294-ready`, at `origin/main`. Everything below was
read from the code in this worktree; no database was opened and no dev task was run.

## What this work is

The census read after the first expander run put two classes into the `CONCEPT` top ten that
`KindMapper` has no rule for: `Q104635718` (Wikimedia artist discography) and `Q13433827`
(encyclopedia article). Issue #294 records both, looked up live on 2026-09-08 with label and
description quoted from Wikidata, and rules both: **stay `CONCEPT`, pinned.**

So this work adds **no `KindMapper` rule**. `BY_CLASS` gains nothing, `rederive` (ADR 42) therefore
moves no node at the next boot, and the graph is byte-identical afterwards. What it adds is a
statement, in the only place that can hold one: a test that fails if a later class pass promotes
either id. Plus two `ClassLabels` names, so the tooltip and the rating card can say what the node
is rather than showing a bare QID.

A Wikimedia artist discography is a Wikipedia list page *about* an artist's releases, not a
release. An encyclopedia article is a page *about* a subject, not the subject. Both are the hub
shape issue #52's amendment to ADR 31 built the `CONCEPT` gate for: a node many works and people point at that
nobody did anything with. Mapping either to `WORK` would put a Wikipedia page into the recommender's
candidate pool, which is the failure the gate exists to prevent. This is the same ruling issue #261
gave awards and fictional human, and issue #265 gave the three character classes.

## Where the issue's premise is looser than the code

Four things, all found by reading the files the issue names. None changes the ruling.

- **The stand-in fence's allowlist holds no hash, so there is nothing to recompute.**
  `StandInQidsDenoteNothingTest.ALLOWED` maps an id to a reason plus a set of `Site` records, and a
  `Site` is `(file, context)` where context is code or annotation. The "(file, hash) pairs" phrasing
  belongs to a different class — `AdrCitationsTest`, which allowlists commit citations in
  `docs/adr/` by `(file, hash)`. Registering an id in the stand-in fence is a hand-written entry,
  not a recomputation, and the plan says so. The dispatch's "recompute the hash" step therefore has
  no counterpart in the code; the guard it stands in for is real, and is written out as two reds
  per pin task instead.
- **The census does not name classes, deliberately.** The issue's Shape says `ClassLabels` is
  wanted "so a tooltip and the census name them" (the issue was corrected on 2026-09-08 to say the
  census names no class by label, after this finding). ADR 63 explicitly rejected printing a
  `ClassLabels` label beside the qid in the census section, on the grounds that the fallback prints
  the bare qid on exactly the classes the section exists to surface. No class under `census` imports
  `ClassLabels`; the two consumers are `export/DotWriter` (the DOT tooltip) and `rate/Card` (the
  rating deck card). The labels are still worth adding, for those two — but the census will keep
  printing the bare qid, and nobody should read the census expecting otherwise.
- **`KindMapper`'s comment block narrates `CONCEPT` rulings only for issue #261.** The block above
  the #261 rules says four of that reading's ten "stay CONCEPT on purpose (awards, and fictional
  humans — see KindMapperTest)". Issue #265's three character pins added no such sentence — its one
  new comment line names only the three works it mapped. So a reader of `KindMapper` today can see
  that #261 refused four classes and cannot see that #265 refused three. #294 refuses two more and
  adds no `put` at all, which makes the issue completely invisible in the table. One sentence,
  pointing at `KindMapperTest` the way the #261 sentence already does and naming no ids, fixes that
  for #294. It does not restate the list: the test is the authority, and mirroring the ids into a
  comment would be a second copy to drift.
- **The developer guide's count of `ClassLabels` is already stale.** The guide's exporter chapter
  calls it "a table in the source of about 45 classes"; the table holds 51 entries today and will
  hold 53 after this work. The figure was already wrong by six before #294 touched anything. The
  honest repair is not a new number — it is to stop restating one, since the table is the authority
  and the sentence's point is where the names come from, not how many there are.

## What changes, file by file

1. `src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java` — two pins, beside the
   fictional-human pin and the #265 character pin, one per class, each with its own reason.
2. `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java` — two allowlist
   entries, because both ids are real Wikidata classes appearing in a test file. Each entry starts
   naming `KindMapperTest` only and gains `ClassLabelsTest` in the commit that first cites it there.
3. `src/main/java/com/robsartin/segue/support/ClassLabels.java` — two names, in the concepts block,
   quoted exactly as issue #294's table quotes them from Wikidata's `labels/en`.
4. `src/test/java/com/robsartin/segue/support/ClassLabelsTest.java` — one test per name, in the
   shape #261 and #265 used.
5. `src/main/java/com/robsartin/segue/wikidata/KindMapper.java` — one comment sentence, naming
   #294 and pointing at the test. No `put`, no behaviour.
6. `docs/developer-guide.md` — one clause, dropping the drifting count.

Nothing else. No ADR, no census, no exporter code, no recommender constant.

## Why a pin needs a planted control before it is worth anything

A pin asserts what already holds. Written and run, it passes on its first execution, which proves
nothing at all: an assertion that has never been red is an assertion nobody has shown can fail. Both
of these pins are exactly that shape, because the production change they guard is the *absence* of a
line.

So the RED for each pin is planted. Before the test is written, `put("Q…", NodeKind.WORK)` goes into
`KindMapper`'s static block — the wrong ruling, written out. The pin is then written, run, and fails
with `expected: CONCEPT but was: WORK`. The plant comes out, the pin runs green, and `git diff
src/main` is empty before the commit. That sequence is red-then-green with the production tree
ending where it started, and it is the only way a pin earns its place.

Two details the plan carries. The planted id must be one `BY_CLASS` does not already hold, or the
duplicate check in `put` throws at class load and the failure is an `IllegalStateException` rather
than the assertion — a compile-or-load error is not a red. And each pin is planted separately: two
pins in one test method would let the first failing assertion mask the second, so each class gets
its own test and its own plant.

## Ordering, so every commit is green

The stand-in fence reds in both directions. An id sighted in a test file with no allowlist entry
reds `shouldUseAnIdWikidataCannotAllocateWhenATestNamesAnEntityItInvented`; an allowlist entry
naming a file that does not carry the id reds
`shouldCarryNoDeadSiteWhenTheAllowlistIsCheckedAgainstTheTree`. Both directions are exact, so an
entry cannot be written ahead of the sighting it describes.

That fixes the order. Each pin commit registers its id at `KindMapperTest` and nowhere else. The
`ClassLabels` commit, which is the first to put either id into `ClassLabelsTest`, is the commit that
adds the second site to each entry. Green at every step, in both directions, without a commit that
is green only because a later one lands.

## Why no ADR amendment

No decision changes. ADR 21 fixes six kinds, ADR 22 makes `CONCEPT` the honest fallback, ADR 38 and
issue #52's amendment to ADR 31 depend on high-degree `CONCEPT` meaning "hub", and ADR 42's
2026-09-05 amendment for issue #261 already records the reasoning for refusing a class as well as
for adding one. This work is that recorded reasoning applied twice more. An amendment that said "and
two more classes were refused for the reason already written down" would be a changelog entry in a
decision record, which is what makes ADR files rot.

## Out of scope

Why the expander reached list pages and encyclopedia articles at all — which property brought them
in — is the adapter's claim mapping, and the issue defers it. The next census reading is the check
on this work, and the expected result is that nothing moves: `CONCEPT` should not fall, `WORK`
should not rise, and both classes should still be at the head of the class section. That reading
belongs in a closing comment on issue #294, not in a committed file.
