# Pin biographical article and discography at CONCEPT

Issue #300. Written 2026-09-08 against branch `300-ready`, at `origin/main`. Everything below was
read from the code in this worktree; no database was opened and no dev task was run.

## What this work is

The census read before the ninth evaluation reading put two more classes into the `CONCEPT` top ten
that `KindMapper` has no rule for: `Q19389637` (biographical article) and `Q273057` (discography).
Issue #300 records both, looked up live on 2026-09-08 with label and description quoted from
Wikidata, and rules both: **stay `CONCEPT`, pinned.**

So this work adds **no `KindMapper` rule**. `BY_CLASS` gains nothing, `rederive` (ADR 42) therefore
moves no node at the next boot, and the graph is byte-identical afterwards. What it adds is a
statement, in the only place that can hold one: a test that fails if a later class pass promotes
either id. Plus one `ClassLabels` name, so the tooltip and the rating card can say what the node is
rather than showing a bare QID.

A biographical article is a dictionary or encyclopedia entry *about* a person, not the person. A
discography is the study and cataloguing of published sound recordings — a discipline, and a
catalogue *of* releases, not a release. Both are the hub shape issue #52's amendment to ADR 31 built
the `CONCEPT` gate for: a node many works and people point at that nobody did anything with. Mapping
either to `WORK` would put a reference page or a catalogue into the recommender's candidate pool,
which is the failure the gate exists to prevent. This is the same ruling issue #261 gave awards and
fictional human, issue #265 gave the three character classes, and issue #294 gave Wikimedia artist
discography and encyclopedia article one week's reading ago.

