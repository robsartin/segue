# Known-list census — design

Issue #311. Brainstormed 2026-09-12 as the first of three coverage sub-projects the owner named:
acts on the known list that connect to nothing the owner likes; interests outside music; things
Wikidata does not model. This is the measurement for the first; a second issue, gated on the
number this prints, is the expansion.

## The gap this measures

The evaluation harness (ADR 65) scores only entities the owner has rated, so it cannot see an
entity the graph fails to reach. The two expander runs recorded on issues #284 and #307 showed the
difference: the first, over every promotion, moved the eighth reading a great deal; the second,
over the newest promotions, recorded many assertions and few net edges, because the deck had
dealt those entities for sharing intermediates the first run had already recorded. Where the
unexpanded structure is that touches what the owner likes is not something any current output
says. This section says it, for the owner's own list, read-only, aggregates only.

## Inputs and the two populations

`graphCensus` gains an optional `--known <file>`, the same file `recommend`, `rate` and `evaluate`
take, read through the shared `QidList`. Absent, the block is byte-identical to today's. Present,
the block gains one section, `known list`, printed last, with two sub-sections:

- **`file`** — the qids in the file, resolved through the merge fold every tool resolves a known
  id through (`Equivalences`), so a merged entity counts on its canonical side.
- **`file and promotions`** — the same list composed through `KnownList.promoted` with the ratings
  map the census already reads (`TasteCensus` is handed `AffinityStore.readRatings`), so it is
  exactly the population `recommend` and `rate` reason over, by the one rule they share (ADR 48).

Each sub-section prints the same rows, so the difference reads straight down. A qid the file
names that the graph does not hold is counted under `named` and not under `in the graph`: the
file naming something the graph has never seen is the first coverage gap there is. Ids that are
not well-formed are refused the way `QidList` already refuses them.

## The rows

All counts are whole-population over the population as resolved; a merge's two sides count once;
no entity is named.

- **`named`** — qids in the population.
- **`in the graph`** — of those, ones the projection holds a node for.
- **`never expanded`** — in the graph, and no assertion in the log cites the entity as the seed of
  an expansion. See *Expanded* below.
