# The stand-in allowlist names a site, not just an id

Issue #216. Written 2026-09-03, against `main` at `07d8e2f`.

## The defect, measured

`StandInQidsDenoteNothingTest` sweeps every file under `src/test` and reds on any allocatable-form
id that is not a key of `ALLOWED`. The membership test is `ALLOWED.containsKey(s.id())` and nothing
else, so an id allowed for one reason at one place is allowed for every reason everywhere.

Measured on `07d8e2f`, as a pair, with `./gradlew test --tests
'com.robsartin.segue.arch.StandInQidsDenoteNothingTest'`:

| plant at `export/DotWriterTest.java:46` | result |
|---|---|
| `new ViewNode("Q1", NodeKind.PERSON, "Wren Alderman")` | `BUILD SUCCESSFUL in 12s` |
| `new ViewNode("Q7", NodeKind.PERSON, "Wren Alderman")` | `AssertionError at StandInQidsDenoteNothingTest.java:281`, `3 tests completed, 1 failed` |

`Q1` is on the list because `GraphStoreContract` numbers its questions `Q1`–`Q4` in `@DisplayName`;
`Q7` is not on the list. Same file, same line, same node-id position, opposite verdicts — and the
green one is a node colliding with a real Wikidata entity, which is the whole subject of ADR 58.
Both plants reverted; the tree is clean.

The issue's account of the code is accurate in every particular checked here. One thing it does not
mention is load-bearing and is the first thing a narrower key breaks: **this class's own source is
inside the swept tree**, so each of the 113 ids appears once more in the allowlist literal itself.
A key of (id, file) with no rule for that file turns all 113 into failures. The design below makes
this file a declaration site rather than a use site.

## What the sweep sees today

Retaken on `07d8e2f` with the guard's own lexer, run standalone over `src/test`:

