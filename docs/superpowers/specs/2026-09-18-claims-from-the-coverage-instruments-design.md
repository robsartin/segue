# What Wikidata lacks: batch claims from the coverage instruments — design

Issue #342. Written 2026-09-18 against the code on `main`. Coverage sub-project 3, the last of the
three the owner named on 2026-09-12 (#311's framing). The owner chose the populations and the two
shapes on 2026-09-18 and then cut the remaining sections short; the rulings this spec makes where a
question would have been asked are marked **ruling**.

## What the owner chose

- **Population a) first, then b).** a) is the seed tool's review file's rows that resolved to
  nothing in Wikidata: an entity the graph lacks entirely, so a `mint` and then at least one
  `assert`. b) is the `--isolated` file's `with no one` acts: in the graph, every neighbour
  expanded, connected to nothing known, so an `assert` and no mint. c), the `no such entity`
  refusals of `expandPromotions --known --add`, is a stale mapping and a seed-tool matter, out.
- **The mint is a batch from the review file**, `ownClaim mint --review <file> --mapping <file>`,
  over the chapter-only alternative (a hand-typed `mint` per row and a hand-edit of the mapping,
  the typing this project exists to remove) and over minting inside `resolveNames` (which would
  make a tool that needs no database into a writing one).
- **The assert is a batch from a file the owner writes**, `ownClaim assert --file <claims>`, three
  columns `from,to,type`, over one `assert` per run (the `with no one` population is dozens of acts)
  and over the tool proposing a neighbour (it covers one case, the book's author, and guesses
  where the owner knows).

## What exists, and the one fence

`ownClaim` (ADR 59, ADR 60) does one of `mint`, `assert`, `merge` per run, reports before it
appends, and `--dry-run` stops at the report. `OwnRun.run` takes one `Options` and returns the one
`LoggedAssertion` it appended or would have; `mintEntity` allocates the smallest `Q00…` id no row in
the log has ever named; `assertEdge` refuses an endpoint the projection does not hold and names a
merged-away local id by its canonical. The seed tool (ADR 40) writes a mapping and a review file in
one seven-column shape, `name,kind,status,qid,label,confidence,reason`, with `Outcome` one of
`ACCEPTED`, `REVIEW`, `UNRESOLVED`; `SeedFiles` reads and appends that shape and
`SeedFiles.alreadyResolved` folds names through `Names.fold` so a re-run resolves nothing twice.
`Expectations` registers each list kind with the node kinds it may be, an occupation set and a
class set; `musician` and `comedian` register two node kinds, every other kind one. `QidList`
reads the first comma field that is exactly a qid, and a local `Q00…` id is one, so a local id in
the mapping is on the `--known` population the moment it is written. The deck deals the file's
in-graph unrated entities with no kind filter, and `KnownListCensus` already reads a rating against
a local id as promoting the entity it resolves to.

The fence: `ArchitectureTest.theOwnerClaimToolOpensNothingElse` forbids `own` from depending on any
other dev tool, `seed` among them, and `seedNeverOpensAStore` forbids `seed` from reaching a store.
So the review file's shape, the name fold and the list-kind fold cannot be read by `own` where
they are. **They move to `support`**, which both tools may read, and `seed` reads them from there.
That is a prerequisite, done first and green on its own (Mikado): the observed break is `own`
importing `seed` and reddening the fence, reverted, then the move, then the feature.

## What changes

### 1. The shared shape moves to `support`

- `ResolutionRow`, `Outcome` and the mapping/review reader, appender and `alreadyResolved` leave
  `seed` for `support`; `Names.fold` goes with them (the spellings half of `Names` is resolver
  logic and stays). `seed` imports them; every `seed` test that named them keeps passing with the
  import changed and nothing else.
- `Outcome` gains a fourth value, `MINTED`: a row the owner minted under ADR 59, written by the
  claim tool and never by the seed tool. `alreadyResolved` treats it as resolved, as it treats
  every row.
- The list-kind → node-kind table leaves `Expectations` for one home in `support`, a map from the
  registered kind name to the set of node kinds it may be; `Expectations` builds its expectations
  from that map so there is one copy. Occupations and class sets stay in `seed`; they are
  resolver knowledge.
