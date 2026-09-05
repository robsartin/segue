# Calibrate one recommender constant against the harness's first real reading — design (#242)

Issue #242. Committed before the first reading is taken, so the rule below is fixed before the
number it judges exists. The commit timestamp is the evidence.

## What is being decided

Whether one of the two constants the harness sweeps moves, and if so which:

- the scorer default (`RecommendCli.parse`, `Scorer.LIFT`; chosen in ADR 45 by reading ranked
  lists side by side);
- the degree floor (`Recommendations.MIN_CANDIDATE_DEGREE`, five; moved from twelve by ADR 45's
  2026-08-29 amendment on a measured rating distribution, made self-reporting by ADR 57).

Nothing else the ranking uses is swept, so nothing else moves here.

## The evidence

One run of `./gradlew evaluate` on the owner's database (ADR 65), pasted by the owner as the
table it prints: one row per `Setting.GRID` entry, with the columns `EvaluationReport` declares.
Aggregates only; ADR 51 and ADR 65 permit quoting the whole table.

The held-out set is small. Only a promoted rating on a person or group the `--known` file does not
name is eligible (ADR 65), and the last two dated readings put that population in the low
hundreds, so every fifth is a few dozen entities. One entity is several points of hit rate. The
rule below is shaped by that: it asks for a difference no single entity can produce.

## The rule

1. **Rates are read over the reachable.** The denominator for a setting is its `in pool` cell,
   not the held-out count. A held-out entity the graph cannot reach is ingest's miss (ADR 65's
   consequence), and a rule that counted it would be judging expansion coverage, not the scorer.
2. **Scorer first, at the shipped floor.** A scorer displaces `LIFT` only if, at floor five, it
   records at least **three more hits** than `LIFT`, offers **no more negatives** in the top N,
   and **also beats `LIFT` at every other floor in the grid** on hits. Dominance across floors is
   the check that separates an effect from one lucky row.
3. **Then the floor, at the chosen scorer.** A floor displaces five only if, at that scorer, it
   records at least three more hits with no more negatives. A candidate floor below five must in
   addition be checked with one `recommend` run: its `FloorReading` must stay inside ADR 57's
   trigger band (fewer than six or more than nineteen of twenty-five on the floor is the
   re-run trigger, not a permitted resting state).
4. **At most one constant moves.** If both clear the bar, the scorer moves, because it is the
   larger lever, and the floor question is re-asked against the new default in a later issue.
   If neither clears it, **the shipped setting stands**, and that is the recorded result.
5. **Ties and near-misses stand.** Two hits is not three. A mean-rank improvement without a hit
   improvement is not a hit. The rule does not have a second clause for "but it looks better".

## What happens for each outcome

- **A scorer moves.** `RecommendCli.parse`'s default changes; `RecommendCliTest`'s literal pin
  moves with it (that test is the red); ADR 45 gains a dated amendment carrying the table, the
  rule and the outcome. `Setting.GRID` needs nothing: every scorer is already swept.
- **The floor moves.** `Recommendations.MIN_CANDIDATE_DEGREE` changes; `SettingTest` requires the
  new value to be in `Setting.FLOORS` (it is, for 2, 8 and 12; any other value is not this
  issue's to choose); `FloorReading`'s band in ADR 57 is re-read once against the new floor; ADR
  57 gains the dated amendment.
- **The shipped setting stands.** No code changes. ADR 45 gains a dated amendment recording the
  first reading, the rule, and that nothing cleared it. That is a result, not an absence of one:
  the constants are no longer untested.

In every case the amendment quotes the table verbatim and names the commit that fixed this rule.

## Alternatives rejected

- **Read the reading first, then decide what would have counted.** The reason this document
  exists. A rule written after the number is a rationalisation with a table attached.
- **Proportional thresholds (a percentage).** With a few dozen entities a percentage is a
  disguised count; saying "three hits" is honest about the granularity.
- **Move both constants at once.** Two moves against one reading cannot be attributed; the grid
  sweeps them jointly but the record must say which one earned its change.
- **Widen the grid or add a metric first.** #239 fixed the grid deliberately; this issue judges
  with the instrument as built. A second reading with a wider grid is a later issue.
- **Tune the constants the grid does not sweep** (edge-type weights, hub threshold, regard
  centre). No evidence; out of scope by the issue's own text.

## Verification

Nothing here is unit-testable until the outcome is known. The rule is verified by its commit
preceding the reading; a constant move is verified by the existing pins going red then green
(`RecommendCliTest` for the scorer, `SettingTest` for the floor) and the full gate; the amendment
by `AdrIndexTest`, `DocumentationLinksTest` and the guide's derived checks.
