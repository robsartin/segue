# `expandPromotions --known` — expanding the known-list entities nothing has expanded — design

Issue #313. Written 2026-09-12 against the code on `main` after #307 (the expander's `--rated-since`)
and #311 (the known-list census). The second of the three coverage sub-projects the owner named: the
census measures the gap, this closes the part of it the shipped expander can already close.

## The gap this closes

The first known-list census (the reading on #311) shows a few dozen entities on the owner's known
list that the log says were never expanded — all people and groups, the same set whether or not the
promotions are composed in. Everything else on that list has been expanded, and every promotion has.
Those entities are known acts whose own neighbourhoods have never been fetched, so nothing routes
through them: they are invisible to the recommender's sweep and to the evaluation harness alike,
which scores only what the owner has rated (ADR 65).

`expandPromotions` therefore gains a **second population**. `--known <file>` expands the known-list
entities `Expanded` — the census's own rule, in `domain` — says were never expanded, and nothing
else. The census and the expander then agree by construction about who has been expanded, because
they read one rule rather than two copies of it. ADR 63's 2026-09-12 amendment already names this
reader in advance: "a second reader is expected — the re-expansion pass this section's number exists
to gate".

## The flag, and the two populations

- `--known <file>` is optional and takes the same file `recommend`, `rate`, `evaluate` and
  `graphCensus --known` take, read by the same rule (`support.QidList`: the first comma-separated
  field on a line that is exactly a qid).
- The population is **the file's ids, resolved through the merge fold, that `Expanded.in(log)` — its
  seeds resolved through the same fold — does not cover.** It is the file alone: the promotions are
  not composed in, because the promotions are already this tool's other population and the reading on
  #311 says every one of them has been expanded.
- `--known` and `--rated-since` together are **refused with the usage message**. They describe
  different populations, and a run says which one it covered. Neither flag is today's behaviour: with
  neither, the tool visits every promotion exactly as it always has.
- The file's own order is kept, de-duplicated on the canonical side. No sort: the block is
  aggregates, so no output depends on the order, and a second ordering rule would be a second thing
  to keep in step with `KnownList.promoted`'s.

## One rule, reused rather than copied

`KnownListCensus.of` composes the census's answer in three steps, two of which are today **private
statics in `census`** and one of which is already shared:

1. the file's ids through the fold — `KnownListCensus.canonical(List<String>, Equivalences)`;
2. the log's expansion seeds through the same fold — `KnownListCensus.onTheCanonicalSide`;
3. the rule itself — `domain.Expanded.covers`, which is already in `domain` for exactly this reason.

`expand` may not depend on `census` (see *Fences*), so steps 1 and 2 move into `domain`, where both
tools already depend and where `Expanded` and `Equivalences` already live:

- **`Equivalences.canonical(List<String>)`** — every id on the side it turned out to be, distinct, in
  the order given. An overload of the single-id `canonical` beside `resolve(Map)` and
  `resolveUpdatedAt(Map)`, which are the same move for the other two shapes.
- **`Expanded.onTheCanonicalSide(Equivalences)`** — the seeds on the side the population is counted
  on. An instance method on the type that owns the seeds.

`KnownListCensus` then calls both and loses its two private copies; nothing about the census's
answers changes, and its tests are the control for that.

## What the block says

The body does not change. The header clause does, in the shape #307 gave the since clause:

```
# only known-list entities from known.csv that no expansion has covered: 7 excluded (some row in the log cites them as an expansion's seed) — the file's ids are read through the merge fold, so a merge's two sides count once.
```

- Printed directly under the block's own header, in **both** the real block and the dry-run block,
  exactly where the since clause is printed.
- Defined once, in `ExpansionReport`, and pinned once as a literal in `ExpansionReportTest` — the
  literal is what catches the wording moving, and reading it off the constant it pins would prove
  nothing.
- `considered` counts the population **after** the filter, the same field with a smaller population —
  so `considered == expanded + refused + failed` and the dry run's
  `considered - in the graph - minted` arithmetic both survive. The excluded count is on the clause
  and not on the tally, because it counts entities the run was *not* handed.
- **The no-flag block and the since-form block are byte-identical to today's.** Their existing pins
  are unchanged, and that they stay unchanged is the control that this work moved nothing already on
  record.

Which flag was given is representable once: `RatedSince` and a new `KnownNeverExpanded(String file,
int excluded)` become the two permitted implementors of a sealed `Population` in `expand`, and the
renderers take `Optional<Population>`. The exclusivity is then a property of the type as well as of
the parser — there is no value that is both — and the renderer's switch is exhaustive with no
`default`, so a third population has to decide what it prints rather than fall through.

## A file id the graph holds no node for

The expander already has a path for this, and this design adds no second one. `EntityExpansion.expand`
reads `graph.node(qid)` first and returns `Refused(UNKNOWN_ENTITY)` before any adapter is asked, and
`ExpandRun` counts that under `refused, by reason` as `unknown entity`; a dry run counts the same
entity outside `in the graph`. A known-list file naming an entity the projection has never held is
therefore **refused out loud and counted**, never silently dropped from the population — the same
choice ADR 66's #307 amendment made for a promotion with no timestamp, and for the same reason: an
entity the owner asked this tool to visit that vanishes into a filter is one nothing on the block
says anything about.

## What a `--known` run reads

- The log, twice: once through `GraphProjector.replay` for the projection and the fold, and once as
  rows for `Expanded.in`. `Replay` hands back the fold and not the rows (#246, ADR 64), so the rows
  have to be read again. Accepted rather than fixed here: widening `Replay` to carry every row is a
  change to a record three other tools read, for a read this tool makes once per run.
- The known-list file, once, through `QidList`.
- **No rating at all.** With `--known` the promotions are never composed, so `AffinityStore`'s bulk
  read is never called — ADR 16's minimisation falling out of the shape, the same way a run with no
  `--rated-since` reads no timestamp.

## Fences, derived from the code

- **`theExpanderOpensNothingElse` forbids `expand → census`.** The rule is
  `otherDevToolsAnd(List.of("expand"), "..jena..", "..mcp..", "..app..")` over `DEV_TOOL_PACKAGES`,
  which lists `census`, with no exception for the expander — "it borrows no sibling's fence". So
  `census.KnownListInput` as it stands is unreachable from `expand`, and it **moves to `support`,
  beside `QidList`**, which both tools already reach. `support` is where a file reader more than one
  dev tool shares already lives (ADR 45), which is the argument `QidList`'s own javadoc makes.
- **`QidList` itself passes every expander fence.** `theExpanderOpensNothingElse` bans the sibling
  dev tools, `jena`, `mcp` and `app`; it deliberately does not ban `java.net`. `QidList` is in
  `support`, reads through `java.nio.file` and reaches no network.
- **`theExpanderTakesItsDatabaseFromTheFlagAlone` is not tripped by the move.** It fires on an access
  whose target is owned by `support` and whose return type or field type is a `Path`.
  `KnownListInput.read` returns a `KnownListInput`; its two accessors return a `String` and a
  `List<String>`. The `Path` the flag names is built by `ExpandCli` from the operator's own string,
  exactly as `--db` already is.
- **`theReplayingToolsTakeTheBootsFold` is not tripped.** It bans `Fold.of`, `Retractions.in` and six
  named `Equivalences` methods; `canonical` is not among them, and `ExpandCli` already calls
  `Equivalences.resolve`.
- **No ArchUnit rule changes in this issue.** If one fires, that is a finding to report, not a rule
  to edit.

## Safe to paste

The clause carries the **first operator-supplied text** this tool's block has ever held. `RatedSince`
could not carry an identifier — `Instant.toString` emits no letter but `T` and `Z` — and a basename
can. Two things hold it:

- it is the **basename and never the path**, and `KnownListInput` is the one home of that rule, which
  is the only reason that type exists (ADR 51, ADR 63);
- `ExpansionIsSafeToPasteTest` gains the flagged path, as `CensusIsSafeToPasteTest` did for the
  census's own heading in #311, with a planted control: a fixture file whose basename is itself
  qid-shaped must make the guard fire.

Whether the basename is qid-shaped is a property of the file the owner points at, not of this code —
the same sentence `CensusIsSafeToPasteTest` already carries.

## Tests

- `Equivalences.canonical(List)`: a merge's two sides collapse to one entry on the canonical side;
  order and distinctness are the file's.
- `Expanded.onTheCanonicalSide`: a seed recorded on a merge's retired side covers the canonical id
  after the fold; the control is that it does not before it.
- The two new pins: the real block and the dry-run block with the clause, character for character,
  with the existing pins unchanged beside them.
- The parser: `--known` is carried as the path; `--known` with `--rated-since` is refused with the
  usage text; each alone still parses (the control that the refusal is about the pair).
- The population, driven end to end over a `@TempDir` database as a dry run: a known entity the log
  shows as expanded is excluded and `considered` drops; one never expanded is included.
- A file id the projection holds no node for, driven as a real run that reaches no network because
  every id in the file is refusable, lands under `refused, by reason` / `unknown entity`.
- The runbook: the guide's known-list variant, its dry run before its run, checked by
  `DeveloperGuideExpandPromotionsExamplesTest` the way the `--rated-since` variant already is.

## Premise corrections (2026-09-12, issue #313)

Read from the code before the plan was written. Each names what the issue assumed, what the code
does, and what the plan does instead.

1. **"read through the same `KnownListInput`/`QidList` path the census uses" cannot be done where
   `KnownListInput` lives.** The issue asks for `theExpanderOpensNothingElse` to be checked against
   `QidList`; `QidList` is fine. What bites is `KnownListInput`, which is in `census`, and that rule
   bans every sibling dev tool. The plan **moves `KnownListInput` to `support`**, green at each step,
   with the guide's package table following.
2. **"the census's rule" is three steps, and two of them are private to `census`.** Reusing the rule
   means reusing the two folds as well as `Expanded.covers`; the plan extracts them into `domain` as
   named above rather than copying six lines into `expand`.
3. **The block's header and its section heading both still say "promotion".**
   `ExpansionReport.HEADER` ("segue promotion expansion — aggregates only…") and the body's
   `promotions` section heading are pinned constants that the no-flag block's byte-identity depends
   on. A `--known` run therefore prints a block whose fixed text still says promotions, and whose
   clause is the only thing that names the population it actually covered. Recorded rather than
   fixed: renaming either would move every block already pasted into an issue. The alternative — a
   second section heading for the known population — is rejected for the same reason, and because it
   would give the report two bodies where the clause already carries the fact.
4. **The excluded count is not "how many the file named minus how many were expanded".** It is the
   resolved population's size minus the filtered population's size, so a merge's two sides are one
   entity on both sides of that subtraction.
5. **A `--known` run reads no rating**, which the issue does not say and which falls out of the
   population no longer being composed through `KnownList.promoted`.
6. **The end-to-end tests must keep the network out by construction.** A non-dry run over a file
   naming an entity that *does* have a node and has never been expanded would really expand it. Every
   non-dry test here names only entities that are refusable — no node, or `LocalEntity.isLocal` —
   which is how `ExpansionIsSafeToPasteTest` already reaches the full block offline.

## Not this design

The isolated-but-expanded entities (the larger gap; a second-hop design, after the re-census). The
three wrongly-kinded entries. Any change to the evaluation harness. Recording expansion state in the
log as a claim of its own — ADR 66's #307 amendment already rejected that, and nothing here reopens
it.