- **Ruling:** the plan verifies `support` may depend on `domain.NodeKind` under the existing
  layering rules before the move, and reports if it may not.

### 2. `mint --review <review> --mapping <mapping>`

```
./gradlew ownClaim --args="mint --db $HOME/.segue/segue.db --review $HOME/lists/reading-review.csv --mapping $HOME/lists/reading-qids.csv --dry-run"
```

- **Parse.** `--review` and `--mapping` are required together; either with `--kind` or `--label`
  is refused as an option belonging to a different operation, the pattern `OwnCli.parse` already
  has. Parse touches no file; the run reads them. `--dry-run` as today.
- **Selection.** Rows of the review file whose outcome is `UNRESOLVED`. `REVIEW` rows are never
  minted: each carries a plausible candidate, and minting one would duplicate a real item. A row
  whose folded name the mapping already carries (any outcome) is skipped with one line, so a
  re-run mints nothing twice.
- **Per row.** Label is the row's name. Node kind is the fold of the row's list kind. **Ruling:**
  a list kind that folds to more than one node kind (`musician`, `comedian`) is skipped with one
  line that prints the single-mint command to type for that row, `--kind` left for the owner to
  fill; a list kind the table does not register refuses the whole run before any append, naming
  the row, because the file is not what the seed tool wrote. Ids are allocated in sequence from
  one read of the log, each one the smallest never named once the earlier mints of this run are
  counted as named.
- **Report before append.** One line per row in today's mint wording, `minting Q00… "label"
  (KIND) — no source claims this entity; you are the source`, then one line each for the skipped
  and one line with the totals to mint and to skip. A dry run ends with `dry run: nothing was
  appended`.
- **Append.** For each row in file order: the `LocalEntity` claim appends to the log, then one row
  appends to the mapping in the seven-column shape — the row's name, kind and status, the
  allocated id as `qid`, the name again as `label`, `MINTED`, and the reason `minted by the owner
  — no Wikidata candidate under any spelling (ADR 59)`. Interleaved per row, so a failure between
  the two leaves at most one mint without its mapping row, and the report's last lines say which
  ids were appended, so the owner can add that row by hand. The run ends with today's `appended.
  The running graph is rebuilt from the log at the next boot` sentence.
- **`OwnRun`.** A second entry point that takes the batch and returns the list of claims it
  appended or would have; `run` for one `Options` is unchanged, and the single `mint` keeps its
  exact report.

### 3. `assert --file <claims>`

```
./gradlew ownClaim --args="assert --db $HOME/.segue/segue.db --file $HOME/lists/claims.csv --dry-run"
```

- **The file.** Header `from,to,type`, then one edge per row: two ids that look like qids (local
  ids allowed on either side) and one `EdgeTypes` code. A row with fewer than three fields, an
  id that is not qid-shaped, or a code not in the vocabulary refuses the whole file before any
  append, naming the row. The reader lives in `own`; nothing else reads this shape. Lines
  beginning `#` are comments, so the owner can annotate a file that is personal data.
- **Parse.** `--file` with any of `--from`, `--to`, `--type` is refused as above. Parse touches no
  file.
- **Report before append.** For each row, today's two lines: `claiming Q… "label" CODE Q…
  "label"` with both labels read from the projection, and the corroboration sentence once at the
  end rather than per row. An endpoint the projection does not hold, or a merged-away local id,
  refuses the row with today's exact sentence; **ruling:** any refused row refuses the whole run
  before any append, because there is no edge-level retraction and a wrong edge is undone only by
  retracting an endpoint. **Ruling:** a row whose edge the log already carries surviving (same
  from, to and code, or the same after the merge fold) is skipped with one line, since the
  projection folds a duplicate to one edge and the log would only gain noise.
- **Append.** Every row's `OwnerEdge` claim, in file order, then today's closing sentence.

### 4. The chapter and the runbook

Two sections join `## Claiming something no source has` in `docs/developer-guide.md`, after
`### An owner edge routes, and never vouches`: one for each batch, each with its example lines
(parsed by `DeveloperGuideOwnClaimExamplesTest`, so every example is a complete double-quoted
invocation), the selection and skip rules above, and the refusal sentences. Then a runbook chapter
after `### Adding what your list names that the graph has never held: --known --add`, "What
Wikidata lacks", end to end:

