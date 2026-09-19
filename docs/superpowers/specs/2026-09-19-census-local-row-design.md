# A minted local id is not a never-expanded shortfall: the census's `local` row, and the floor rows exclude it — design

Issue #344. Written 2026-09-19 against the code on `main` after #342 (batch claims). Filed beside
#342 on 2026-09-18, on the owner's word, as the census read-back of what that issue built.

## What is wrong

After #342, a list's mapping can carry local ids the owner minted under ADR 59. `support.QidList`
reads one like any qid, so `graphCensus --known <mapping>` counts each under `in the graph` and
then, because `domain.Expanded` reads an owner claim as covering nothing, under `never expanded`
forever: nothing can expand a local entity, and `expandPromotions --known` refuses one with `local
entity`, which the runbook already calls permanent (#326's stop rule). The same floor reaches the
three second-hop rows: a local neighbour of an isolated act — the edge `assert --file` is for — is
"unexpanded" to `SecondHop.toExpandBeside`, so it counts under `with someone to expand beside` and
`distinct to expand` for good, and a `--second-hop` run would visit it and be refused. And the
runbook's derive step says `never expanded` is what a `--known --add` run would expand, which stops
being true the day a mapping carries a local id.

That is the floor the #315 and #326 amendments to ADR 63 and ADR 66 describe, met on a population
the owner created on purpose. The rows should not carry it, and the two tools should agree.

## What changes

### 1. `domain.SecondHop` excludes a local neighbour

`toExpandBeside` adds a neighbour only if its kind is in `WORTH_EXPANDING`, `Expanded` does not
cover it, **and it is not local** (`LocalEntity.isLocal`). `toExpand` follows, so the census's
three nested rows and the `--second-hop` population change together, from the one rule, as ADR 66's
2026-09-13 amendment for #319 intended. The Javadoc says why: no source will ever answer for a
minted id, so it is not a shortfall a later run could close. An isolated member that is itself
local stays isolated on the same rule as any other member; nothing about `isolated()` changes.

### 2. `census.KnownListCensus` counts local ids, and `never expanded` excludes them

- `Population` gains `local`: the population's ids the graph holds a node for that are local, on
  the canonical side — a local id merged onto a canonical is counted as the canonical, as the
  populations already are (`populationsOf`).
- `neverExpanded` and `neverExpandedByKind` exclude local ids; `inTheGraph` and its by-kind rows
  keep counting them. The record's Javadoc says why for each.
- `CensusReport` prints `local` one level under `in the graph`, in both sub-sections, **only when
  it is not zero** — so a file naming no local id prints the block byte for byte as today, and
  every block pasted into an issue so far stays readable against the new one. That is the
  precedent the expander's `added` and `no such entity` rows set (#328).

### 3. `expand.ExpandCli`'s `--known` population excludes local ids

The `--known` population is the file's ids `Expanded` does not cover; it now also drops the local
ones, so the dry run's `to expand` count and the census's `never expanded` row read the same rule
again. The `local entity` refusal in the run stays: `--rated-since` can still name a rated local id,
and the refusal is that population's honest answer. `--known --add` is unchanged: a local id in the
mapping is in the graph, so it was never `to add`.

### 4. Prose

- The guide's census chapter: a paragraph on the `local` row and the exclusion, beside the
  `never expanded` floor paragraph.
- The runbook: the `--second-hop` chapter's stop rule drops its "a `refused` entirely `local
  entity` aside" clauses — that refusal can no longer come from a `--known` or `--second-hop`
  run, and the sentence says where it still can (`--rated-since`); the `--known --add` derive
  step keeps "`never expanded` is what it would expand", now true again; the #342 runbook
  sentence that said "until issue #344 lands, under `never expanded` too" is rewritten to say
  the entity prints under `local` and not under `never expanded`.
- ADR 63, dated amendment: the row, the exclusion, and why the floor rows no longer count what
  the owner minted; the print-when-non-zero choice and its precedent.
- ADR 66, dated amendment: both file populations exclude local ids; the `local entity` refusal is
  reachable from `--rated-since` alone; the derive-step equivalence restored.

## What does not change

- `domain.Expanded`: an owner claim still covers nothing. That reading is deliberate (ADR 59) and
  the exclusion lives in the readers, not in it.
- `named`, `in the graph`, `no known neighbour within N hops` and their meaning; the isolated
  file (`--isolated`) still lists every isolated act, local or not.
- `--rated-since`, `retractEntity`, `ownClaim`.

## Testing

Pure TDD, invented ids only, each guard with a planted positive control observed and removed:

- `SecondHopTest`: a local neighbour (`Q00…`, minted in the fixture log, joined to an isolated act
  by an owner edge) is not in `toExpandBeside` and not in `toExpand`; a non-local unexpanded
  neighbour on the same act still is (the control that the rule did not go blind).
- `KnownListCensusTest`: a file naming a minted, unmerged local id counts it under `inTheGraph`
  and `local`, not under `neverExpanded` nor its kind's `neverExpandedByKind`; the existing
  merged-local-id case keeps its answer (the merge's canonical is not local).
- `CensusReportTest`: the pinned block is unchanged for the existing fixture; a fixture with one
  local id prints the `local` row one level under `in the graph`, in both sub-sections, and the
  block without one prints no such row.
- `ExpandCliTest`: a `--known --dry-run` over a file naming a local id lists it neither under `to
  expand` nor as a refusal; the existing `local entity` refusal test moves to, or is kept on,
  `--rated-since`.
- `DocumentationLinksTest`, `AdrCitationsTest`, the guide-example tests over the prose; the full
  gate, blocking, exit code read before any claim.