The two are the direct neighbours of #294's two, which is why this issue repeats #294's shape
exactly: a biographical article is an encyclopedia article's sibling, and the issue records that
`Q104635718` (the Wikimedia list class #294 pinned) is a subclass of `Q273057`. That subclass claim
is the issue's, quoted from its live lookup; nothing here re-derives it and no committed file
restates it as a fact of this repository.

## Where the issue's premise had to be checked against the code

Three things, all found by reading the files the issue names. None changes the ruling; the second is
the only one that changes what a task does.

- **`Q273057` is already in `ClassLabels`, and the label the issue quotes is the label the table
  already carries.** `ClassLabels.java:112` holds `put("Q273057", "discography");`, in the concepts
  block, under a comment that already says it "states no class KindMapper recognises". So the
  `ClassLabels` task adds exactly one name, `biographical article`, not two — which is what the
  issue's Shape says, and it is right.
- **`Q273057` has no entry in `StandInQidsDenoteNothingTest.ALLOWED`, and `ClassLabelsTest` does not
  cite it.** This is the one place a plausible reading of the dispatch is wrong. The fence sweeps
  `src/test` only, and `ClassLabels.java` is `src/main`, so the id has never been sighted: `grep -rn
  Q273057 src` returns exactly one line, the `ClassLabels` `put`. There is therefore no entry to
  **widen**; both ids get a brand-new entry, and both entries name `KindMapperTest` and nothing
  else. `Q273057`'s entry stays at one site for the life of this issue, because nothing here adds a
  `ClassLabelsTest` test for it — the name it would assert is already in the table, so such a test
  could never be seen red, and a test that has never been red is not a test (see below).
- **The stand-in fence's allowlist holds no hash.** `ALLOWED` maps an id to a reason plus a set of
  `Site` records, where a `Site` is `(file, context)` and context is code or annotation. The
  `(file, hash)` allowlist belongs to `AdrCitationsTest`, for commit citations in `docs/adr/`, and
  nothing here touches it. Registering an id in the stand-in fence is a hand-written entry, not a
  recomputation. Nothing is recomputed and the fence is never widened beyond the sites just sighted.

One thing the issue says that the code confirms: the developer guide's `ClassLabels` sentence no
longer restates a count — issue #294 removed that clause — so there is no documentation drift for
this issue to repair, and `docs/` is untouched.

## What changes, file by file

1. `src/test/java/com/robsartin/segue/wikidata/KindMapperTest.java` — two pins, beside #294's two,
   one per class, each with its own reason and its own planted red.
2. `src/test/java/com/robsartin/segue/arch/StandInQidsDenoteNothingTest.java` — two new allowlist
   entries, because both ids are real Wikidata classes newly appearing in a test file. Each starts
   naming `KindMapperTest` only; `Q19389637` gains `ClassLabelsTest` in the commit that first cites
   it there, and `Q273057` never gains a second site.
3. `src/main/java/com/robsartin/segue/support/ClassLabels.java` — one name, `biographical article`,
   at the end of the concepts block, quoted exactly as issue #300's table quotes it from Wikidata's
   `labels/en`.
4. `src/test/java/com/robsartin/segue/support/ClassLabelsTest.java` — one test for that one name, in
   the shape #261, #265 and #294 used.
5. `src/main/java/com/robsartin/segue/wikidata/KindMapper.java` — the existing no-rule comment gains
   one clause naming #300. No `put`, no class id, no behaviour.

Nothing else. No ADR, no ADR amendment, no census, no exporter code, no recommender constant, no
developer-guide edit.

## Why a pin needs a planted control before it is worth anything

A pin asserts what already holds. Written and run, it passes on its first execution, which proves
nothing at all: an assertion that has never been red is an assertion nobody has shown can fail. Both
of these pins are exactly that shape, because the production change they guard is the *absence* of a
line.

So the RED for each pin is planted. Before the test is written, `put("Q…", NodeKind.WORK)` goes into
`KindMapper`'s static block — the wrong ruling, written out. The pin is then written, run, and fails
with `expected: CONCEPT but was: WORK`. The plant comes out, the pin runs green, and `git diff
--stat src/main` is empty before the commit. That sequence is red-then-green with the production
tree ending where it started, and it is the only way a pin earns its place.

Two details the plan carries. The planted id must be one `BY_CLASS` does not already hold, or the
duplicate check in `put` throws at class load and the failure is an `IllegalStateException` rather
than the assertion — a compile-or-load error is not a red. Both ids qualify: `grep -n
'Q19389637\|Q273057' src/main/java/com/robsartin/segue/wikidata/KindMapper.java` returns nothing.
And each pin is planted separately: two pins in one test method would let the first failing
assertion mask the second, so each class gets its own test and its own plant.

The same reasoning is why `Q273057` gets no `ClassLabelsTest` test. Its label is already in the
table, so the test would be green on its first run with no production change to plant against —
exactly the shape this section refuses. The pin is the statement this issue is making about that
class; the label is not new and needs none.

## Ordering, so every commit is green

The stand-in fence reds in both directions. An id sighted in a test file with no allowlist entry —
or with an entry whose sites do not include that file — reds
`shouldUseAnIdWikidataCannotAllocateWhenATestNamesAnEntityItInvented`; an allowlist entry naming a
file that does not carry the id reds `shouldCarryNoDeadSiteWhenTheAllowlistIsCheckedAgainstTheTree`.
Both directions are exact, so an entry may not be written ahead of the sighting it describes.

That fixes the order. Each pin commit registers its id at `KindMapperTest` and nowhere else. The
`ClassLabels` commit, which is the first to put `Q19389637` into `ClassLabelsTest`, is the commit
that adds the second site to that one entry — and only to that one.

The second red in the `ClassLabels` task is worth writing out, because it differs in shape from the
pin tasks' second red. In a pin task the id has no entry at all, so `report` prints the bare
sighting. In the `ClassLabels` task the id already has an entry naming a different file, so `report`
appends the allowed sites, and the failure reads `…ClassLabelsTest.java:<line>  Q19389637  —
allowed, but only at …KindMapperTest.java (in code)`. That is the fence saying precisely the thing
this ordering exists to exercise: an id allowed at one file is not allowed at another.

## Why no ADR amendment

No decision changes. ADR 21 fixes six kinds, ADR 22 makes `CONCEPT` the honest fallback, ADR 38 and
issue #52's amendment to ADR 31 depend on high-degree `CONCEPT` meaning "hub", and ADR 42's
2026-09-05 amendment for issue #261 already records the reasoning for refusing a class as well as
for adding one. This work is that recorded reasoning applied twice more. An amendment saying "and
two more classes were refused for the reason already written down" would be a changelog entry in a
decision record, which is what makes ADR files rot. Nothing under `docs/adr` is touched, so no
commit hash is cited anywhere.

## Why the table comment gains a clause rather than a paragraph

`KindMapper`'s table already carries one sentence, added by #294, saying that a reading added no
rule and pointing at `KindMapperTest`. #300 is the same refusal for two more classes of the same
shape. A second paragraph would be a second copy of the same statement; one clause naming #300
inside the existing sentence says everything a later class pass needs to know, and the ids stay in
the test where the assertion is. The comment names no class id, for the reason the comment itself
gives: a second copy of the list is the one a later editor forgets.

It is a comment, so it has no unit-testable behaviour. It is verified by another explicit method —
the full gate in the last task, which compiles the class, runs `spotless` over it and runs every
test that reads it — and by re-reading the diff to confirm it adds comment lines only, no `put`, and
no class id. That is stated out loud here so nobody reads a test-after into it.

## Out of scope

Why the expander reaches article and catalogue pages at all — which property brings them in — is the
adapter's claim mapping, and the issue defers it exactly as #294 did. Everything below these two in
the top ten is out too; the issue says the head there is flat. The next census reading is the check
on this work, and the expected result is that nothing moves: `CONCEPT` should not fall, `WORK`
should not rise, and both classes should still be in the class section. That reading belongs in a
closing comment on issue #300, not in a committed file, and no figure from the owner's graph appears
in anything this plan writes.