1. Step 0 as every writing run: stop the stdio JVMs holding the database.
2. **Population a).** From the list's review file: `mint --review … --mapping … --dry-run`, read
   the labels, then the run. The mapping now carries the local ids; `graphCensus --known
   <mapping>` counts them under `in the graph` and, until #344 lands, under `never expanded`
   too, for the reason #344 states.
3. **The claims file.** One row per edge, from a minted id or a `with no one` act's qid (the
   `--isolated` file puts each qid beside its label) to something the graph holds, with a code
   from `EdgeTypes`. `assert --file … --dry-run`, read both labels on every line, then the run.
4. **Population b)** is step 3 over the isolated file's acts.
5. **A deck session** with the mapping as its own `--known`: a minted entity is dealt like any
   in-graph unrated one, and a rating at or above `KnownList.PROMOTION_RATING` promotes it.
6. **The census after**, and the reading that follows on the normal rule, with its own issue.
7. **When Wikidata catches up**, `merge --local Q00… --canonical Q…` as the chapter already says;
   the mapping keeps the local id and the fold resolves it.
8. **Undoing**: `retractEntity` on the local id takes its node, its edges and its mapping row's
   meaning with it; the mapping row is the owner's to delete.

Noted for a follow-up, not this issue: the owner's run on 2026-09-18 showed that a refusal from
`ownClaim` prints a stack trace beneath the usage sentence.

### 5. Records

- **ADR 59**, dated amendment: the two batch shapes, why "one operation per run" still holds (one
  *kind* of claim per run, the report before any append, all-or-nothing on a refusal), the
  `MINTED` outcome and the mapping as where a local id lives for `--known`. The alternatives the
  owner rejected, with the reason each lost.
- **ADR 40**, dated amendment: the mapping/review shape is now shared with the claim tool and
  lives in `support`; `MINTED` is a row the seed tool never writes and reads as resolved.
- **ADR 60** needs nothing: both batches take `--db` from the flag as before, and the two
  ArchUnit rules named in it are unchanged.

## What does not change

- `merge`, `retractEntity`, the single `mint` and `assert` and their reports, `OwnRun.run`.
- The MCP surface: neither batch is a tool, for ADR 59's reason.
- `Expanded` still reads an owner claim as covering nothing. The census consequence — a local id
  counts under `never expanded` and the second-hop rows forever — is #344, not this change.
- The seed tool's behaviour: it never writes `MINTED`, never reads the database, and its tests
  change only their imports.

## Privacy

The review file, the mapping and the claims file are the owner's, outside the working tree, and
none may enter the repository (ADR 40's rule, and the `--isolated` file's header as precedent).
The reports print labels, as every claim tool's report does today. Fixtures use invented ids and
invented names only.

## Testing

Pure TDD, each guard with a planted positive control observed and removed:

- `OwnCliTest`: the new flags parse into their options; each refused combination refuses naming
  both flags; parse opens no file (a path that does not exist parses).
- `OwnRunTest`, on `SqliteAssertionLog.inMemory()` with `@TempDir` files: the batch mint mints
  only `UNRESOLVED` rows, skips a name the mapping carries, skips a two-kind row printing the
  single-mint command, refuses an unregistered kind before any append, allocates ids in sequence
  (three rows, three distinct ids none of which the log named), writes the mapping rows with
  `MINTED`, and appends nothing on a dry run; the batch assert refuses on a missing endpoint, an
  unknown code and a malformed row before any append, skips a surviving duplicate, appends every
  row otherwise, and appends nothing on a dry run.
- `support`: the moved reader and appender round-trip a `MINTED` row; the kind table folds every
  registered kind and names the two that fold to more than one.
- `ArchitectureTest`: unchanged fences, seen red during the Mikado observation (`own` importing
  `seed`) and green after the move.
- `DeveloperGuideOwnClaimExamplesTest` parses the new examples; `DocumentationLinksTest` over the
  new sections and the ADR amendments.
- The gate, blocking, exit code read before any claim.