- **`no known neighbour within two hops`** — in the graph, and a walk of depth `Routes.MAX_HOPS`
  (read by reference; the recommender's route limit) from it reaches no other member of the same
  population. The walk runs on the projection, which already omits retracted and withdrawn edges
  (ADR 44), so it needs no filter of its own.
- **by kind** — `in the graph` and `never expanded` per `NodeKind`, in `NodeKind`'s declared
  order, so a gap concentrated in one kind shows.

## Expanded: one rule, derived from the log

An expansion leaves its seed's id in every edge it records, so "expanded" is read off provenance
rather than guessed from degree:

- a Wikidata forward claim (`ClaimMapper`) carries the statement id as its reference, and a
  Wikidata statement id begins with the subject's qid followed by `$`;
- a reverse-discovered edge (`ReverseClaims`) carries a reference of the form
  `wdqs:<other>:<property>:<seed>`, ending in the seed's qid.

An entity is *expanded* when at least one assertion in the log cites it in either shape. The
MusicBrainz adapter's references name the seed's MBID, not its qid, and the rule does not read
them: every expansion runs every adapter that supports the seed's kind, and the Wikidata adapter
supports every kind, so a MusicBrainz expansion of a seed is accompanied by a Wikidata expansion
of the same seed in the same call. The residual — a call in which Wikidata was unavailable and
MusicBrainz was not — leaves an entity the rule calls unexpanded; that is a true statement about
what Wikidata recorded, and the count errs towards "expand it", which is the safe direction.

`Expanded` lives in `domain` beside `Retractions`, the same two-caller shape ADR 42 gave
`KindMapper.rederive` and ADR 44 gave `Retractions`: this census reads it now, and the expander
issue that follows reads it to choose its population, so the two cannot disagree about who has
been expanded. It takes the logged assertions' provenance and answers for a qid; it holds no
graph and makes no network call.

## Output

The section prints last, after `concept classes`, in `CensusReport`'s shape: a section heading,
sub-headings for the two populations, rows in the label and count columns measured the way the
rest of the block is. Because the section prints only when the flag was given, `CensusReportTest`'s
golden block does not change; the with-flag form is pinned as its own literal. The header line
names the file's basename when the flag was given, never its path, so the block stays safe to
paste (ADR 51, ADR 63).

## Cost

The "expanded" pass is one scan over references already in memory. The walks are one depth-two
neighbourhood read per known entity against the in-memory projection: a few hundred, seconds at
most against a three-second census. The plan measures it once on a synthetic graph of the real
graph's shape and the runbook says what to expect; if it is slower than that, the walk is the
thing to look at, not the flag.

## Fences and rules

- The census stays read-only. Nothing here writes; `theCensusOnlyReads` is unchanged, and the
  plan checks `theCensusOpensNothingElse` against `QidList`'s file read before the first edit,
  since that rule is the one that would refuse a new input.
- ADR 63 gains a dated amendment naming the input and the section, and recording that the flag
  reads a file that is personal data and prints aggregates from it — ADR 51's line, already the
  line the `taste` section stands on.
- `docs/` is a declared test input; the developer guide's census chapter gains the flag and the
  section, and the dev-tool table row follows.

## Tests

- `Expanded`: fires for a forward statement citing the qid as subject; fires for a reverse edge
  ending in the qid; stays silent for a node that appears only as another expansion's neighbour
  (the control); stays silent for a qid that is a prefix of another (`Q12` is not `Q123`).
- The two-hop walk: finds a known neighbour at exactly two hops; misses one at three; ignores a
  neighbour that is not in the population.
- A merge whose two sides are both in the file counts once, on the canonical side.
- A file naming a qid the graph lacks counts under `named` and not under `in the graph`.
- The no-flag block is byte-identical to the golden block; the with-flag block is pinned.
- The `file and promotions` sub-section differs from `file` by exactly the promotions the ratings
  map adds, on an invented ratings map.

## Not this design

Expanding anything; naming an entity; changing the harness; a third population.

## Premise corrections (2026-09-12, issue #311)

Appended after review, from reading the code. Each names what this document assumed, what the code
actually does, and what the plan does instead. Nothing above is edited.

1. **`Routes.MAX_HOPS` cannot be read by reference from `census`.** *The rows* says the walk is "a
   walk of depth `Routes.MAX_HOPS` (read by reference; the recommender's route limit)". `Routes`
   lives in `recommend`, and `theCensusOnlyReads` forbids `census` every dev-tool package but
   `export` — so that reference does not compile, and widening the fence for a constant would hand
   the census a sibling's whole surface. The plan **moves the constant into
   `domain.Recommendations`**, keeping the name, and updates its three call sites (`Routes` twice,
   `RecommendationReport` once) and the one guide sentence that names it. The precedent is exact:
   `Recommendations.MIN_CANDIDATE_DEGREE` already lives there and `DegreeCensus` already reads it
   "by reference and never by a second copy of the number". Rejected: a second literal `2` in
   `census` (the copy this project's single-source rule exists to prevent), and widening
   `theCensusOnlyReads` to admit `recommend`.

2. **The census holds no `GraphStore`, so the walk is over the fold.** `theCensusOpensNothingElse`
   bans `tinker` and `jena`, and nothing in `census` opens an engine. *The rows* already says the
   walk runs on the projection; this records that it is a constraint rather than a preference. The
   adjacency is built from `LogProjection.edges()`, in `census`, as `Degrees.in` already builds
   incidence from the same list.

3. **`theCensusOpensNothingElse` does not refuse `QidList`'s file read**, which *Fences and rules*
   asked to be checked before the first edit. That rule bans six packages, `java.net`/`javax.net`,
   and any class in this project that reaches them; `QidList` is in `support`, which `census`
   already depends on, reads through `java.nio.file`, and reaches nothing on a network.
   `theCensusOnlyReads` does not fence `support` either. **No ArchUnit rule changes in this issue.**

4. **`QidList` does not refuse a malformed id; it ignores the field.** *Inputs and the two
   populations* says ids that are not well-formed "are refused the way `QidList` already refuses
   them". `QidList.read` keeps the first comma-separated field on a line that matches `Q\d+`
   exactly and silently passes over everything else; its only refusals are a file that does not
   exist and a file with no QID anywhere in it. So the true behaviour is: a malformed id is never
   counted, and a file of nothing but malformed ids is refused outright. The plan claims no
   per-id refusal and adds no validation.

5. **The two reference shapes are confirmed, and one row in the log defeats a loose reading of the
   reverse arm.** `ClaimMapper` writes the statement's own JSON `id` — the `Q<seed>$<uuid>` form —
   and falls back to `<property>:<objectQid>` where the response carries none; **no recorded
   response under `src/test/resources/wikidata` carries a statement `id` at all**, so every
   fixture-backed forward claim has the fallback reference and is correctly not read as expanded.
   `ReverseClaims` writes `"wdqs:" + other + ":" + property + ":" + seedQid` on the edge — and, on
   the same call, records each discovered neighbour as a `NodeAssertion` whose reference **is that
   neighbour's own bare qid**. A rule phrased as "ends with the seed's qid" would read every
   neighbour as expanded by itself. The rule is therefore two exact shapes: the reference begins
   `<qid>$`, or it begins `wdqs:` and ends `:<qid>`. That is what *Tests*' neighbour-only control
   and prefix control are controls of, and the plan derives the seed by splitting on those
   separators rather than by testing a suffix.

6. **The basename goes on the section heading, not on `CensusReport.HEADER`.** *Output* says "the
   header line names the file's basename". `HEADER` is a public constant that `CensusReportTest`
   pins and `CensusIsSafeToPasteTest` asserts by identity, and the no-flag block must stay
   byte-identical, so the basename goes on the `known list` section's own heading.

7. **`Q12` and `Q123` are allocatable Wikidata ids**, so the prefix control cannot use them:
   `StandInQidsDenoteNothingTest` sweeps every string literal under `src/test`. The plan uses the
   leading-zero form (ADR 58) for both sides of that control.

8. **The forward shape's qid prefix is matched case-insensitively, and the rule above is therefore
   not "exact".** *Expanded: one rule, derived from the log* and correction 5 both say the forward
   reference begins with the subject's qid followed by `$`. Asked of the live API on 2026-09-12,
   after the implementation review, one real entity was found carrying statement ids minted with an
   uppercase `Q` prefix **and** statement ids minted with a lowercase `q` one, both live on that
   entity. `Expanded.FORWARD_QID` is therefore `[Qq]\d+`, normalised back to the canonical uppercase
   form before it is compared; the digits and the `$` separator stay exact, and the reverse arm,
   whose reference `ReverseClaims` builds itself, is unchanged. No fixture in this repository
   carries a real statement id, so nothing offline could have caught it. `WikidataLiveSmokeTest`
   holds the measurement and its counts; ADR 63's 2026-09-12 amendment records why it belongs in an
   ADR.

9. **The expansion seeds are read through the merge fold too.** *The rows* counts a resolved
   population, and *Expanded* describes a rule read off the log as written — which leaves the two
   sides of a merge free to disagree: a row recorded before the merge cites the id the owner has
   since retired, and a population already on its canonical side finds no seed for it and reports it
   as never expanded. `KnownListCensus.of` therefore reads the seeds through the same
   `Equivalences.canonical` the population is read through. `Expanded` itself still reads the raw
   rows, deliberately: that an expansion ran is a fact the append-only log keeps, and reading a fold
   there would flip a seed back to never-expanded as soon as a retraction dropped the edges carrying
   its reference.