| measure | value |
|---|---|
| files read | 172 |
| allocatable-form sightings | 486 |
| distinct ids (= `ALLOWED`'s size) | 113 |
| distinct (id, file) sites, this file included | 358 |
| …of them, this file's own allowlist literal | 113 |
| …of them, real sites elsewhere | **245** |
| sightings inside an annotation | **8** — `Q1`–`Q4`, twice each, all in `port/GraphStoreContract` |

Site counts per id are long-tailed: 70 of the 113 ids sit in exactly one file, and the top five are
`Q5` (20 files), `Q215380` and `Q192668` (12), `Q11424` (10), `Q180337` (9). **No id outside this
file appears both inside an annotation and in code**, so the annotation classification splits the
tree cleanly today rather than forcing a judgement call anywhere.

`GraphStoreContract` is the file that makes the annotation half worth having: it carries `Q1`–`Q4`
as question numbers in `@DisplayName` *and* mints node ids (`Q0100001`–`Q0100004`, `Q0999999`) in
its own method bodies. It is the single most likely place in the repository for somebody to write
`new NodeRecord("Q1", …)` and mean it.

## The decision

**An `ALLOWED` entry is keyed by id and names its sites; a site is a file and whether the sighting
sat inside an annotation's arguments. A sighting anywhere else, or in the other context, is a
failure like any other.**

Concretely:

- `Context` is `CODE` or `ANNOTATION`. `CODE` is a string literal or text block in Java code, and
  the whole text of a non-Java file — a resource has no annotations, so the split costs it nothing.
- `Literal` and `Sighting` each carry a `Context`. `ALLOWED` becomes `Map<String, Allowance>` with
  `Allowance(String reason, Set<Site> sites)` and `Site(String file, Context context)`, written
  `real(reason, code("…"), annotation("…"))`. **The reason stays spelled once per id** — `Q5`'s
  reason would otherwise be copied twenty times, and a copied reason is a drift generator. `Set.of`
  makes a repeated site throw at class-init rather than pass quietly.
- Sites are declared per site rather than per id, so an id that is one day a display-name number in
  one file and a real entity in another can say so. That costs one wrapper per line today and
  removes a dead end.
- **This file is a declaration site.** A sighting in `StandInQidsDenoteNothingTest.java` is allowed
  if its id is a key at all; no entry names this file. An allocatable id typed into this class that
  is not an entry still reds, so the class still covers itself — one scope wider than the rest.
- **The report earns the narrower key.** When an offending sighting's id *is* on the list, the
  failure line says where it is allowed: `export/DotWriterTest.java:46  Q1  — allowed, but only at
  src/…/GraphStoreContract.java (in an annotation)`. That is what makes a moved test file a
  one-line fix rather than a puzzle.
- **The dead-entry check becomes a dead-site check**: a declared site whose file no longer carries
  that id in that context reds, and so does an entry that names no site at all. This subsumes
  today's check — an id gone from the tree takes all its sites with it — and is strictly narrower.

### How the lexer classifies, and what it already knew

The lexer already walks the source character by character in four states, and already skips
comments and character literals, so the two constructs that would fool a naive scan are handled
before this change. It knew nothing about annotations. It learns them with one more piece of state:
a `Deque<Boolean>` pushed at every `(` reached in code and popped at every `)`, the pushed value
being whether the `(` closes an `@Ident` — found by walking back over whitespace and identifier
characters to look for `@`. A literal is `ANNOTATION` when that stack holds a `true`. Twenty lines,
no forward scan, no parser, and parens inside comments and literals never reach the stack because
those branches already consume them.

## Rejected

- **Key of (id, file) alone.** Simple, no lexer change, and it closes the symptom the issue's title
  names. Rejected because it leaves the reason un-checkable in the one file where the confusion is
  most likely: `Q1`'s reason says *the question number in `GraphStoreContract`'s `@DisplayName`*,
  and under this key `new NodeRecord("Q1", …)` in `GraphStoreContract`'s own body is green. That is
  the same defect the issue is about, one scope smaller — a key that says less than the reason it
  carries. The measured cost of the better key is eight sightings in one file and twenty lines of
  lexer, against a `Sighting` field the sweep has to thread either way.
- **Key of (id, directory) or (id, package).** Would cut the 245 sites to perhaps 60 and make new
  test files in an established package free. Rejected: `port`, `export` and `wikidata` each hold
  both real class ids and invented node ids, so a package-wide allowance re-opens the hole inside
  the packages where it is most likely to be used.
- **A flat `Map<Site, String>` of site to reason.** Half the machinery — no `Allowance` record.
  Rejected on the reason duplication above: 245 reason strings for 113 reasons, twenty copies of
  `Q5`'s, and no way to change one of them without changing twenty.
- **Keep the id key and detect node-id positions instead.** The thing actually worth forbidding is
  an allowlisted id used *as an identifier*, not its presence in a file. Rejected: telling a node id
  from a class id from a label needs the Java type system, not a lexer, and a guard that cannot read
  the construct must not claim to. The site key is the readable approximation of it.

## The cost, stated

245 sites to declare, against 113 entries today, and roughly one added line each time a test file
first uses a real class id. That is friction where the danger is nil — `Q5` is *human* and always
will be — bought to catch the case where it is real. It is the deliberateness this list was already
for (`naming a real Wikidata entity in a test is meant to be a deliberate act`), moved from
first-use-in-the-repository to first-use-in-a-file, and the failure names the exact line to add.

**Moving a test file reds twice** — once on the new path as an undeclared site, once on the old path
as a dead one — and both messages name the path. That is the accepted false red, and it is a
two-line fix.

## The limit this does not remove

A site key cannot tell a node id from a class id *within a file that already declares the id in
code*. `new NodeRecord("Q5", …)` inside `wikidata/KindMapperTest` stays green, because `Q5` is
legitimately in that file already. Only a parser would catch it. This is stated in the class's
javadoc beside the two limits already recorded there, because a limit written down is a limit and a
limit nobody wrote down is a hole.

## Documentation

The developer guide's stand-in row (`docs/developer-guide.md`, the *Stand-in identifiers* row of the
testing-strategy table) says an id `has to be allowed by name with the reason it is real`; it gains
the site. **No count is restated** — this spec holds the measurement and the code holds the list.

**No ADR amendment.** ADR 58's *What holds it now* says the guard reds on any allocatable-form id
outside the allowlist and that *a companion assertion reds on an allowed id the tree no longer
contains, so a reason cannot outlive the site it was written about*. Both sentences stay true after
this change — the second becomes more literally true — and no decision changes, so the immutable
record needs nothing. ADR 62's shapes are untouched; no id migrates.

## Definition of done

1. The plant from the measurement above — `"Q1"` as a node id in `DotWriterTest` — reds, naming
   `src/test/java/com/robsartin/segue/export/DotWriterTest.java:46` and saying where `Q1` *is*
   allowed. Quoted, then removed.
2. `"Q1"` as a node id **inside `GraphStoreContract`** reds, which is the annotation half doing work
   that (id, file) would not do. Quoted, then removed.
3. Deleting one entry still reds on the real id it covered.
4. A declared site that no longer carries its id reds; so does an entry with no site.
5. The vacuity assertions are untouched and green: files read non-empty, sightings non-empty, this
   class's own source among the files read.
6. `SEGUE_REQUIRE_BROWSER=true SEGUE_REQUIRE_GRAPHVIZ=true ./gradlew check --rerun-tasks` green.
