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
